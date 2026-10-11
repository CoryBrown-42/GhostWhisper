package com.fieldkit.app

import com.fieldkit.app.core.Texts
import com.fieldkit.app.data.AnimalReaction
import com.fieldkit.app.data.ConditionResult
import com.fieldkit.app.data.EffectResult
import com.fieldkit.app.data.HearingProtection
import com.fieldkit.app.data.MagAnomaly
import com.fieldkit.app.data.MagSweep
import com.fieldkit.app.data.Observation
import com.fieldkit.app.data.ObserverType
import com.fieldkit.app.data.PeakEvent
import com.fieldkit.app.data.RangeStop
import com.fieldkit.app.data.Session
import com.fieldkit.app.data.Setting
import com.fieldkit.app.data.ShieldingRun
import com.fieldkit.app.data.Snapshot
import com.fieldkit.app.data.Transect
import com.fieldkit.app.report.ReportText
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTextTest {
    @Test
    fun emptySessionStillCarriesLimitsAndSafety() {
        val text = ReportText.build(Session("s1", "Empty", 0L), 1000L)
        assertTrue(text.startsWith("# Field Kit session report"))
        assertTrue(text.contains(Texts.DISCLAIMER))
        assertTrue(text.contains("No RF or microwave measurement"))
        assertTrue(text.contains("NOT quantum magnetometers"))
        assertTrue(text.contains("No peaks logged."))
        assertTrue(text.contains("If you feel ill, stop"))
    }

    @Test
    fun fullSessionListsEverything() {
        val session = Session(
            id = "s2", name = "Back yard", createdMs = 0L, notes = "Evening test",
            peaks = listOf(PeakEvent(1000, 19250.0, -62.4, true), PeakEvent(2000, 440.0, -30.0, false)),
            snapshots = listOf(Snapshot(3000, "snap.png", "Spectrogram, linear scale")),
            observations = listOf(
                Observation(4000, ObserverType.ANIMAL, "dog", listOf("animal distress"), "whining", 3, "2 min",
                    Setting.OUTDOOR, "clear", "heat pump", "", false, true, true, HearingProtection.NONE),
            ),
            shieldingRuns = listOf(
                ShieldingRun(5000, true, "", ConditionResult(5100, EffectResult.PRESENT, ""), true,
                    ConditionResult(5200, EffectResult.ABSENT, "muffs"), "Consistent with an acoustic effect", "detail"),
            ),
            transects = listOf(
                Transect(6000, 5.0, "fence corner", listOf(
                    RangeStop(0, 6100, 0.0, 0.0, 4.0, true, 4, AnimalReaction.YES, -40.0, -55.5, ""),
                    RangeStop(1, 6200, 5.0, 6.3, 5.0, false, 0, AnimalReaction.NO, null, null, "wind"),
                ), "assessment text"),
            ),
            magSweeps = listOf(
                MagSweep(7000, 65000, 48.2, 0.4, 5.0, 47.0, 120.5, listOf(MagAnomaly(7500, 72.3, 1500)),
                    listOf(48f, 49f), 100, "by the shed"),
            ),
        )
        val text = ReportText.build(session, 9000L)
        for (expected in listOf(
            "Session: Back yard", "Evening test", "19.25 kHz", "-62.4 dB", "18-24 kHz", "440 Hz",
            "Spectrogram, linear scale", "Animal (dog)", "animal distress, whining", "Severity: 3/5",
            "No baseline entry exists", "animals separated from human reactions",
            "Consistent with an acoustic effect", "(muffs)", "start point: fence corner",
            "6.3 ±5 m", "Stop 1: wind", "assessment text", "duration 1m 5s", "48.2 µT",
            "peak +72.3 µT from baseline, lasting 1.5 s", "by the shed",
        )) assertTrue("missing: $expected\n$text", text.contains(expected))
        // Table rows are marked for the PDF writer and aligned.
        val rows = text.lines().filter { it.startsWith("| ") }
        assertTrue(rows.size >= 6)
    }
}
