package com.fieldkit.app.core

import com.fieldkit.app.data.EffectResult
import com.fieldkit.app.data.RangeStop
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The decision logic behind the Shielding Test, Range Map and Magnetometer
 * Sweep. Pure Kotlin (no Android), covered by AnalysisTest.
 */

data class Verdict(val title: String, val detail: String)

object Shielding {

    /** Applies to every verdict: what this test design cannot rule out. */
    const val CAVEAT =
        "Limits of this test: the observer knows which condition is applied, so expectation alone " +
            "can change what is perceived. Repeat the run several times, ideally with a helper " +
            "applying real and sham shielding without telling the observer. Faraday fabric also " +
            "muffles some high-frequency sound, and hearing protection does not stop low-frequency " +
            "or bone-conducted sound."

    /**
     * The discrimination logic:
     *   A = inside a Faraday enclosure (blocks RF, passes sound)
     *   B = full hearing protection   (blocks sound, passes RF)
     *
     *   A removes it, B does not -> consistent with an RF-mediated effect
     *   B removes it, A does not -> consistent with an acoustic effect
     *   neither removes it       -> neither simple mechanism
     *   both remove it           -> inconclusive
     */
    fun interpret(
        baselinePresent: Boolean,
        faraday: EffectResult,
        returnedBetween: Boolean,
        hearingProtection: EffectResult,
    ): Verdict {
        if (!baselinePresent) {
            return Verdict(
                "Not testable right now",
                "The effect was not present before shielding was applied, so neither condition can " +
                    "show that it removed anything. Run the test while the effect is being perceived.",
            )
        }

        val a = faraday
        val b = hearingProtection
        val base = when {
            a == EffectResult.ABSENT && b == EffectResult.PRESENT -> Verdict(
                "Consistent with an RF-mediated effect",
                "The Faraday enclosure removed the effect and hearing protection did not. That is the " +
                    "pattern expected of an RF-mediated effect such as the microwave auditory effect " +
                    "(perceived as internal sound). Confirming it needs an RF measurement with external " +
                    "hardware: this phone cannot measure RF.",
            )
            a == EffectResult.PRESENT && b == EffectResult.ABSENT -> Verdict(
                "Consistent with an acoustic effect",
                "Hearing protection removed the effect and the Faraday enclosure did not. That is the " +
                    "pattern expected of airborne sound. Use Acoustic Survey to look for a matching " +
                    "frequency, then look for the mundane source (machinery, electronics, pest repellers).",
            )
            a == EffectResult.PRESENT && b == EffectResult.PRESENT -> Verdict(
                "Neither simple mechanism",
                "Neither blocking RF nor blocking airborne sound removed the effect. Check confounds: " +
                    "low-frequency sound or vibration (which passes hearing protection), a leaky " +
                    "enclosure, or a cause that is not an external emission at all. Tinnitus, inner-ear " +
                    "and other medical conditions produce internal sound, headache and vertigo and would " +
                    "give exactly this result. A medical or audiology check is the next step.",
            )
            a == EffectResult.ABSENT && b == EffectResult.ABSENT -> Verdict(
                "Inconclusive",
                "Both conditions removed the effect. One physical mechanism cannot be blocked by both, " +
                    "so something else changed: the effect may have stopped on its own, or expectation " +
                    "may be driving it. Re-check the controls and repeat.",
            )
            a == EffectResult.ABSENT && b == EffectResult.REDUCED -> Verdict(
                "Ambiguous, leans RF-mediated",
                "The Faraday enclosure removed the effect, but hearing protection also reduced it. " +
                    "A clean result needs one condition to remove it and the other to leave it unchanged. Repeat.",
            )
            a == EffectResult.REDUCED && b == EffectResult.ABSENT -> Verdict(
                "Ambiguous, leans acoustic",
                "Hearing protection removed the effect, but the Faraday enclosure also reduced it " +
                    "(fabric enclosures muffle some sound). Repeat, or try a rigid mesh enclosure.",
            )
            a == EffectResult.REDUCED && b == EffectResult.PRESENT -> Verdict(
                "Ambiguous, weak lean to RF-mediated",
                "The Faraday enclosure only reduced the effect. Partial reductions are the results most " +
                    "easily produced by expectation. Repeat before drawing anything from this.",
            )
            a == EffectResult.PRESENT && b == EffectResult.REDUCED -> Verdict(
                "Ambiguous, weak lean to acoustic",
                "Hearing protection only reduced the effect. Partial reductions are the results most " +
                    "easily produced by expectation. Repeat, with better-sealed protection.",
            )
            else -> Verdict(
                "Inconclusive",
                "Both conditions reduced the effect without removing it. This does not separate the two " +
                    "mechanisms. Re-check the controls and repeat.",
            )
        }

        val washout = if (returnedBetween) "" else
            " Note: the effect had not returned before condition B started, so B's result cannot be " +
                "attributed to the hearing protection. Treat this run as inconclusive."
        return Verdict(base.title, base.detail + washout)
    }
}

data class Regression(val slope: Double, val intercept: Double, val r2: Double, val n: Int)

object RangeAnalysis {

    /** Least-squares fit of y against x. Null when x has no spread or fewer than 2 points. */
    fun linearFit(xs: List<Double>, ys: List<Double>): Regression? {
        val n = xs.size
        if (n < 2 || n != ys.size) return null
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            sxx += (xs[i] - mx) * (xs[i] - mx)
            sxy += (xs[i] - mx) * (ys[i] - my)
            syy += (ys[i] - my) * (ys[i] - my)
        }
        if (sxx < 1e-12) return null
        val slope = sxy / sxx
        val r2 = if (syy < 1e-12) 1.0 else (sxy * sxy) / (sxx * syy)
        return Regression(slope, my - slope * mx, r2, n)
    }

    /**
     * Mic level against distance. A free-field point source falls 20 dB per
     * tenfold distance (6 dB per doubling): the inverse-square law.
     */
    fun levelFalloff(stops: List<RangeStop>, level: (RangeStop) -> Double?, label: String): String? {
        val usable = stops.filter { it.nominalDistanceM > 0 && level(it) != null }
        if (usable.size < 4) return null
        val fit = linearFit(usable.map { log10(it.nominalDistanceM) }, usable.map { level(it)!! })
            ?: return null
        val slope = fit.slope
        val slopeText = Fmt.num(slope, 1)
        val r2Text = Fmt.num(fit.r2, 2)
        return when {
            abs(slope) < 6.0 ->
                "$label mic level is flat with distance ($slopeText dB per tenfold distance). The mic is " +
                    "hearing ambient sound, not a source near the start point."
            slope in -26.0..-14.0 && fit.r2 >= 0.7 ->
                "$label mic level falls $slopeText dB per tenfold distance (fit r² $r2Text), close to the " +
                    "-20 expected of inverse-square spreading from a compact source near the start point."
            slope < 0 ->
                "$label mic level falls $slopeText dB per tenfold distance (fit r² $r2Text). It decreases with " +
                    "distance but does not match inverse-square (-20): reflections, obstacles, a " +
                    "non-compact source or changing background noise."
            else ->
                "$label mic level rises with distance ($slopeText dB per tenfold distance). Whatever the mic " +
                    "hears is not at the start point."
        }
    }

    /** What the observer reported, stop by stop. */
    fun perceptionPattern(stops: List<RangeStop>): String {
        if (stops.size < 3) return "Log at least 3 stops to describe how detection changes with distance."
        val sorted = stops.sortedBy { it.nominalDistanceM }
        val detected = sorted.map { it.humanDetects }
        if (detected.none { it }) {
            return "The effect was not detected at any stop, including the start point."
        }
        val lastYes = detected.lastIndexOf(true)
        val gaps = (0 until lastYes).any { !detected[it] }
        if (gaps) {
            return "Detection comes and goes with distance (detected again beyond a stop where it was " +
                "absent). That is not a simple falloff: check confounds such as reflections, " +
                "obstacles, time-varying sources or expectation."
        }
        if (lastYes == sorted.size - 1) {
            val first = sorted.first().intensity
            val last = sorted.last().intensity
            return if (last >= first) {
                "The effect was detected at every stop with no decrease out to " +
                    "${fmtMeters(sorted.last().nominalDistanceM)}. It does not depend on distance from " +
                    "this start point: the source is elsewhere, is very distant, or the cause is not an " +
                    "external emission. Walk further or pick a different start point."
            } else {
                "The effect weakens with distance but was still detected at the last stop " +
                    "(${fmtMeters(sorted.last().nominalDistanceM)}). Walk further to find where it ends."
            }
        }
        // Detected up to lastYes, absent afterwards.
        val edge = sorted[lastYes]
        val peak = sorted.take(lastYes + 1).maxOf { it.intensity }
        val steadilyFalling = lastYes >= 2 &&
            (1..lastYes).all { sorted[it].intensity <= sorted[it - 1].intensity } &&
            edge.intensity < sorted.first().intensity
        return when {
            edge.intensity >= 3 || (peak > 0 && edge.intensity >= peak) ->
                "Abrupt cutoff: reported intensity was still ${edge.intensity}/5 at " +
                    "${fmtMeters(edge.nominalDistanceM)} and the effect was absent at the next stop. " +
                    "Radiated energy fades gradually, so a sharp edge suggests a perception threshold, " +
                    "an obstacle or beam edge, or a confound. Repeat the walk in another direction."
            steadilyFalling ->
                "Gradual falloff: reported intensity declined steadily and the effect was last detected at " +
                    "${fmtMeters(edge.nominalDistanceM)}. That shape is what a radiating source near the " +
                    "start point would produce, but perceived intensity is not a measurement: it cannot " +
                    "by itself confirm inverse-square behaviour."
            else ->
                "The effect was last detected at ${fmtMeters(edge.nominalDistanceM)}. Reported intensity " +
                    "did not change steadily, so the shape of the falloff is unclear."
        }
    }

    /** Full assessment shown on screen and stored with the transect. */
    fun assess(stops: List<RangeStop>): String {
        val parts = mutableListOf(perceptionPattern(stops))
        levelFalloff(stops, { it.hfBandDb }, "18-24 kHz")?.let { parts += it }
        levelFalloff(stops, { it.broadbandDb }, "Broadband")?.let { parts += it }
        if (stops.none { it.hfBandDb != null }) {
            parts += "No mic levels were sampled, so there is no objective check of inverse-square falloff: " +
                "yes/no detection alone cannot show it."
        }
        return parts.joinToString("\n\n")
    }

    private fun fmtMeters(m: Double): String =
        if (m == Math.rint(m)) "${m.toLong()} m" else "${Fmt.num(m, 1)} m"
}

object Geo {
    private const val EARTH_RADIUS_M = 6371000.0

    /** Great-circle distance in metres. Used only for relative waypoint distances. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

object MagStats {
    /** Smallest deviation ever flagged: below this, phone magnetometer noise and drift dominate. */
    const val MIN_THRESHOLD_UT = 5.0

    fun mean(values: List<Float>): Double = if (values.isEmpty()) 0.0 else values.sumOf { it.toDouble() } / values.size

    fun standardDeviation(values: List<Float>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    /** Deviation from the baseline mean that counts as an anomaly. */
    fun threshold(baselineSd: Double): Double = max(MIN_THRESHOLD_UT, 4.0 * baselineSd)
}

/**
 * Groups consecutive out-of-range samples into anomaly events. Feed it every
 * sample; it returns a finished event when the field returns to baseline.
 */
class AnomalyDetector(private val baselineMean: Double, private val thresholdUt: Double) {
    data class Event(val startMs: Long, val peakDeviationUt: Double, val durationMs: Long)

    private var startMs = -1L
    private var lastMs = 0L
    private var peak = 0.0

    val inAnomaly: Boolean get() = startMs >= 0

    fun feed(timeMs: Long, magnitudeUt: Double): Event? {
        val deviation = magnitudeUt - baselineMean
        if (abs(deviation) > thresholdUt) {
            if (startMs < 0) {
                startMs = timeMs
                peak = deviation
            } else if (abs(deviation) > abs(peak)) {
                peak = deviation
            }
            lastMs = timeMs
            return null
        }
        return finish()
    }

    /** Close an event that is still open (call when the sweep stops). */
    fun finish(): Event? {
        if (startMs < 0) return null
        val event = Event(startMs, peak, lastMs - startMs)
        startMs = -1L
        peak = 0.0
        return event
    }
}
