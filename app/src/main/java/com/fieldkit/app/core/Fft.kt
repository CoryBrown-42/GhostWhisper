package com.fieldkit.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Self-contained iterative radix-2 FFT (Cooley-Tukey, in place).
 *
 * Design choice: a ~40-line FFT instead of JTransforms. The app needs one
 * fixed-size real transform ~23 times a second; that is trivial work, and a
 * local implementation keeps the project free of extra dependencies and fully
 * auditable. Verified against known sine inputs in FftTest.
 */
class Fft(val n: Int) {
    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
    }

    private val cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val reversed = IntArray(n).also { table ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Forward transform; [re] and [im] (length n) are overwritten with the result. */
    fun forward(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += size
            }
            size = size shl 1
        }
    }
}

/**
 * Turns blocks of 16-bit PCM into a magnitude spectrum in dB relative to
 * digital full scale (dBFS). 0 dBFS = a full-scale sine. These are NOT sound
 * pressure levels: the microphone's sensitivity and frequency response are
 * unknown, so values are only comparable with each other on the same phone.
 */
class SpectrumAnalyzer(val fftSize: Int, val sampleRate: Int) {
    val binCount: Int = fftSize / 2 + 1
    val binHz: Double = sampleRate.toDouble() / fftSize

    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    // A full-scale sine through a Hann window (coherent gain 0.5) peaks at N/4 * 32768.
    private val reference = fftSize / 4.0 * 32768.0
    private val referenceSq = reference * reference

    /** [samples] must hold at least fftSize values; [outDb] at least binCount. */
    fun analyze(samples: ShortArray, outDb: FloatArray) {
        for (i in 0 until fftSize) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        fft.forward(re, im)
        for (k in 0 until binCount) {
            val power = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            outDb[k] = if (power <= 0.0) Spectrum.FLOOR_DB
            else max(Spectrum.FLOOR_DB, (10.0 * log10(power / referenceSq)).toFloat())
        }
    }
}

data class SpectralPeak(val frequencyHz: Double, val db: Double)

/** One averaged level measurement (used by Range Map at each stop). */
data class LevelSample(val broadbandDb: Double, val hfBandDb: Double)

/** Helpers that work on a dB spectrum produced by [SpectrumAnalyzer]. */
object Spectrum {
    const val FLOOR_DB = -140f

    /** Display range of the audible-plus band this app shows. */
    const val MIN_HZ = 20.0
    const val MAX_HZ = 24000.0

    /** "Canine-audible band": above typical adult human hearing, within dog hearing. */
    const val HF_LO_HZ = 18000.0
    const val HF_HI_HZ = 24000.0

    // Summing Hann-windowed bins over-counts power by the window's equivalent
    // noise bandwidth (1.5 bins = 1.76 dB).
    private const val HANN_ENBW_DB = 1.76

    fun binOf(hz: Double, binHz: Double, binCount: Int): Int =
        (hz / binHz).roundToInt().coerceIn(0, binCount - 1)

    /** Total power between two frequencies, in dB (same reference as the input). */
    fun bandPowerDb(db: FloatArray, binHz: Double, loHz: Double, hiHz: Double): Double {
        val lo = binOf(loHz, binHz, db.size).coerceAtLeast(1)
        val hi = binOf(hiHz, binHz, db.size)
        var sum = 0.0
        for (k in lo..hi) sum += 10.0.pow(db[k] / 10.0)
        return if (sum <= 0.0) FLOOR_DB.toDouble() else max(FLOOR_DB.toDouble(), 10.0 * log10(sum) - HANN_ENBW_DB)
    }

    /** Strongest bin between two frequencies, refined by parabolic interpolation. */
    fun peak(db: FloatArray, binHz: Double, loHz: Double, hiHz: Double): SpectralPeak {
        val lo = binOf(loHz, binHz, db.size).coerceAtLeast(1)
        val hi = binOf(hiHz, binHz, db.size)
        var best = lo
        for (k in lo..hi) if (db[k] > db[best]) best = k
        var offset = 0.0
        var value = db[best].toDouble()
        if (best > 0 && best < db.size - 1) {
            val a = db[best - 1].toDouble()
            val b = db[best].toDouble()
            val c = db[best + 1].toDouble()
            val denominator = a - 2 * b + c
            if (denominator < -1e-9) {
                offset = (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5)
                value = b - 0.25 * (a - c) * offset
            }
        }
        return SpectralPeak((best + offset) * binHz, value)
    }

    /** Dark-to-bright colour ramp for the spectrogram. Returns ARGB. */
    fun colorFor(db: Float, minDb: Float, maxDb: Float): Int {
        val t = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        // black -> indigo -> magenta -> orange -> pale yellow
        val stops = RAMP
        val scaled = t * (stops.size - 1)
        val i = scaled.toInt().coerceAtMost(stops.size - 2)
        val f = scaled - i
        val a = stops[i]
        val b = stops[i + 1]
        val r = (a[0] + (b[0] - a[0]) * f).toInt()
        val g = (a[1] + (b[1] - a[1]) * f).toInt()
        val bl = (a[2] + (b[2] - a[2]) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private val RAMP = arrayOf(
        intArrayOf(0, 0, 0),
        intArrayOf(40, 20, 110),
        intArrayOf(170, 40, 120),
        intArrayOf(245, 130, 40),
        intArrayOf(255, 245, 190),
    )
}
