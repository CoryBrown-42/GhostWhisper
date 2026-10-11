package com.fieldkit.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale-independent formatting, so reports read the same on every phone. */
object Fmt {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

    fun num(value: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)

    fun dateTime(ms: Long): String = dateTime.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun time(ms: Long): String = timeOnly.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun fileStamp(ms: Long): String = fileStamp.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun hz(hz: Double): String = if (hz >= 1000) "${num(hz / 1000.0, 2)} kHz" else "${num(hz, 0)} Hz"
    fun db(db: Double): String = "${num(db, 1)} dB"
    fun meters(m: Double): String = "${num(m, 1)} m"
    fun microtesla(ut: Double): String = "${num(ut, 1)} µT"

    fun duration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}

/**
 * All fixed wording that states what the app can and cannot do. Kept in one
 * place so the screens and the exported report always say the same thing.
 */
object Texts {

    // One-line "what this can and cannot tell you" captions, one per measurement screen.
    const val CAPTION_ACOUSTIC =
        "Can show: relative sound level by frequency, 20 Hz-24 kHz. Cannot show: absolute loudness, direction, or anything about RF."
    const val CAPTION_LOG =
        "Can show: what was reported, when, under which controls. Cannot show: what caused it."
    const val CAPTION_SHIELDING =
        "Can show: which blocking condition changes the reported effect. Cannot show: proof of mechanism without blinded repeats."
    const val CAPTION_RANGE =
        "Can show: how detection and mic level change with distance. Cannot show: where a source is or what it is."
    const val CAPTION_MAGNET =
        "Can show: strong nearby magnets and currents (motors, speakers, transformers). Cannot show: RF, microwaves or weak fields."
    const val CAPTION_REPORT =
        "Can show: a complete record of what was measured and observed. Cannot show: more than the measurements support."

    const val HF_BAND_LABEL =
        "18-24 kHz: canine-audible band (above typical adult hearing, within dog hearing)"

    const val MIC_NOTE =
        "Phone microphones are uncalibrated and roll off below about 50 Hz and above about 20 kHz. " +
            "All readings are relative (dB re digital full scale), not absolute sound pressure."

    const val MIC_CONTEXT =
        "Energy at 18-24 kHz is common and usually mundane: switching power supplies, LED drivers, " +
            "motion sensors, pest repellers. Silence there is not proof of absence either, because " +
            "many phones filter out everything above 20-22 kHz."

    const val MAG_LABEL =
        "Phone magnetometers are microtesla-class orientation sensors, NOT quantum magnetometers. " +
            "This rules in or out mundane strong sources (motors, speakers, transformers), nothing more."

    const val MATERIALS_NOTE =
        "Faraday fabric or bags and earmuffs are external purchases. The app guides the procedure " +
            "and records results; it does not supply or simulate shielding."

    const val PERMISSION_MIC =
        "Acoustic Survey needs the microphone to compute a live spectrum. Audio is analysed in " +
            "memory and never recorded to a file or sent anywhere; this app has no network access."

    const val PERMISSION_LOCATION =
        "Range Map can use GPS to cross-check the distance between your start point and each stop. " +
            "Only relative distances are stored, never coordinates. No maps, no uploads. " +
            "You can skip this and rely on paced distances."

    val SAFETY: List<Pair<String, String>> = listOf(
        "Do not trespass" to
            "Measure only from places you are allowed to be. Nothing here justifies entering someone else's property.",
        "Do not approach or touch suspected devices" to
            "Observe from a distance. Do not open, move, unplug or interfere with equipment that is not yours.",
        "Keep your distance from high-power RF sources" to
            "Antennas, radar and industrial RF equipment can cause burns and other injury at close range. Obey signage and barriers.",
        "This app documents. It does not protect you" to
            "It cannot shield you, warn you of danger, or detect RF at all.",
        "If you feel ill, stop" to
            "Leave the area and seek medical help instead of continuing the test. Headache, vertigo, nausea and " +
            "internal sounds have many medical causes that deserve a proper assessment whatever these measurements show.",
    )

    val HARDWARE_LIMITS: List<Pair<String, String>> = listOf(
        "No RF or microwave measurement" to
            "Phones cannot do RF/microwave spectrum analysis: there is no accessible RF front end. That phase " +
            "needs external hardware such as a handheld spectrum analyzer with a suitable antenna.",
        "Microphone tops out around 24 kHz" to
            "Sampling at 48 kHz limits analysis to 24 kHz, and most phone microphones and their filters fade " +
            "well before that. Ultrasound above 24 kHz and infrasound below about 20-50 Hz are invisible to this app.",
        "Nothing here is calibrated" to
            "No reading from this app is traceable to a standard. Levels are relative to the phone's own scale and " +
            "differ between phone models. This is not lab equipment.",
        "Magnetometer is an orientation sensor" to MAG_LABEL,
        "GPS is coarse" to
            "Consumer GPS is typically accurate to 3-10 m outdoors and much worse indoors, similar to the default " +
            "5 m transect step. Paced distance is the primary figure; GPS is a cross-check.",
        "Self-reports are not measurements" to
            "Perceived effects are logged faithfully but are subject to expectation and many ordinary causes. " +
            "They become evidence only with the controls the app asks for, and repetition.",
    )

    const val DISCLAIMER =
        "Field Kit is a measurement and documentation aid. It records relative microphone levels, a coarse " +
            "magnetometer trace, paced/GPS distances and human observations. It cannot detect RF or microwave " +
            "emissions, cannot identify a device, and cannot establish the cause of any symptom. Absence of a " +
            "reading is not proof of absence; presence of a reading is not proof of a directed source."
}
