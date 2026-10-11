package com.fieldkit.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.TransectDraft
import com.fieldkit.app.audio.AudioEngine
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.core.Geo
import com.fieldkit.app.core.RangeAnalysis
import com.fieldkit.app.core.Texts
import com.fieldkit.app.data.AnimalReaction
import com.fieldkit.app.data.RangeStop
import com.fieldkit.app.data.Transect
import com.fieldkit.app.sensors.LocationSource
import com.fieldkit.app.ui.ChoiceChips
import com.fieldkit.app.ui.Field
import com.fieldkit.app.ui.KeepScreenOn
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.OnAppStop
import com.fieldkit.app.ui.PermissionExplainer
import com.fieldkit.app.ui.SectionCard
import com.fieldkit.app.ui.YesNoChips
import com.fieldkit.app.ui.hasPermission
import com.fieldkit.app.ui.toast
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun RangeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = vm.transectDraft
    fun edit(change: (TransectDraft) -> TransectDraft) {
        vm.transectDraft = change(vm.transectDraft)
    }

    // --- GPS (optional, requested only when the user switches it on here) ---
    val location = remember { LocationSource(context) }
    val fix by location.fix.collectAsState()
    var locationGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var locationDenied by remember { mutableStateOf(false) }
    var showLocationExplainer by remember { mutableStateOf(false) }
    var gpsOff by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Android may grant only approximate location; relative distances need the precise one.
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        locationDenied = !locationGranted
        if (locationGranted) {
            showLocationExplainer = false
            edit { it.copy(useGps = true) }
        }
    }
    val gpsWanted = draft.useGps && locationGranted
    DisposableEffect(gpsWanted) {
        if (gpsWanted) gpsOff = !location.start()
        onDispose { location.stop() }
    }
    OnAppStop { location.stop() }
    if (draft.active) KeepScreenOn()

    // --- Microphone level per stop (only if already granted in Acoustic Survey) ---
    val micGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)

    // --- Form for the stop being logged ---
    var detects by remember { mutableStateOf<Boolean?>(null) }
    var intensity by remember { mutableIntStateOf(3) }
    var animal by remember { mutableStateOf<AnimalReaction?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val currentFix = fix
    val gpsDistance: Double? =
        if (gpsWanted && currentFix != null && draft.startLat != null && draft.startLon != null) {
            Geo.distanceM(draft.startLat, draft.startLon, currentFix.latitude, currentFix.longitude)
        } else null

    ModeScreen(Texts.CAPTION_RANGE) {
        if (!draft.active) {
            SectionCard("Set up a transect") {
                Text(
                    "Stand at a start point near the suspected source, then walk straight outward, stopping at fixed " +
                        "steps. At each stop you log whether the effect is detectable and whether animals react.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Note("Pick a start point on ground you are allowed to be on. Do not approach or touch a suspected device.")
                OutlinedTextField(
                    value = draft.incrementText,
                    onValueChange = { v -> edit { it.copy(incrementText = v.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5)) } },
                    label = { Text("Step between stops (metres)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Note("Using ${Fmt.meters(draft.incrementM)} steps. Pace it out or use a tape; about 6-7 walking steps is 5 m.")
                Field(draft.startNote, "Start point description (optional)") { v -> edit { it.copy(startNote = v) } }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = draft.useGps && locationGranted,
                        onCheckedChange = { on ->
                            if (!on) edit { it.copy(useGps = false) }
                            else if (locationGranted) edit { it.copy(useGps = true) }
                            else showLocationExplainer = true
                        },
                    )
                    Text("Cross-check distances with GPS", style = MaterialTheme.typography.bodyMedium)
                }
                if (gpsWanted) GpsStatus(gpsOff, currentFix?.accuracy)
                Note(
                    if (micGranted) "Mic level will be sampled for 1 second at each stop (microphone already allowed)."
                    else "No mic levels will be sampled. Allow the microphone in Acoustic Survey to add an objective level at each stop."
                )

                Button(onClick = {
                    edit {
                        it.copy(
                            active = true,
                            startedMs = System.currentTimeMillis(),
                            startLat = if (gpsWanted) currentFix?.latitude else null,
                            startLon = if (gpsWanted) currentFix?.longitude else null,
                            stops = emptyList(),
                        )
                    }
                }) {
                    Text(
                        if (gpsWanted && currentFix == null) "Set start point here (no GPS fix yet: paced only)"
                        else "Set start point here"
                    )
                }
            }
            if (showLocationExplainer && !locationGranted) {
                PermissionExplainer(
                    title = "Location access",
                    explanation = Texts.PERMISSION_LOCATION,
                    denied = locationDenied,
                    onRequest = {
                        locationLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    },
                )
            }
        } else {
            val stopIndex = draft.stops.size
            val nominal = stopIndex * draft.incrementM
            SectionCard("Stop $stopIndex: ${Fmt.meters(nominal)} from the start point") {
                Text(
                    if (stopIndex == 0) "Stay at the start point and log what is observed here."
                    else "Walk outward to ${Fmt.meters(nominal)} from the start point, stop, wait a moment, then log.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (gpsWanted) {
                    GpsStatus(gpsOff, currentFix?.accuracy)
                    if (gpsDistance != null) {
                        Text("GPS distance from start: ${Fmt.meters(gpsDistance)}", style = MaterialTheme.typography.bodyMedium)
                    } else if (draft.startLat == null) {
                        Note("There was no GPS fix when the start point was set, so GPS distances are unavailable for this transect.")
                    }
                }

                Text("Is the effect detectable here (human)?", style = MaterialTheme.typography.labelLarge)
                YesNoChips(detects, yes = "Detectable", no = "Not detectable") { detects = it }
                if (detects == true) {
                    Text("Perceived intensity: $intensity of 5", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = intensity.toFloat(),
                        onValueChange = { intensity = it.roundToInt().coerceIn(1, 5) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                }
                Text("Animals", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(AnimalReaction.entries.map { it to it.label }, animal) { animal = it }
                Field(note, "Note for this stop (optional)") { note = it }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = detects != null && animal != null && !busy,
                        onClick = {
                            val humanDetects = detects == true
                            val reaction = animal ?: AnimalReaction.NOT_OBSERVED
                            val stopNote = note.trim()
                            val stopIntensity = if (humanDetects) intensity else 0
                            val accuracy = if (gpsDistance != null) currentFix?.accuracy?.toDouble() else null
                            busy = true
                            scope.launch {
                                // One second of audio, averaged. Hold the phone still and stay quiet.
                                val levels = if (micGranted) AudioEngine.measureLevels(context.applicationContext) else null
                                val stop = RangeStop(
                                    index = stopIndex,
                                    timeMs = System.currentTimeMillis(),
                                    nominalDistanceM = nominal,
                                    gpsDistanceM = gpsDistance,
                                    gpsAccuracyM = accuracy,
                                    humanDetects = humanDetects,
                                    intensity = stopIntensity,
                                    animal = reaction,
                                    broadbandDb = levels?.broadbandDb,
                                    hfBandDb = levels?.hfBandDb,
                                    note = stopNote,
                                )
                                edit { it.copy(stops = it.stops + stop) }
                                detects = null
                                animal = null
                                note = ""
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Sampling..." else "Log this stop") }
                    if (draft.stops.isNotEmpty()) {
                        OutlinedButton(enabled = !busy, onClick = { edit { it.copy(stops = it.stops.dropLast(1)) } }) {
                            Text("Undo last")
                        }
                    }
                }
                if (micGranted) Note("Logging samples the mic for 1 second: hold the phone still and stay quiet.")
            }

            if (draft.stops.isNotEmpty()) {
                SectionCard("Distance vs detection") {
                    StopsTable(draft.stops)
                    HorizontalDivider()
                    Text("Assessment so far", style = MaterialTheme.typography.labelLarge)
                    Text(RangeAnalysis.assess(draft.stops), style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = draft.stops.isNotEmpty() && !busy,
                    onClick = {
                        val transect = Transect(
                            startedMs = draft.startedMs,
                            incrementM = draft.incrementM,
                            startNote = draft.startNote.trim(),
                            stops = draft.stops,
                            assessment = RangeAnalysis.assess(draft.stops),
                        )
                        vm.update { it.copy(transects = it.transects + transect) }
                        edit { TransectDraft(incrementText = it.incrementText, useGps = it.useGps) }
                        toast(context, "Transect saved to the session")
                    },
                ) { Text("Finish and save") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { edit { TransectDraft(incrementText = it.incrementText, useGps = it.useGps) } },
                ) { Text("Discard") }
            }
        }

        val transects = vm.session.transects
        SectionCard("Saved transects (${transects.size})") {
            if (transects.isEmpty()) Note("None yet. Walking the same line twice, and a second line in another direction, makes the result far more convincing.")
            for (index in transects.indices.reversed()) {
                val t = transects[index]
                if (index != transects.lastIndex) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${Fmt.dateTime(t.startedMs)}   ${t.stops.size} stops, ${Fmt.meters(t.incrementM)} steps",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (t.startNote.isNotBlank()) Note("Start: ${t.startNote}")
                    }
                    IconButton(onClick = {
                        vm.update { s -> s.copy(transects = s.transects.filterIndexed { i, _ -> i != index }) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Delete transect") }
                }
                StopsTable(t.stops)
                Text(t.assessment, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GpsStatus(gpsOff: Boolean, accuracyM: Float?) {
    Note(
        when {
            gpsOff -> "GPS is switched off or unavailable on this phone. Turn on Location in system settings, or rely on paced distances."
            accuracyM == null -> "Waiting for a GPS fix (needs open sky)..."
            else -> "GPS fix, accuracy about ±${Fmt.num(accuracyM.toDouble(), 0)} m. " +
                "That is comparable to one step, so treat GPS as a cross-check on paced distance."
        }
    )
}

private val TABLE_WEIGHTS = listOf(0.5f, 1.1f, 1.1f, 0.9f, 0.6f, 1.2f, 1.0f)

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(Modifier.fillMaxWidth()) {
        for (i in cells.indices) {
            Text(
                cells[i],
                style = style,
                color = if (header) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(TABLE_WEIGHTS[i]),
            )
        }
    }
}

/** Distance-vs-detection table. Monospace so the columns line up. */
@Composable
private fun StopsTable(stops: List<RangeStop>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TableRow(listOf("#", "paced", "GPS", "human", "int", "animal", "HF dB"), header = true)
        for (s in stops) {
            TableRow(
                listOf(
                    s.index.toString(),
                    "${Fmt.num(s.nominalDistanceM, 1)}m",
                    s.gpsDistanceM?.let { "${Fmt.num(it, 1)}m" } ?: "-",
                    if (s.humanDetects) "yes" else "no",
                    s.intensity.toString(),
                    when (s.animal) {
                        AnimalReaction.YES -> "reacts"
                        AnimalReaction.NO -> "none"
                        AnimalReaction.NOT_OBSERVED -> "-"
                    },
                    s.hfBandDb?.let { Fmt.num(it, 1) } ?: "-",
                )
            )
        }
    }
}
