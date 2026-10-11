package com.fieldkit.app

import com.fieldkit.app.core.AnomalyDetector
import com.fieldkit.app.core.Geo
import com.fieldkit.app.core.MagStats
import com.fieldkit.app.core.RangeAnalysis
import com.fieldkit.app.core.Shielding
import com.fieldkit.app.data.AnimalReaction
import com.fieldkit.app.data.EffectResult.ABSENT
import com.fieldkit.app.data.EffectResult.PRESENT
import com.fieldkit.app.data.EffectResult.REDUCED
import com.fieldkit.app.data.RangeStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class AnalysisTest {

    // --- Shielding ---------------------------------------------------------

    @Test
    fun shieldingTruthTable() {
        fun title(a: com.fieldkit.app.data.EffectResult, b: com.fieldkit.app.data.EffectResult) =
            Shielding.interpret(true, a, true, b).title
        assertEquals("Consistent with an RF-mediated effect", title(ABSENT, PRESENT))
        assertEquals("Consistent with an acoustic effect", title(PRESENT, ABSENT))
        assertEquals("Neither simple mechanism", title(PRESENT, PRESENT))
        assertEquals("Inconclusive", title(ABSENT, ABSENT))
        assertEquals("Inconclusive", title(REDUCED, REDUCED))
        assertTrue(title(ABSENT, REDUCED).startsWith("Ambiguous"))
        assertTrue(title(REDUCED, ABSENT).startsWith("Ambiguous"))
        assertTrue(title(REDUCED, PRESENT).startsWith("Ambiguous"))
        assertTrue(title(PRESENT, REDUCED).startsWith("Ambiguous"))
    }

    @Test
    fun shieldingNeedsBaselineAndWashout() {
        assertEquals("Not testable right now", Shielding.interpret(false, ABSENT, true, PRESENT).title)
        val noWashout = Shielding.interpret(true, ABSENT, false, PRESENT)
        assertTrue(noWashout.detail.contains("had not returned"))
        assertTrue(!Shielding.interpret(true, ABSENT, true, PRESENT).detail.contains("had not returned"))
    }

    // --- Range -------------------------------------------------------------

    private fun stop(i: Int, d: Double, detects: Boolean, intensity: Int, hf: Double? = null, all: Double? = null) =
        RangeStop(i, 0L, d, null, null, detects, intensity, AnimalReaction.NOT_OBSERVED, all, hf, "")

    @Test
    fun linearFitRecoversSlope() {
        val fit = RangeAnalysis.linearFit(listOf(0.0, 1.0, 2.0, 3.0), listOf(1.0, 3.0, 5.0, 7.0))!!
        assertEquals(2.0, fit.slope, 1e-9)
        assertEquals(1.0, fit.intercept, 1e-9)
        assertEquals(1.0, fit.r2, 1e-9)
        assertNull(RangeAnalysis.linearFit(listOf(1.0, 1.0), listOf(1.0, 2.0)))
    }

    @Test
    fun inverseSquareLevelsAreRecognised() {
        val stops = (0..6).map { i ->
            val d = i * 5.0
            stop(i, d, true, 3, hf = if (d > 0) -40.0 - 20 * log10(d) else -30.0)
        }
        val text = RangeAnalysis.levelFalloff(stops, { it.hfBandDb }, "18-24 kHz")!!
        assertTrue(text, text.contains("-20.0 dB per tenfold"))
        assertTrue(text, text.contains("inverse-square spreading"))
    }

    @Test
    fun flatAndOddLevelsAreNotCalledInverseSquare() {
        val flat = (0..5).map { stop(it, it * 5.0, true, 3, hf = -70.0 + (it % 2) * 0.5) }
        assertTrue(RangeAnalysis.levelFalloff(flat, { it.hfBandDb }, "X")!!.contains("flat with distance"))
        val steep = (0..5).map { stop(it, it * 5.0, true, 3, hf = if (it > 0) -40.0 - 45 * log10(it * 5.0) else -30.0) }
        assertTrue(RangeAnalysis.levelFalloff(steep, { it.hfBandDb }, "X")!!.contains("does not match inverse-square"))
        val rising = (0..5).map { stop(it, it * 5.0, true, 3, hf = if (it > 0) -80.0 + 20 * log10(it * 5.0) else -80.0) }
        assertTrue(RangeAnalysis.levelFalloff(rising, { it.hfBandDb }, "X")!!.contains("rises with distance"))
        // Too few sampled stops: no claim at all.
        assertNull(RangeAnalysis.levelFalloff(flat.take(4), { it.hfBandDb }, "X"))
        assertNull(RangeAnalysis.levelFalloff((0..5).map { stop(it, it * 5.0, true, 3) }, { it.hfBandDb }, "X"))
    }

    @Test
    fun perceptionPatterns() {
        fun pattern(vararg intensities: Int) =
            RangeAnalysis.perceptionPattern(intensities.mapIndexed { i, v -> stop(i, i * 5.0, v > 0, v) })
        assertTrue(pattern(4, 4, 4, 0, 0).startsWith("Abrupt cutoff"))
        assertTrue(pattern(5, 4, 2, 1, 0, 0).startsWith("Gradual falloff"))
        assertTrue(pattern(0, 0, 0).contains("not detected at any stop"))
        assertTrue(pattern(3, 0, 3, 0).contains("comes and goes"))
        assertTrue(pattern(3, 3, 3, 3).contains("does not depend on distance"))
        assertTrue(pattern(5, 3, 2, 1).contains("still detected at the last stop"))
        assertTrue(pattern(3, 3).contains("at least 3 stops"))
        assertTrue(pattern(2, 1, 2, 1, 0).contains("shape of the falloff is unclear"))
    }

    @Test
    fun assessSaysWhenThereIsNoObjectiveCheck() {
        val text = RangeAnalysis.assess((0..4).map { stop(it, it * 5.0, it < 3, if (it < 3) 3 else 0) })
        assertTrue(text.contains("No mic levels were sampled"))
    }

    // --- Geo ---------------------------------------------------------------

    @Test
    fun geoDistance() {
        assertEquals(0.0, Geo.distanceM(40.0, -74.0, 40.0, -74.0), 1e-6)
        // One thousandth of a degree of latitude is about 111.2 m.
        assertEquals(111.2, Geo.distanceM(40.0, -74.0, 40.001, -74.0), 0.2)
        // Longitude shrinks with cos(latitude).
        assertEquals(111.2 * kotlin.math.cos(Math.toRadians(40.0)), Geo.distanceM(40.0, -74.0, 40.0, -74.001), 0.2)
    }

    // --- Magnetometer ------------------------------------------------------

    @Test
    fun magStatsAndThreshold() {
        val v = listOf(48f, 50f, 52f)
        assertEquals(50.0, MagStats.mean(v), 1e-9)
        assertEquals(2.0, MagStats.standardDeviation(v), 1e-9)
        assertEquals(8.0, MagStats.threshold(2.0), 1e-9)
        assertEquals(5.0, MagStats.threshold(0.3), 1e-9) // never below the noise floor
        assertEquals(0.0, MagStats.standardDeviation(listOf(1f)), 0.0)
    }

    @Test
    fun anomalyDetectorGroupsConsecutiveSamples() {
        val d = AnomalyDetector(50.0, 5.0)
        assertNull(d.feed(0, 51.0))
        assertNull(d.feed(100, 60.0))
        assertNull(d.feed(200, 95.0))
        assertTrue(d.inAnomaly)
        assertNull(d.feed(300, 58.0))
        val e = d.feed(400, 50.5)
        assertNotNull(e)
        assertEquals(100L, e!!.startMs)
        assertEquals(45.0, e.peakDeviationUt, 1e-9)
        assertEquals(200L, e.durationMs)
        assertNull(d.feed(500, 50.0))
        // Negative excursions count too, and an open event is closed by finish().
        assertNull(d.feed(600, 30.0))
        val open = d.finish()!!
        assertEquals(-20.0, open.peakDeviationUt, 1e-9)
        assertNull(d.finish())
    }
}
