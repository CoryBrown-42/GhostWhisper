package com.fieldkit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.ObservationDraft
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.core.Texts
import com.fieldkit.app.data.HearingProtection
import com.fieldkit.app.data.Observation
import com.fieldkit.app.data.ObserverType
import com.fieldkit.app.data.Setting
import com.fieldkit.app.ui.CheckRow
import com.fieldkit.app.ui.ChoiceChips
import com.fieldkit.app.ui.Field
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.SectionCard
import com.fieldkit.app.ui.toast
import kotlin.math.roundToInt

private const val EFFECT_NONE = "none"
private val EFFECT_PRESETS = listOf(
    "headache", "nausea", "vertigo", "perceived internal sound", "anxiety", "animal distress", EFFECT_NONE,
)

@Composable
fun PhenomenologyScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val draft = vm.observationDraft
    fun edit(change: (ObservationDraft) -> ObservationDraft) {
        vm.observationDraft = change(vm.observationDraft)
    }

    val observations = vm.session.observations
    val sessionHasBaseline = observations.any { it.isBaseline }

    // Control checklist: all three must be satisfied before an entry can be logged.
    val controlA = draft.noAnimalsPresent || draft.animalsSeparated
    val controlB = draft.isBaseline || draft.baselineTicked
    val controlC = draft.protection != null
    val controlsDone = controlA && controlB && controlC
    val animalNeedsSpecies = draft.observerType == ObserverType.ANIMAL && draft.species.isBlank()
    val hasEffect = draft.effects.isNotEmpty() || draft.effectText.isNotBlank()

    ModeScreen(Texts.CAPTION_LOG) {
        SectionCard("Control checklist (required before logging)") {
            Text("(a) Behavioural contagion", style = MaterialTheme.typography.labelLarge)
            CheckRow(
                checked = draft.animalsSeparated,
                text = "Animal observers are separated so they cannot see human reactions",
                enabled = !draft.noAnimalsPresent,
            ) { v -> edit { it.copy(animalsSeparated = v) } }
            CheckRow(
                checked = draft.noAnimalsPresent,
                text = "Not applicable: no animals present",
            ) { v -> edit { it.copy(noAnimalsPresent = v, animalsSeparated = if (v) false else it.animalsSeparated) } }

            Text("(b) Baseline", style = MaterialTheme.typography.labelLarge)
            CheckRow(
                checked = draft.baselineTicked,
                text = "A baseline was logged with the suspected source absent or powered down",
                enabled = !draft.isBaseline,
            ) { v -> edit { it.copy(baselineTicked = v) } }
            CheckRow(
                checked = draft.isBaseline,
                text = "This entry IS the baseline (source absent or off right now)",
            ) { v -> edit { it.copy(isBaseline = v) } }
            Note(
                if (sessionHasBaseline) "This session has ${observations.count { it.isBaseline }} baseline entries."
                else "This session has no baseline entry yet. Without one, later entries have nothing to be compared against."
            )

            Text("(c) Hearing protection status", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(HearingProtection.entries.map { it to it.label }, draft.protection) { v ->
                edit { it.copy(protection = v) }
            }
        }

        SectionCard("New observation") {
            Text("Observer", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(ObserverType.entries.map { it to it.label }, draft.observerType) { v ->
                edit { it.copy(observerType = v) }
            }
            if (draft.observerType == ObserverType.ANIMAL) {
                Field(draft.species, "Species (e.g. dog, cat)") { v -> edit { it.copy(species = v) } }
            }

            Text("Effect", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in EFFECT_PRESETS) {
                    FilterChip(
                        selected = preset in draft.effects,
                        onClick = {
                            edit { d ->
                                val next = when {
                                    preset in d.effects -> d.effects - preset
                                    preset == EFFECT_NONE -> setOf(EFFECT_NONE) // "none" excludes the rest
                                    else -> d.effects - EFFECT_NONE + preset
                                }
                                d.copy(effects = next)
                            }
                        },
                        label = { Text(preset) },
                    )
                }
            }
            Field(draft.effectText, "Effect in your own words", singleLine = false) { v -> edit { it.copy(effectText = v) } }

            Text("Severity: ${draft.severity} of 5", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = draft.severity.toFloat(),
                onValueChange = { v -> edit { it.copy(severity = v.roundToInt().coerceIn(1, 5)) } },
                valueRange = 1f..5f,
                steps = 3,
            )
            Field(draft.duration, "Duration (e.g. 10 min, ongoing)") { v -> edit { it.copy(duration = v) } }

            Text("Environment", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Setting.entries.map { it to it.label }, draft.setting) { v -> edit { it.copy(setting = v) } }
            Field(draft.weather, "Weather") { v -> edit { it.copy(weather = v) } }
            Field(draft.equipment, "Nearby equipment (HVAC, appliances, chargers...)") { v -> edit { it.copy(equipment = v) } }
            Field(draft.notes, "Notes", singleLine = false) { v -> edit { it.copy(notes = v) } }

            Button(
                enabled = controlsDone && hasEffect && !animalNeedsSpecies,
                onClick = {
                    val entry = Observation(
                        timeMs = System.currentTimeMillis(),
                        observerType = draft.observerType,
                        species = if (draft.observerType == ObserverType.ANIMAL) draft.species.trim() else "",
                        effects = EFFECT_PRESETS.filter { it in draft.effects },
                        effectText = draft.effectText.trim(),
                        severity = draft.severity,
                        duration = draft.duration.trim(),
                        setting = draft.setting,
                        weather = draft.weather.trim(),
                        nearbyEquipment = draft.equipment.trim(),
                        notes = draft.notes.trim(),
                        isBaseline = draft.isBaseline,
                        animalsSeparated = if (draft.noAnimalsPresent) null else draft.animalsSeparated,
                        baselineLogged = draft.isBaseline || draft.baselineTicked,
                        hearingProtection = draft.protection ?: HearingProtection.NONE,
                    )
                    vm.update { it.copy(observations = it.observations + entry) }
                    // Keep the checklist and environment for the next entry; clear what was observed.
                    edit {
                        it.copy(
                            effects = emptySet(), effectText = "", severity = 1, duration = "", notes = "",
                            isBaseline = false, baselineTicked = it.baselineTicked || it.isBaseline,
                        )
                    }
                    toast(context, "Observation logged")
                },
            ) { Text("Log observation (timestamped now)") }

            if (!controlsDone) {
                val missing = listOfNotNull(
                    "(a) animal separation".takeIf { !controlA },
                    "(b) baseline".takeIf { !controlB },
                    "(c) hearing protection".takeIf { !controlC },
                )
                Note("Complete the control checklist first: ${missing.joinToString(", ")}.")
            } else if (!hasEffect) {
                Note("Choose an effect (or \"none\") or describe it.")
            } else if (animalNeedsSpecies) {
                Note("Enter the species for an animal observer.")
            }
            Note("If symptoms are severe or keep returning, stop and see a doctor. A log is not a diagnosis.")
        }

        SectionCard("Timeline (${observations.size})") {
            if (observations.isEmpty()) Note("No entries in this session yet.")
            for (index in observations.indices.reversed()) {
                val o = observations[index]
                if (index != observations.lastIndex) HorizontalDivider()
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val who = if (o.species.isNotBlank()) "${o.observerType.label} (${o.species})" else o.observerType.label
                        Text(
                            "${Fmt.dateTime(o.timeMs)}   $who" + if (o.isBaseline) "   BASELINE" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (o.isBaseline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        )
                        val effects = (o.effects + listOf(o.effectText).filter { it.isNotBlank() }).joinToString(", ")
                        Text(effects.ifBlank { "not specified" }, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Severity ${o.severity}/5" +
                                (if (o.duration.isNotBlank()) "   ${o.duration}" else "") +
                                "   ${o.setting.label}   protection: ${o.hearingProtection.label}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val env = listOf(o.weather, o.nearbyEquipment, o.notes).filter { it.isNotBlank() }.joinToString("   ")
                        if (env.isNotBlank()) Note(env)
                    }
                    IconButton(onClick = {
                        vm.update { s -> s.copy(observations = s.observations.filterIndexed { i, _ -> i != index }) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Delete entry") }
                }
            }
        }
    }
}
