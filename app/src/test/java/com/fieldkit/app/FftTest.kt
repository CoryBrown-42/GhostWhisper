package com.fieldkit.app

import com.fieldkit.app.core.Fft
import com.fieldkit.app.core.Spectrum
import com.fieldkit.app.core.SpectrumAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {
    private fun sine(n: Int, hz: Double, amplitude: Double, rate: Int = 48000) =
        ShortArray(n) { (amplitude * 32767 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    @Test
    fun impulseHasFlatSpectrum() {
        val re = FloatArray(16).also { it[0] = 1f }
        val im = FloatArray(16)
        Fft(16).forward(re, im)
        for (k in 0 until 16) {
            assertEquals(1f, re[k], 1e-5f)
            assertEquals(0f, im[k], 1e-5f)
        }
    }

    @Test
    fun matchesDirectDft() {
        val n = 64
        val input = FloatArray(n) { (sin(0.3 * it) + 0.5 * sin(1.7 * it + 1)).toFloat() }
        val re = input.copyOf()
        val im = FloatArray(n)
        Fft(n).forward(re, im)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                sr += input[t] * kotlin.math.cos(2 * PI * k * t / n)
                si -= input[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(sr, re[k].toDouble(), 1e-3)
            assertEquals(si, im[k].toDouble(), 1e-3)
        }
    }

    @Test
    fun fullScaleSineReadsZeroDbAtItsFrequency() {
        val a = SpectrumAnalyzer(4096, 48000)
        val db = FloatArray(a.binCount)
        val exactBin = 1024 * a.binHz // 12 kHz, exactly on a bin
        a.analyze(sine(4096, exactBin, 1.0), db)
        val p = Spectrum.peak(db, a.binHz, 20.0, 24000.0)
        assertEquals(12000.0, p.frequencyHz, 1.0)
        assertEquals(0.0, p.db, 0.1)
    }

    @Test
    fun findsOffBinToneInHfBand() {
        val a = SpectrumAnalyzer(4096, 48000)
        val db = FloatArray(a.binCount)
        a.analyze(sine(4096, 19003.0, 0.5), db)
        val p = Spectrum.peak(db, a.binHz, Spectrum.HF_LO_HZ, Spectrum.HF_HI_HZ)
        assertEquals(19003.0, p.frequencyHz, 3.0)
        assertEquals(-6.0, p.db, 1.0) // half scale = -6 dB, small interpolation error
        // Band power of a single tone equals the tone's level; the low band is far quieter.
        assertEquals(-6.0, Spectrum.bandPowerDb(db, a.binHz, 18000.0, 24000.0), 0.5)
        assertTrue(Spectrum.bandPowerDb(db, a.binHz, 20.0, 10000.0) < -60)
    }

    @Test
    fun silenceIsAtTheFloor() {
        val a = SpectrumAnalyzer(4096, 48000)
        val db = FloatArray(a.binCount)
        a.analyze(ShortArray(4096), db)
        assertTrue(db.all { it == Spectrum.FLOOR_DB })
        // 512 bins at the -140 dB floor still sum to a very low band level.
        assertTrue(Spectrum.bandPowerDb(db, a.binHz, 18000.0, 24000.0) < -110)
    }

    @Test
    fun colourRampEndsAreBlackAndBright() {
        assertEquals(0xFF000000.toInt(), Spectrum.colorFor(-200f, -110f, -10f))
        val top = Spectrum.colorFor(0f, -110f, -10f)
        assertEquals(255, (top shr 16) and 0xFF)
    }
}
