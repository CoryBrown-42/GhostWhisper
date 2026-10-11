package com.fieldkit.app.data

/*
 * Everything a session can contain. Plain immutable data classes: screens
 * replace the session with a modified copy, and the store writes it to disk.
 * No Android imports here, so this file is unit-testable on a desktop JVM.
 */

enum class ObserverType(val label: String) { HUMAN("Human"), ANIMAL("Animal") }

enum class Setting(val label: String) { INDOOR("Indoor"), OUTDOOR("Outdoor") }

enum class HearingProtection(val label: String) {
    NONE("None worn"), EARPLUGS("Earplugs"), EARMUFFS("Earmuffs"), BOTH("Plugs + muffs")
}

enum class EffectResult(val label: String) {
    PRESENT("Still present"), REDUCED("Reduced"), ABSENT("Absent")
}

enum class AnimalReaction(val label: String) {
    YES("Reacts"), NO("No reaction"), NOT_OBSERVED("No animal / not observed")
}

/** Acoustic Survey: a peak the user chose to log. dB is relative to digital full scale. */
data class PeakEvent(
    val timeMs: Long,
    val frequencyHz: Double,
    val relDb: Double,
    val inHfBand: Boolean,
)

/** Acoustic Survey: a saved spectrogram image (PNG in the session folder). */
data class Snapshot(
    val timeMs: Long,
    val fileName: String,
    val caption: String,
)

/** Phenomenology Log entry. */
data class Observation(
    val timeMs: Long,
    val observerType: ObserverType,
    val species: String,
    val effects: List<String>,
    val effectText: String,
    val severity: Int,
    val duration: String,
    val setting: Setting,
    val weather: String,
    val nearbyEquipment: String,
    val notes: String,
    /** True when logged with the suspected source absent or powered down. */
    val isBaseline: Boolean,
    /** Control (a): animals could not see human reactions. Null = no animals present. */
    val animalsSeparated: Boolean?,
    /** Control (b): a baseline exists for comparison. */
    val baselineLogged: Boolean,
    /** Control (c). */
    val hearingProtection: HearingProtection,
)

/** Shielding Test: outcome of one condition. */
data class ConditionResult(
    val timeMs: Long,
    val result: EffectResult,
    val notes: String,
)

/** Shielding Test: one complete run of the two-condition protocol. */
data class ShieldingRun(
    val startedMs: Long,
    val baselinePresent: Boolean,
    val baselineNotes: String,
    /** Condition A: observer inside a Faraday enclosure (blocks RF, passes sound). */
    val faraday: ConditionResult,
    /** Did the effect come back after leaving the enclosure, before condition B? */
    val returnedBetween: Boolean,
    /** Condition B: observer wearing full hearing protection (blocks sound, passes RF). */
    val hearingProtection: ConditionResult,
    val verdictTitle: String,
    val verdictDetail: String,
)

/** Range Map: one stop on the transect. */
data class RangeStop(
    val index: Int,
    val timeMs: Long,
    /** Distance the user was asked to walk to (index x increment). */
    val nominalDistanceM: Double,
    /** Straight-line GPS distance from the start point, if GPS was on. */
    val gpsDistanceM: Double?,
    val gpsAccuracyM: Double?,
    val humanDetects: Boolean,
    /** Perceived intensity 0-5 (0 when not detected). */
    val intensity: Int,
    val animal: AnimalReaction,
    /** 1-second average mic levels at this stop (dB re full scale), if the mic was allowed. */
    val broadbandDb: Double?,
    val hfBandDb: Double?,
    val note: String,
)

data class Transect(
    val startedMs: Long,
    val incrementM: Double,
    val startNote: String,
    val stops: List<RangeStop>,
    val assessment: String,
)

data class MagAnomaly(
    val timeMs: Long,
    val peakDeviationUt: Double,
    val durationMs: Long,
)

/** Magnetometer Sweep: one recording against a locally captured baseline. */
data class MagSweep(
    val startedMs: Long,
    val durationMs: Long,
    val baselineMeanUt: Double,
    val baselineSdUt: Double,
    val thresholdUt: Double,
    val minUt: Double,
    val maxUt: Double,
    val anomalies: List<MagAnomaly>,
    /** Field magnitude in microtesla, decimated to [traceIntervalMs]. */
    val trace: List<Float>,
    val traceIntervalMs: Long,
    val note: String,
)

data class Session(
    val id: String,
    val name: String,
    val createdMs: Long,
    val notes: String = "",
    val peaks: List<PeakEvent> = emptyList(),
    val snapshots: List<Snapshot> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val shieldingRuns: List<ShieldingRun> = emptyList(),
    val transects: List<Transect> = emptyList(),
    val magSweeps: List<MagSweep> = emptyList(),
) {
    val entryCount: Int
        get() = peaks.size + snapshots.size + observations.size + shieldingRuns.size +
            transects.size + magSweeps.size
}

/** Lightweight row for the session picker. */
data class SessionInfo(val id: String, val name: String, val createdMs: Long, val entryCount: Int)
