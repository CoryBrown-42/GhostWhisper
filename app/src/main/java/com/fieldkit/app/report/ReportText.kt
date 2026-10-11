package com.fieldkit.app.report

import com.fieldkit.app.core.Fmt
import com.fieldkit.app.core.Shielding
import com.fieldkit.app.core.Texts
import com.fieldkit.app.data.Session

/**
 * Builds the session report as plain text with a tiny markup the PDF writer
 * understands: "# " title, "## " section heading, "| " table row (monospace).
 * The text export is this string as-is. Pure Kotlin, covered by ReportTextTest.
 */
object ReportText {

    fun build(session: Session, generatedMs: Long): String {
        val out = StringBuilder()
        fun line(text: String = "") { out.append(text).append('\n') }
        fun section(title: String) { line(); line("## $title") }
        fun row(vararg cells: Pair<String, Int>) {
            line("| " + cells.joinToString(" ") { (text, width) -> text.take(width).padEnd(width) }.trimEnd())
        }

        line("# Field Kit session report")
        line("Session: ${session.name}")
        line("Session started: ${Fmt.dateTime(session.createdMs)}")
        line("Report generated: ${Fmt.dateTime(generatedMs)}")
        line(
            "Contents: ${session.peaks.size} acoustic peaks, ${session.snapshots.size} spectrogram snapshots, " +
                "${session.observations.size} observations, ${session.shieldingRuns.size} shielding runs, " +
                "${session.transects.size} transects, ${session.magSweeps.size} magnetometer sweeps"
        )
        if (session.notes.isNotBlank()) {
            section("Session notes")
            line(session.notes.trim())
        }

        section("What this report can and cannot show")
        line(Texts.DISCLAIMER)
        line()
        for ((title, body) in Texts.HARDWARE_LIMITS) line("- $title. $body")

        section("Acoustic Survey: logged peaks")
        line(Texts.MIC_NOTE)
        if (session.peaks.isEmpty()) {
            line("No peaks logged.")
        } else {
            row("Time" to 19, "Frequency" to 11, "Level (rel)" to 12, "Band" to 14)
            for (p in session.peaks) {
                row(
                    Fmt.dateTime(p.timeMs) to 19,
                    Fmt.hz(p.frequencyHz) to 11,
                    Fmt.db(p.relDb) to 12,
                    (if (p.inHfBand) "18-24 kHz" else "below 18 kHz") to 14,
                )
            }
        }
        if (session.snapshots.isNotEmpty()) {
            line()
            line("Spectrogram snapshots (images are included in the PDF version):")
            session.snapshots.forEachIndexed { i, s ->
                line("${i + 1}. ${Fmt.dateTime(s.timeMs)}  ${s.caption}")
            }
        }

        section("Phenomenology Log")
        if (session.observations.isEmpty()) {
            line("No observations logged.")
        } else {
            val baselines = session.observations.count { it.isBaseline }
            line("${session.observations.size} entries, of which $baselines baseline (suspected source absent or off).")
            if (baselines == 0) {
                line("No baseline entry exists. Without one, these entries cannot be compared against anything.")
            }
            for (o in session.observations) {
                line()
                val who = if (o.species.isNotBlank()) "${o.observerType.label} (${o.species})" else o.observerType.label
                line("${Fmt.dateTime(o.timeMs)}  $who${if (o.isBaseline) "  [BASELINE]" else ""}")
                val effects = (o.effects + listOf(o.effectText).filter { it.isNotBlank() }).joinToString(", ")
                line("  Effect: ${effects.ifBlank { "not specified" }}")
                line("  Severity: ${o.severity}/5   Duration: ${o.duration.ifBlank { "not given" }}")
                line(
                    "  Environment: ${o.setting.label}" +
                        (if (o.weather.isNotBlank()) ", weather: ${o.weather}" else "") +
                        (if (o.nearbyEquipment.isNotBlank()) ", nearby equipment: ${o.nearbyEquipment}" else "")
                )
                val separated = when (o.animalsSeparated) {
                    null -> "no animals present"
                    true -> "animals separated from human reactions"
                    false -> "animals NOT separated"
                }
                line(
                    "  Controls: $separated; baseline ${if (o.baselineLogged) "logged" else "NOT logged"}; " +
                        "hearing protection: ${o.hearingProtection.label}"
                )
                if (o.notes.isNotBlank()) line("  Notes: ${o.notes}")
            }
        }

        section("Shielding Test")
        line("A = inside Faraday enclosure (blocks RF, passes sound). B = full hearing protection (blocks sound, passes RF).")
        if (session.shieldingRuns.isEmpty()) {
            line("No runs recorded.")
        } else {
            session.shieldingRuns.forEachIndexed { i, r ->
                line()
                line("Run ${i + 1}, started ${Fmt.dateTime(r.startedMs)}")
                line("  Before shielding: effect ${if (r.baselinePresent) "present" else "absent"}" +
                    (if (r.baselineNotes.isNotBlank()) " (${r.baselineNotes})" else ""))
                line("  A, Faraday enclosure (${Fmt.time(r.faraday.timeMs)}): ${r.faraday.result.label}" +
                    (if (r.faraday.notes.isNotBlank()) " (${r.faraday.notes})" else ""))
                line("  Effect returned between conditions: ${if (r.returnedBetween) "yes" else "no"}")
                line("  B, hearing protection (${Fmt.time(r.hearingProtection.timeMs)}): ${r.hearingProtection.result.label}" +
                    (if (r.hearingProtection.notes.isNotBlank()) " (${r.hearingProtection.notes})" else ""))
                line("  Interpretation: ${r.verdictTitle}")
                line("  ${r.verdictDetail}")
            }
            line()
            line(Shielding.CAVEAT)
        }

        section("Range Map")
        if (session.transects.isEmpty()) {
            line("No transects recorded.")
        } else {
            session.transects.forEachIndexed { i, t ->
                line()
                line("Transect ${i + 1}, started ${Fmt.dateTime(t.startedMs)}, step ${Fmt.meters(t.incrementM)}" +
                    (if (t.startNote.isNotBlank()) ", start point: ${t.startNote}" else ""))
                row("#" to 2, "Paced" to 8, "GPS" to 13, "Human" to 6, "Int" to 3, "Animal" to 11, "HF dB" to 7, "All dB" to 7)
                for (s in t.stops) {
                    val gps = s.gpsDistanceM?.let { d ->
                        Fmt.num(d, 1) + (s.gpsAccuracyM?.let { " ±" + Fmt.num(it, 0) } ?: "") + " m"
                    } ?: "-"
                    row(
                        s.index.toString() to 2,
                        Fmt.meters(s.nominalDistanceM) to 8,
                        gps to 13,
                        (if (s.humanDetects) "yes" else "no") to 6,
                        s.intensity.toString() to 3,
                        when (s.animal) {
                            com.fieldkit.app.data.AnimalReaction.YES -> "reacts"
                            com.fieldkit.app.data.AnimalReaction.NO -> "no reaction"
                            com.fieldkit.app.data.AnimalReaction.NOT_OBSERVED -> "-"
                        } to 11,
                        (s.hfBandDb?.let { Fmt.num(it, 1) } ?: "-") to 7,
                        (s.broadbandDb?.let { Fmt.num(it, 1) } ?: "-") to 7,
                    )
                }
                for (s in t.stops.filter { it.note.isNotBlank() }) line("  Stop ${s.index}: ${s.note}")
                line("Assessment:")
                line(t.assessment)
            }
        }

        section("Magnetometer Sweep")
        line(Texts.MAG_LABEL)
        if (session.magSweeps.isEmpty()) {
            line("No sweeps recorded.")
        } else {
            session.magSweeps.forEachIndexed { i, m ->
                line()
                line("Sweep ${i + 1}, started ${Fmt.dateTime(m.startedMs)}, duration ${Fmt.duration(m.durationMs)}")
                line("  Baseline: ${Fmt.microtesla(m.baselineMeanUt)} (sd ${Fmt.num(m.baselineSdUt, 2)}), " +
                    "anomaly threshold ±${Fmt.microtesla(m.thresholdUt)}")
                line("  Range during sweep: ${Fmt.microtesla(m.minUt)} to ${Fmt.microtesla(m.maxUt)}")
                if (m.note.isNotBlank()) line("  Note: ${m.note}")
                if (m.anomalies.isEmpty()) {
                    line("  No anomalies: no strong magnetic source was passed during this sweep.")
                } else {
                    line("  ${m.anomalies.size} anomalies:")
                    for (a in m.anomalies) {
                        val sign = if (a.peakDeviationUt >= 0) "+" else ""
                        line("    ${Fmt.time(a.timeMs)}  peak $sign${Fmt.microtesla(a.peakDeviationUt)} from baseline, " +
                            "lasting ${Fmt.num(a.durationMs / 1000.0, 1)} s")
                    }
                }
            }
        }

        section("Safety")
        for ((title, body) in Texts.SAFETY) line("- $title. $body")

        return out.toString()
    }
}
