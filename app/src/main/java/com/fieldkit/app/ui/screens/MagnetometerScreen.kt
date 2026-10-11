package com.fieldkit.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.core.AnomalyDetector
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.core.MagStats
import com.fieldkit.app.core.Texts
import com.fieldkit.app.data.MagAnomaly
import com.fieldkit.app.data.MagSweep
import com.fieldkit.app.sensors.MagnetometerSource
import com.fieldkit.app.ui.Field
import com.fieldkit.app.ui.KeepScreenOn
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.SectionCard
import com.fieldkit.app.ui.toast
import kotlin.math.abs
import kotlin.math.max

private const val BASELINE_MS = 10_000L
private const val DISPLAY_POINTS = 600 // one minute at 10 points per second
private const val MAX_TRACE_POINTS = 6000

private enum class MagPhase { IDLE, BASELINE, READY, SWEEP }

/**
 * Collects magnetometer samples through the three stages of a sweep.
 * Mutated on the main thread by the sensor callback; the screen redraws when
 * [onChange] is called (ten times a second).
 */
private class MagRecorder(private val onChange: () -> Unit) {
    var phase = MagPhase.IDLE
        private set
    var current = 0f
        private set

    // Baseline
    private val baselineSamples = ArrayList<Float>()
    private var baselineStartMs = 0L
    var baselineProgress = 0f
        private set
    var mean = 0.0
        private set
    var sd = 0.0
        private set
    var threshold = 0.0
        private set

    // Sweep
    private var detector: AnomalyDetector? = null
    var sweepStartMs = 0L
        private set
    var lastSampleMs = 0L
        private set
    var minUt = 0.0
        private set
    var maxUt = 0.0
        private set
    val anomalies = ArrayList<MagAnomaly>()
    val inAnomaly: Boolean get() = detector?.inAnomaly == true
    private var trace = ArrayList<Float>()
    private var traceIntervalMs = 100L

    /** Last minute of readings for the on-screen trace (all phases). */
    val display = kotlin.collections.ArrayDeque<Float>()

    // 100 ms averaging bucket shared by the display and the stored trace.
    private var bucketStartMs = 0L
    private var bucketSum = 0.0
    private var bucketCount = 0
    private var storeSum = 0.0
    private var storeCount = 0
    private var storeStartMs = 0L

    fun startBaseline() {
        baselineSamples.clear()
        baselineStartMs = 0L
        baselineProgress = 0f
        phase = MagPhase.BASELINE
        onChange()
    }

    fun startSweep() {
        if (phase != MagPhase.READY) return
        detector = AnomalyDetector(mean, threshold)
        anomalies.clear()
        trace = ArrayList()
        traceIntervalMs = 100L
        storeSum = 0.0
        storeCount = 0
        storeStartMs = 0L
        sweepStartMs = System.currentTimeMillis()
        lastSampleMs = sweepStartMs
        minUt = Double.MAX_VALUE
        maxUt = -Double.MAX_VALUE
        phase = MagPhase.SWEEP
        onChange()
    }

    fun onSample(timeMs: Long, magnitudeUt: Float) {
        current = magnitudeUt

        when (phase) {
            MagPhase.BASELINE -> {
                if (baselineStartMs == 0L) baselineStartMs = timeMs
                baselineSamples.add(magnitudeUt)
                baselineProgress = ((timeMs - baselineStartMs).toFloat() / BASELINE_MS).coerceIn(0f, 1f)
                if (timeMs - baselineStartMs >= BASELINE_MS && baselineSamples.size >= 20) {
                    mean = MagStats.mean(baselineSamples)
                    sd = MagStats.standardDeviation(baselineSamples)
                    threshold = MagStats.threshold(sd)
                    phase = MagPhase.READY
                }
            }
            MagPhase.SWEEP -> {
                lastSampleMs = timeMs
                if (magnitudeUt < minUt) minUt = magnitudeUt.toDouble()
                if (magnitudeUt > maxUt) maxUt = magnitudeUt.toDouble()
                detector?.feed(timeMs, magnitudeUt.toDouble())?.let { addAnomaly(it) }

                // Stored trace: averaged to traceIntervalMs, thinned when it gets long.
                if (storeStartMs == 0L) storeStartMs = timeMs
                storeSum += magnitudeUt
                storeCount++
                if (timeMs - storeStartMs >= traceIntervalMs) {
                    trace.add((storeSum / storeCount).toFloat())
                    storeSum = 0.0
                    storeCount = 0
                    storeStartMs = timeMs
                    if (trace.size >= MAX_TRACE_POINTS) {
                        // Halve the resolution so very long sweeps stay a manageable size.
                        val thinned = ArrayList<Float>(trace.size / 2 + 1)
                        var i = 0
                        while (i + 1 < trace.size) {
                            thinned.add((trace[i] + trace[i + 1]) / 2f)
                            i += 2
                        }
                        trace = thinned
                        traceIntervalMs *= 2
                    }
                }
            }
            else -> {}
        }

        // Display trace: one point per 100 ms.
        if (bucketStartMs == 0L) bucketStartMs = timeMs
        bucketSum += magnitudeUt
        bucketCount++
        if (timeMs - bucketStartMs >= 100L) {
            display.addLast((bucketSum / bucketCount).toFloat())
            while (display.size > DISPLAY_POINTS) display.removeFirst()
            bucketSum = 0.0
            bucketCount = 0
            bucketStartMs = timeMs
            onChange()
        }
    }

    private fun addAnomaly(event: AnomalyDetector.Event) {
        anomalies.add(MagAnomaly(event.startMs, event.peakDeviationUt, event.durationMs))
    }

    /** Ends the sweep and returns it, or null if no sweep was running. */
    fun finishSweep(note: String): MagSweep? {
        if (phase != MagPhase.SWEEP) return null
        detector?.finish()?.let { addAnomaly(it) }
        val sweep = MagSweep(
            startedMs = sweepStartMs,
            durationMs = (lastSampleMs - sweepStartMs).coerceAtLeast(0L),
            baselineMeanUt = mean,
            baselineSdUt = sd,
            thresholdUt = threshold,
            minUt = if (minUt == Double.MAX_VALUE) mean else minUt,
            maxUt = if (maxUt == -Double.MAX_VALUE) mean else maxUt,
            anomalies = anomalies.toList(),
            trace = trace.toList(),
            traceIntervalMs = traceIntervalMs,
            note = note,
        )
        detector = null
        phase = MagPhase.READY // the baseline stays valid for another sweep
        onChange()
        return sweep
    }

    fun reset() {
        detector = null
        phase = MagPhase.IDLE
        onChange()
    }
}

@Composable
fun MagnetometerScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val source = remember { MagnetometerSource(context) }
    var tick by remember { mutableIntStateOf(0) }
    val recorder = remember { MagRecorder { tick++ } }
    var note by remember { mutableStateOf("") }

    DisposableEffect(source) {
        if (source.available) source.start { timeMs, value -> recorder.onSample(timeMs, value) }
        onDispose {
            source.stop()
            // Leaving the screen mid-sweep: keep what was recorded.
            recorder.finishSweep(note.trim())?.let { sweep ->
                vm.update { it.copy(magSweeps = it.magSweeps + sweep) }
            }
        }
    }
    if (recorder.phase == MagPhase.BASELINE || recorder.phase == MagPhase.SWEEP) KeepScreenOn()

    // Reading `tick` here makes the whole screen follow the recorder (10 updates a second).
    val frame = tick
    val phase = recorder.phase

    ModeScreen(Texts.CAPTION_MAGNET) {
        SectionCard {
            Text(Texts.MAG_LABEL, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }

        if (!source.available) {
            SectionCard("No magnetometer") {
                Text("This phone has no magnetic field sensor, so this mode cannot run.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            SectionCard("Field strength") {
                Text(
                    Fmt.microtesla(recorder.current.toDouble()) +
                        if (phase == MagPhase.SWEEP || phase == MagPhase.READY) {
                            val deviation = recorder.current - recorder.mean
                            "   (${if (deviation >= 0) "+" else ""}${Fmt.num(deviation, 1)} from baseline)"
                        } else "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (recorder.inAnomaly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                MagTrace(recorder, frame)
                Note("Trace: last 60 seconds. Earth's field alone is 25-65 µT. Sensor: ${source.sensorName}")
            }

            when (phase) {
                MagPhase.IDLE -> SectionCard("1. Capture a local baseline") {
                    Text(
                        "Stand a few metres from anything electrical or metallic, hold the phone flat and still, " +
                            "and capture 10 seconds. Anomalies are judged against this baseline.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { recorder.startBaseline() }) { Text("Capture baseline (10 s)") }
                }

                MagPhase.BASELINE -> SectionCard("Capturing baseline: hold still") {
                    val progress = recorder.baselineProgress
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { recorder.reset() }) { Text("Cancel") }
                }

                MagPhase.READY -> SectionCard("2. Sweep") {
                    Text(
                        "Baseline ${Fmt.microtesla(recorder.mean)} (sd ${Fmt.num(recorder.sd, 2)}). " +
                            "Anything more than ±${Fmt.microtesla(recorder.threshold)} from it is flagged.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Note(
                        "Move slowly and keep the phone in the same orientation: turning it changes the reading by " +
                            "several µT on its own. Phone cases with magnets will swamp everything."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { recorder.startSweep() }) { Text("Start sweep") }
                        OutlinedButton(onClick = { recorder.startBaseline() }) { Text("Redo baseline") }
                    }
                }

                MagPhase.SWEEP -> SectionCard("Sweeping") {
                    val elapsed = recorder.lastSampleMs - recorder.sweepStartMs
                    Text(
                        "${Fmt.duration(elapsed)}   ${recorder.anomalies.size} anomalies" +
                            if (recorder.inAnomaly) "   ANOMALY NOW" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (recorder.inAnomaly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    for (a in recorder.anomalies.takeLast(5).asReversed()) {
                        Note("${Fmt.time(a.timeMs)}   ${if (a.peakDeviationUt >= 0) "+" else ""}${Fmt.microtesla(a.peakDeviationUt)}   ${Fmt.num(a.durationMs / 1000.0, 1)} s")
                    }
                    Field(note, "Where / what you swept (optional)") { note = it }
                    Button(onClick = {
                        recorder.finishSweep(note.trim())?.let { sweep ->
                            vm.update { it.copy(magSweeps = it.magSweeps + sweep) }
                            toast(context, "Sweep saved to the session")
                        }
                        note = ""
                    }) { Text("Stop and save") }
                }
            }

            SectionCard("Reading an anomaly") {
                Note(
                    "A flagged anomaly means a magnet, a current-carrying cable, a motor, a speaker, a transformer or a " +
                        "large piece of steel is within roughly a metre. That is all it means. No anomaly means none of " +
                        "those is close by: it says nothing about RF, microwaves or sound."
                )
            }
        }

        val sweeps = vm.session.magSweeps
        SectionCard("Saved sweeps (${sweeps.size})") {
            if (sweeps.isEmpty()) Note("None yet.")
            for (index in sweeps.indices.reversed()) {
                val m = sweeps[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${Fmt.dateTime(m.startedMs)}   ${Fmt.duration(m.durationMs)}   ${m.anomalies.size} anomalies",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Note(
                            "Baseline ${Fmt.microtesla(m.baselineMeanUt)}, range ${Fmt.microtesla(m.minUt)} to ${Fmt.microtesla(m.maxUt)}" +
                                if (m.note.isNotBlank()) "   ${m.note}" else ""
                        )
                    }
                    IconButton(onClick = {
                        vm.update { s -> s.copy(magSweeps = s.magSweeps.filterIndexed { i, _ -> i != index }) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Delete sweep") }
                }
            }
        }
    }
}

/** µT trace with the baseline and the anomaly thresholds drawn in. */
@Composable
private fun MagTrace(recorder: MagRecorder, frame: Int) {
    val lineColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val thresholdColor = MaterialTheme.colorScheme.error
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }

    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        if (frame < 0) return@Canvas // reading frame makes each update redraw this canvas
        val points = recorder.display
        if (points.isEmpty()) return@Canvas

        val hasBaseline = recorder.phase == MagPhase.READY || recorder.phase == MagPhase.SWEEP
        val center = if (hasBaseline) recorder.mean.toFloat() else points.average().toFloat()
        var halfRange = if (hasBaseline) (recorder.threshold * 2).toFloat() else 10f
        for (v in points) halfRange = max(halfRange, abs(v - center) * 1.15f)
        fun yOf(value: Float): Float = size.height * (0.5f - (value - center) / (2f * halfRange))

        labelPaint.color = labelArgb
        labelPaint.textSize = 10.sp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

        drawLine(baselineColor, Offset(0f, yOf(center)), Offset(size.width, yOf(center)), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText(Fmt.microtesla(center.toDouble()), 4f, yOf(center) - 4f, labelPaint)
        if (hasBaseline) {
            val t = recorder.threshold.toFloat()
            for (edge in listOf(center + t, center - t)) {
                drawLine(thresholdColor, Offset(0f, yOf(edge)), Offset(size.width, yOf(edge)), strokeWidth = 1.5f, pathEffect = dash)
            }
            drawContext.canvas.nativeCanvas.drawText("+${Fmt.num(t.toDouble(), 1)}", 4f, yOf(center + t) - 4f, labelPaint)
        }

        val stepX = size.width / (DISPLAY_POINTS - 1)
        val startX = size.width - (points.size - 1) * stepX // newest point at the right edge
        val path = Path()
        var i = 0
        for (v in points) {
            val x = startX + i * stepX
            if (i == 0) path.moveTo(x, yOf(v)) else path.lineTo(x, yOf(v))
            i++
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}
