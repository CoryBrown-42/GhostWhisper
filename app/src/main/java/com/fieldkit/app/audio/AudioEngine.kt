package com.fieldkit.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.fieldkit.app.core.LevelSample
import com.fieldkit.app.core.Spectrum
import com.fieldkit.app.core.SpectrumAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.pow

/**
 * Microphone -> spectrum. Captures 48 kHz mono PCM with AudioRecord on its own
 * thread and publishes one dB spectrum per hop (about 23 per second).
 *
 * Audio only ever lives in memory: nothing is written to disk.
 * Callers must hold RECORD_AUDIO before calling [start] or [measureLevels].
 */
class AudioEngine {

    /** One analysed block. [db] has [BIN_COUNT] values, dB re digital full scale. */
    class Frame(val seq: Long, val db: FloatArray)

    private val _frames = MutableStateFlow<Frame?>(null)
    val frames: StateFlow<Frame?> = _frames

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    @Volatile
    private var keepRunning = false
    private var worker: Thread? = null

    /** Which Android audio source was opened (shown on screen: it affects filtering). */
    @Volatile
    var sourceLabel: String = ""
        private set

    /** Returns null on success, or a message explaining why capture could not start. */
    fun start(context: Context): String? {
        if (keepRunning) return null
        val opened = openRecorder(context)
            ?: return "Could not open the microphone at 48 kHz. Another app may be using it."
        sourceLabel = opened.second
        keepRunning = true
        _active.value = true
        worker = thread(name = "fieldkit-audio") { captureLoop(opened.first) }
        return null
    }

    fun stop() {
        keepRunning = false
        val t = worker
        worker = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(600)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun captureLoop(record: AudioRecord) {
        val analyzer = SpectrumAnalyzer(FFT_SIZE, SAMPLE_RATE)
        val window = ShortArray(FFT_SIZE)
        val hop = ShortArray(HOP)
        var seq = 0L
        try {
            record.startRecording()
            while (keepRunning) {
                var got = 0
                while (got < HOP && keepRunning) {
                    val n = record.read(hop, got, HOP - got)
                    if (n <= 0) {
                        keepRunning = false // device error or recorder released
                    } else {
                        got += n
                    }
                }
                if (got < HOP) break
                // Slide the analysis window by one hop (50% overlap).
                System.arraycopy(window, HOP, window, 0, FFT_SIZE - HOP)
                System.arraycopy(hop, 0, window, FFT_SIZE - HOP, HOP)
                val db = FloatArray(analyzer.binCount)
                analyzer.analyze(window, db)
                _frames.value = Frame(seq++, db)
            }
        } catch (e: Exception) {
            // Fall through to cleanup; the UI sees active = false.
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                // already stopped
            }
            record.release()
            keepRunning = false
            _active.value = false
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val FFT_SIZE = 4096
        const val HOP = FFT_SIZE / 2
        const val BIN_COUNT = FFT_SIZE / 2 + 1
        const val BIN_HZ = SAMPLE_RATE.toDouble() / FFT_SIZE

        /**
         * Opens the least-processed microphone source the device offers.
         * UNPROCESSED skips noise suppression and automatic gain, which would
         * otherwise distort level comparisons; not every phone supports it.
         */
        @SuppressLint("MissingPermission")
        private fun openRecorder(context: Context): Pair<AudioRecord, String>? {
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) return null
            val bufferBytes = maxOf(minBytes, FFT_SIZE * 4)

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val unprocessed =
                audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val candidates = ArrayList<Pair<Int, String>>()
            if (unprocessed) candidates.add(MediaRecorder.AudioSource.UNPROCESSED to "unprocessed mic")
            candidates.add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice-recognition mic (light processing)")
            candidates.add(MediaRecorder.AudioSource.MIC to "default mic (may apply noise suppression)")

            for ((source, label) in candidates) {
                try {
                    val record = AudioRecord(
                        source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes
                    )
                    if (record.state == AudioRecord.STATE_INITIALIZED) return record to label
                    record.release()
                } catch (e: Exception) {
                    // try the next source
                }
            }
            return null
        }

        /**
         * Records for [durationMs] and returns the average broadband and
         * 18-24 kHz levels. Used by Range Map to attach an objective number to
         * each stop. Returns null if the microphone could not be opened.
         */
        suspend fun measureLevels(context: Context, durationMs: Int = 1000): LevelSample? =
            withContext(Dispatchers.IO) {
                val record = openRecorder(context)?.first ?: return@withContext null
                try {
                    val analyzer = SpectrumAnalyzer(FFT_SIZE, SAMPLE_RATE)
                    val block = ShortArray(FFT_SIZE)
                    val db = FloatArray(analyzer.binCount)
                    val settleBlocks = 2 // discard ~170 ms while the input stage settles
                    val blocks = maxOf(1, durationMs * SAMPLE_RATE / 1000 / FFT_SIZE)
                    var broadSum = 0.0
                    var hfSum = 0.0
                    var counted = 0
                    record.startRecording()
                    for (i in 0 until settleBlocks + blocks) {
                        var got = 0
                        while (got < FFT_SIZE) {
                            val n = record.read(block, got, FFT_SIZE - got)
                            if (n <= 0) return@withContext null
                            got += n
                        }
                        if (i < settleBlocks) continue
                        analyzer.analyze(block, db)
                        // Average power, not dB, so loud moments are weighted correctly.
                        broadSum += 10.0.pow(Spectrum.bandPowerDb(db, BIN_HZ, Spectrum.MIN_HZ, Spectrum.MAX_HZ) / 10.0)
                        hfSum += 10.0.pow(Spectrum.bandPowerDb(db, BIN_HZ, Spectrum.HF_LO_HZ, Spectrum.HF_HI_HZ) / 10.0)
                        counted++
                    }
                    LevelSample(10.0 * log10(broadSum / counted), 10.0 * log10(hfSum / counted))
                } catch (e: Exception) {
                    null
                } finally {
                    try {
                        record.stop()
                    } catch (e: Exception) {
                        // not started
                    }
                    record.release()
                }
            }
    }
}
