package com.fieldkit.app.ui.screens

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.audio.AudioEngine
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.core.SpectralPeak
import com.fieldkit.app.core.Spectrum
import com.fieldkit.app.core.Texts
import com.fieldkit.app.data.PeakEvent
import com.fieldkit.app.data.Snapshot
import com.fieldkit.app.ui.CaptionBar
import com.fieldkit.app.ui.KeepScreenOn
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.OnAppStop
import com.fieldkit.app.ui.PermissionExplainer
import com.fieldkit.app.ui.SectionCard
import com.fieldkit.app.ui.hasPermission
import com.fieldkit.app.ui.toast
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

private const val SPEC_W = 300 // spectrogram columns kept (about 12.8 s at 23 columns/s)
private const val SPEC_H = 240 // spectrogram rows
private const val BARS = 64 // log-spaced bars in the spectrum view
private const val MIN_DB = -110f
private const val MAX_DB = -10f
private val FREQ_RATIO = Spectrum.MAX_HZ / Spectrum.MIN_HZ

/**
 * Everything the acoustic views draw from. A plain (non-Compose) object that is
 * mutated once per audio frame; the screen bumps a counter to trigger redraws.
 */
private class AcousticDisplay {
    val bitmap: Bitmap = Bitmap.createBitmap(SPEC_W, SPEC_H, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()
    private val pixels = IntArray(SPEC_W * SPEC_H) { BLACK }

    // Spectrum bars and their peak-hold values.
    val bars = FloatArray(BARS) { MIN_DB }
    val hold = FloatArray(BARS) { MIN_DB }
    val holdHz = DoubleArray(BARS)
    val barCenterHz = DoubleArray(BARS)
    private val barLo = IntArray(BARS)
    private val barHi = IntArray(BARS)

    // Bin range drawn in each spectrogram row (row 0 = top = highest frequency).
    private val rowLo = IntArray(SPEC_H)
    private val rowHi = IntArray(SPEC_H)

    var logScale = false
        private set

    var hfDb = MIN_DB.toDouble()
    var broadbandDb = MIN_DB.toDouble()
    var hfPeak = SpectralPeak(Spectrum.HF_LO_HZ, MIN_DB.toDouble())
    var heldPeak: SpectralPeak? = null
    var heldHfPeak: SpectralPeak? = null
    var hasData = false

    init {
        for (i in 0 until BARS) {
            val lowHz = Spectrum.MIN_HZ * FREQ_RATIO.pow(i.toDouble() / BARS)
            val highHz = Spectrum.MIN_HZ * FREQ_RATIO.pow((i + 1).toDouble() / BARS)
            barLo[i] = floor(lowHz / AudioEngine.BIN_HZ).toInt().coerceIn(1, AudioEngine.BIN_COUNT - 1)
            barHi[i] = (ceil(highHz / AudioEngine.BIN_HZ).toInt() - 1).coerceIn(barLo[i], AudioEngine.BIN_COUNT - 1)
            barCenterHz[i] = sqrt(lowHz * highHz)
        }
        setScale(false)
    }

    /** Switch the spectrogram's frequency axis between linear and logarithmic. Clears the image. */
    fun setScale(log: Boolean) {
        logScale = log
        for (row in 0 until SPEC_H) {
            val topHz = hzAtFraction(row.toDouble() / SPEC_H)
            val bottomHz = hzAtFraction((row + 1).toDouble() / SPEC_H)
            rowLo[row] = floor(bottomHz / AudioEngine.BIN_HZ).toInt().coerceIn(1, AudioEngine.BIN_COUNT - 1)
            rowHi[row] = (ceil(topHz / AudioEngine.BIN_HZ).toInt() - 1).coerceIn(rowLo[row], AudioEngine.BIN_COUNT - 1)
        }
        pixels.fill(BLACK)
        bitmap.setPixels(pixels, 0, SPEC_W, 0, 0, SPEC_W, SPEC_H)
    }

    /** Frequency at a vertical position, 0 = top of the spectrogram, 1 = bottom. */
    private fun hzAtFraction(fromTop: Double): Double =
        if (logScale) Spectrum.MIN_HZ * FREQ_RATIO.pow(1.0 - fromTop)
        else Spectrum.MAX_HZ * (1.0 - fromTop)

    /** Vertical position (0 = top, 1 = bottom) of a frequency on the spectrogram. */
    fun fractionFromTop(hz: Double): Float =
        if (logScale) (1.0 - ln(hz / Spectrum.MIN_HZ) / ln(FREQ_RATIO)).toFloat()
        else (1.0 - hz / Spectrum.MAX_HZ).toFloat()

    fun consume(db: FloatArray) {
        hasData = true

        // Spectrum bars + hold.
        for (i in 0 until BARS) {
            var best = barLo[i]
            for (k in barLo[i]..barHi[i]) if (db[k] > db[best]) best = k
            bars[i] = db[best]
            if (db[best] > hold[i]) {
                hold[i] = db[best]
                holdHz[i] = best * AudioEngine.BIN_HZ
            }
        }

        // Band meters and peaks.
        broadbandDb = Spectrum.bandPowerDb(db, AudioEngine.BIN_HZ, Spectrum.MIN_HZ, Spectrum.MAX_HZ)
        hfDb = Spectrum.bandPowerDb(db, AudioEngine.BIN_HZ, Spectrum.HF_LO_HZ, Spectrum.HF_HI_HZ)
        hfPeak = Spectrum.peak(db, AudioEngine.BIN_HZ, Spectrum.HF_LO_HZ, Spectrum.HF_HI_HZ)
        val overall = Spectrum.peak(db, AudioEngine.BIN_HZ, Spectrum.MIN_HZ, Spectrum.MAX_HZ)
        if (overall.db > (heldPeak?.db ?: -1e9)) heldPeak = overall
        if (hfPeak.db > (heldHfPeak?.db ?: -1e9)) heldHfPeak = hfPeak

        // Spectrogram: scroll one column left, paint the newest on the right edge.
        for (row in 0 until SPEC_H) {
            val offset = row * SPEC_W
            System.arraycopy(pixels, offset + 1, pixels, offset, SPEC_W - 1)
            var level = db[rowLo[row]]
            for (k in rowLo[row]..rowHi[row]) if (db[k] > level) level = db[k]
            pixels[offset + SPEC_W - 1] = Spectrum.colorFor(level, MIN_DB, MAX_DB)
        }
        bitmap.setPixels(pixels, 0, SPEC_W, 0, 0, SPEC_W, SPEC_H)
    }

    fun resetHold() {
        hold.fill(MIN_DB)
        holdHz.fill(0.0)
        heldPeak = null
        heldHfPeak = null
    }

    /** Strongest held bar within two bars of [index], or null if nothing has been held there. */
    fun heldPeakNear(index: Int): SpectralPeak? {
        var best = -1
        for (i in (index - 2).coerceAtLeast(0)..(index + 2).coerceAtMost(BARS - 1)) {
            if (hold[i] > MIN_DB + 1f && (best < 0 || hold[i] > hold[best])) best = i
        }
        return if (best < 0) null else SpectralPeak(holdHz[best], hold[best].toDouble())
    }

    companion object {
        private const val BLACK = -0x1000000 // 0xFF000000
    }
}

@Composable
fun AcousticScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }

    if (granted) {
        AcousticSurvey(vm)
    } else {
        ModeScreen(Texts.CAPTION_ACOUSTIC) {
            PermissionExplainer(
                title = "Microphone access",
                explanation = Texts.PERMISSION_MIC,
                denied = denied,
                onRequest = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            )
            SectionCard("About these readings") {
                Note(Texts.MIC_NOTE)
            }
        }
    }
}

@Composable
private fun AcousticSurvey(vm: AppViewModel) {
    val context = LocalContext.current
    val engine = remember { AudioEngine() }
    val display = remember { AcousticDisplay() }
    val active by engine.active.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var logScale by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Feed every analysed frame into the display, then redraw.
    LaunchedEffect(engine) {
        var lastSeq = -1L
        engine.frames.collect { frame ->
            if (frame != null && frame.seq != lastSeq) {
                lastSeq = frame.seq
                display.consume(frame.db)
                tick++
            }
        }
    }
    DisposableEffect(engine) { onDispose { engine.stop() } }
    OnAppStop { engine.stop() }
    if (active) KeepScreenOn()

    fun logPeak(peak: SpectralPeak?) {
        if (peak == null) {
            toast(context, "Nothing held yet. Start the survey first.")
            return
        }
        val event = PeakEvent(
            timeMs = System.currentTimeMillis(),
            frequencyHz = peak.frequencyHz,
            relDb = peak.db,
            inHfBand = peak.frequencyHz >= Spectrum.HF_LO_HZ,
        )
        vm.update { it.copy(peaks = it.peaks + event) }
        toast(context, "Logged ${Fmt.hz(peak.frequencyHz)} at ${Fmt.db(peak.db)}")
    }

    ModeScreen(Texts.CAPTION_ACOUSTIC) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (active) engine.stop() else error = engine.start(context.applicationContext)
            }) { Text(if (active) "Stop" else "Start survey") }
            FilterChip(
                selected = logScale,
                onClick = {
                    logScale = !logScale
                    display.setScale(logScale)
                    tick++
                },
                label = { Text("Log frequency axis") },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (active) Note("Input: ${engine.sourceLabel}, 48 kHz, ${AudioEngine.FFT_SIZE}-point FFT (${Fmt.num(AudioEngine.BIN_HZ, 1)} Hz bins)")

        Text("Spectrogram (newest at right)", style = MaterialTheme.typography.titleSmall)
        Spectrogram(display, tick)

        Text("Spectrum, 20 Hz-24 kHz (tap a bar to log its held peak)", style = MaterialTheme.typography.titleSmall)
        SpectrumBars(display, tick) { index -> logPeak(display.heldPeakNear(index)) }

        BandMeters(display, tick)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { logPeak(display.heldPeak) }) { Text("Log strongest peak") }
            OutlinedButton(onClick = { logPeak(display.heldHfPeak) }) { Text("Log 18-24 kHz peak") }
            OutlinedButton(onClick = { display.resetHold(); tick++ }) { Text("Reset hold") }
            OutlinedButton(onClick = {
                if (!display.hasData) {
                    toast(context, "Nothing to snapshot yet.")
                } else {
                    val now = System.currentTimeMillis()
                    val name = vm.store.saveSnapshot(vm.session.id, display.bitmap, now)
                    if (name == null) {
                        toast(context, "Could not save the snapshot.")
                    } else {
                        val axis = if (display.logScale) "log" else "linear"
                        val caption = "Spectrogram, $axis frequency axis 20 Hz-24 kHz (24 kHz at top), about 13 s wide, " +
                            "colour = relative level ${MIN_DB.toInt()} to ${MAX_DB.toInt()} dB"
                        vm.update { it.copy(snapshots = it.snapshots + Snapshot(now, name, caption)) }
                        toast(context, "Snapshot added to the session")
                    }
                }
            }) { Text("Save snapshot") }
        }

        SectionCard("About these readings") {
            Note(Texts.MIC_NOTE)
            Note(Texts.MIC_CONTEXT)
        }

        val peaks = vm.session.peaks
        SectionCard("Logged peaks (${peaks.size}), ${vm.session.snapshots.size} snapshots") {
            if (peaks.isEmpty()) Note("None yet.")
            // Newest first; indices refer to the session list so delete removes the right one.
            for (index in peaks.indices.reversed().take(8)) {
                val p = peaks[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Fmt.time(p.timeMs)}   ${Fmt.hz(p.frequencyHz)}   ${Fmt.db(p.relDb)}" +
                            if (p.inHfBand) "   18-24 kHz" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        vm.update { s -> s.copy(peaks = s.peaks.filterIndexed { i, _ -> i != index }) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Delete peak") }
                }
            }
            if (peaks.size > 8) Note("Showing the latest 8. All ${peaks.size} are in the report.")
        }
    }
}

@Composable
private fun Spectrogram(display: AcousticDisplay, tick: Int) {
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE } }
    val bandColor = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        if (tick < 0) return@Canvas // reading tick makes every new frame redraw this canvas
        drawImage(
            image = display.image,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
        // Lower edge of the canine-audible band.
        val y18 = display.fractionFromTop(Spectrum.HF_LO_HZ) * size.height
        drawLine(
            color = bandColor,
            start = Offset(0f, y18),
            end = Offset(size.width, y18),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
        labelPaint.textSize = 10.sp.toPx()
        val marks = if (display.logScale) listOf(100.0, 1000.0, 10000.0, 18000.0)
        else listOf(5000.0, 10000.0, 15000.0, 18000.0)
        for (hz in marks) {
            val y = display.fractionFromTop(hz) * size.height
            drawContext.canvas.nativeCanvas.drawText(Fmt.hz(hz), 6f, y - 4f, labelPaint)
        }
        drawContext.canvas.nativeCanvas.drawText("18-24 kHz band", size.width * 0.55f, labelPaint.textSize + 4f, labelPaint)
    }
}

@Composable
private fun SpectrumBars(display: AcousticDisplay, tick: Int, onTapBar: (Int) -> Unit) {
    val barColor = MaterialTheme.colorScheme.primary
    val hfColor = MaterialTheme.colorScheme.tertiary
    val holdColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val hfBackground = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    val currentOnTap by rememberUpdatedState(onTapBar)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { position ->
                    currentOnTap((position.x / size.width * BARS).toInt().coerceIn(0, BARS - 1))
                })
            }
    ) {
        if (tick < 0) return@Canvas // reading tick makes every new frame redraw this canvas
        val barWidth = size.width / BARS
        fun xOf(hz: Double): Float = (ln(hz / Spectrum.MIN_HZ) / ln(FREQ_RATIO)).toFloat() * size.width
        fun yOf(db: Float): Float = size.height * (1f - ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f))

        // Shade the 18-24 kHz band.
        val x18 = xOf(Spectrum.HF_LO_HZ)
        drawRect(hfBackground, topLeft = Offset(x18, 0f), size = Size(size.width - x18, size.height))

        // dB grid.
        labelPaint.color = labelArgb
        labelPaint.textSize = 10.sp.toPx()
        for (db in listOf(-20f, -50f, -80f)) {
            val y = yOf(db)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("${db.toInt()} dB", 4f, y - 3f, labelPaint)
        }

        for (i in 0 until BARS) {
            val color = if (display.barCenterHz[i] >= Spectrum.HF_LO_HZ) hfColor else barColor
            val top = yOf(display.bars[i])
            drawRect(color, topLeft = Offset(i * barWidth, top), size = Size(barWidth * 0.8f, size.height - top))
            if (display.hold[i] > MIN_DB + 1f) {
                val holdY = yOf(display.hold[i])
                drawLine(holdColor, Offset(i * barWidth, holdY), Offset(i * barWidth + barWidth * 0.8f, holdY), strokeWidth = 2f)
            }
        }

        // Frequency axis labels along the bottom.
        for (hz in listOf(100.0, 1000.0, 10000.0)) {
            drawContext.canvas.nativeCanvas.drawText(Fmt.hz(hz), xOf(hz), size.height - 4f, labelPaint)
        }
        drawContext.canvas.nativeCanvas.drawText("18k", x18 + 2f, labelPaint.textSize + 2f, labelPaint)
    }
}

@Composable
private fun BandMeters(display: AcousticDisplay, tick: Int) {
    // `tick` changes with every audio frame, so this recomposes and re-reads the display.
    val showData = display.hasData && tick >= 0
    fun fraction(db: Double): Float = ((db - MIN_DB) / (MAX_DB - MIN_DB)).toFloat().coerceIn(0f, 1f)
    val hfFraction = if (showData) fraction(display.hfDb) else 0f
    val broadbandFraction = if (showData) fraction(display.broadbandDb) else 0f

    SectionCard {
        Text(Texts.HF_BAND_LABEL, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        LinearProgressIndicator(
            progress = { hfFraction },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            if (showData) "${Fmt.db(display.hfDb)} rel   strongest: ${Fmt.hz(display.hfPeak.frequencyHz)} at ${Fmt.db(display.hfPeak.db)}"
            else "Not running",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Whole band, 20 Hz-24 kHz", style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(
            progress = { broadbandFraction },
            modifier = Modifier.fillMaxWidth(),
        )
        val held = display.heldPeak
        Text(
            if (showData) "${Fmt.db(display.broadbandDb)} rel" +
                (if (held != null) "   held peak: ${Fmt.hz(held.frequencyHz)} at ${Fmt.db(held.db)}" else "")
            else "Not running",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
