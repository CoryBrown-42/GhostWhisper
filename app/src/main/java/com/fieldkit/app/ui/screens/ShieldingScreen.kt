package com.fieldkit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.ShieldingDraft
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.core.Shielding
import com.fieldkit.app.core.Texts
import com.fieldkit.app.data.ConditionResult
import com.fieldkit.app.data.EffectResult
import com.fieldkit.app.data.ShieldingRun
import com.fieldkit.app.ui.ChoiceChips
import com.fieldkit.app.ui.Field
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.SectionCard
import com.fieldkit.app.ui.YesNoChips
import com.fieldkit.app.ui.toast

private const val LAST_STEP = 5
private val RESULT_OPTIONS = EffectResult.entries.map { it to it.label }

/**
 * Guided two-condition test. Steps:
 * 0 introduction, 1 baseline, 2 condition A (Faraday), 3 washout,
 * 4 condition B (hearing protection), 5 interpretation.
 */
@Composable
fun ShieldingScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val draft = vm.shieldingDraft
    fun edit(change: (ShieldingDraft) -> ShieldingDraft) {
        vm.shieldingDraft = change(vm.shieldingDraft)
    }

    ModeScreen(Texts.CAPTION_SHIELDING) {
        if (draft.step > 0) {
            val progress = draft.step / LAST_STEP.toFloat()
            Text("Step ${draft.step} of $LAST_STEP", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }

        when (draft.step) {
            0 -> {
                SectionCard("What this test does") {
                    Text(
                        "It separates an RF-mediated effect (for example the microwave auditory effect, perceived " +
                            "as sound inside the head) from an acoustic one by blocking each path in turn.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Condition A: observer inside a Faraday enclosure. Blocks RF, passes sound.", style = MaterialTheme.typography.bodyMedium)
                    Text("Condition B: observer wearing full hearing protection. Blocks sound, passes RF.", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text("How results are read", style = MaterialTheme.typography.labelLarge)
                    Note("A removes it, B does not: consistent with RF-mediated.")
                    Note("B removes it, A does not: consistent with acoustic.")
                    Note("Neither removes it: neither simple mechanism. Check confounds.")
                    Note("Both remove it: inconclusive. Re-check controls.")
                }
                SectionCard("Materials") {
                    Note(Texts.MATERIALS_NOTE)
                    Note(
                        "The enclosure must close on every side with no gaps (a tent or bag of conductive fabric, or a " +
                            "metal mesh cage). Hearing protection means well-fitted earplugs plus earmuffs."
                    )
                }
                SectionCard("Before you start") {
                    Note(Shielding.CAVEAT)
                    Button(onClick = { edit { ShieldingDraft(step = 1, startedMs = System.currentTimeMillis()) } }) {
                        Text("Begin a run")
                    }
                }
            }

            1 -> SectionCard("Baseline, no shielding") {
                Text(
                    "With no enclosure and no hearing protection: is the effect being perceived right now?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                YesNoChips(draft.baselinePresent, yes = "Yes, present", no = "No, absent") { v -> edit { it.copy(baselinePresent = v) } }
                Field(draft.baselineNotes, "Describe it (what, how strong)", singleLine = false) { v -> edit { it.copy(baselineNotes = v) } }
                if (draft.baselinePresent == false) {
                    Note(
                        "The test only works while the effect is present: shielding cannot be shown to remove something " +
                            "that is not there. Log this moment in the Phenomenology Log if useful, and start again later."
                    )
                }
                StepButtons(
                    nextEnabled = draft.baselinePresent == true,
                    onNext = { edit { it.copy(step = 2) } },
                    onCancel = { edit { ShieldingDraft() } },
                )
            }

            2 -> SectionCard("Condition A: Faraday enclosure") {
                Text(
                    "1. The observer goes fully inside the enclosure and closes it on all sides.\n" +
                        "2. Ears stay uncovered: sound must still get in.\n" +
                        "3. Wait at least 2 minutes.\n" +
                        "4. Record what the observer perceives while still inside.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Note("Check the enclosure: a phone placed inside should lose all signal. If it still rings, the enclosure leaks.")
                Text("Inside the enclosure, the effect is:", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(RESULT_OPTIONS, draft.faradayResult) { v ->
                    edit { it.copy(faradayResult = v, faradayTimeMs = System.currentTimeMillis()) }
                }
                Field(draft.faradayNotes, "Notes", singleLine = false) { v -> edit { it.copy(faradayNotes = v) } }
                StepButtons(
                    nextEnabled = draft.faradayResult != null,
                    onNext = { edit { it.copy(step = 3) } },
                    onCancel = { edit { ShieldingDraft() } },
                )
            }

            3 -> SectionCard("Between conditions") {
                Text(
                    "The observer leaves the enclosure. Wait a few minutes with no shielding at all.\n\n" +
                        "Has the effect returned to how it was at baseline?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                YesNoChips(draft.returned, yes = "Yes, returned", no = "No, still changed") { v -> edit { it.copy(returned = v) } }
                Note(
                    "This matters: if the effect has not come back, whatever happens under condition B cannot be " +
                        "credited to the hearing protection. You can wait longer before answering."
                )
                StepButtons(
                    nextEnabled = draft.returned != null,
                    onNext = { edit { it.copy(step = 4) } },
                    onCancel = { edit { ShieldingDraft() } },
                )
            }

            4 -> SectionCard("Condition B: full hearing protection") {
                Text(
                    "1. The observer stays outside the enclosure, in the same spot as the baseline.\n" +
                        "2. Fit earplugs properly, then earmuffs over them.\n" +
                        "3. Wait at least 2 minutes.\n" +
                        "4. Record what the observer perceives with protection still on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("With hearing protection on, the effect is:", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(RESULT_OPTIONS, draft.protectionResult) { v ->
                    edit { it.copy(protectionResult = v, protectionTimeMs = System.currentTimeMillis()) }
                }
                Field(draft.protectionNotes, "Notes", singleLine = false) { v -> edit { it.copy(protectionNotes = v) } }
                StepButtons(
                    nextEnabled = draft.protectionResult != null,
                    nextLabel = "Show interpretation",
                    onNext = { edit { it.copy(step = 5) } },
                    onCancel = { edit { ShieldingDraft() } },
                )
            }

            else -> {
                val faraday = draft.faradayResult ?: EffectResult.PRESENT
                val protection = draft.protectionResult ?: EffectResult.PRESENT
                val verdict = Shielding.interpret(
                    baselinePresent = draft.baselinePresent == true,
                    faraday = faraday,
                    returnedBetween = draft.returned == true,
                    hearingProtection = protection,
                )
                SectionCard("Interpretation") {
                    Text("A, Faraday enclosure: ${faraday.label}", style = MaterialTheme.typography.bodyMedium)
                    Text("B, hearing protection: ${protection.label}", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text(verdict.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                    Text(verdict.detail, style = MaterialTheme.typography.bodyMedium)
                    Note(Shielding.CAVEAT)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val run = ShieldingRun(
                                startedMs = draft.startedMs,
                                baselinePresent = draft.baselinePresent == true,
                                baselineNotes = draft.baselineNotes.trim(),
                                faraday = ConditionResult(draft.faradayTimeMs, faraday, draft.faradayNotes.trim()),
                                returnedBetween = draft.returned == true,
                                hearingProtection = ConditionResult(draft.protectionTimeMs, protection, draft.protectionNotes.trim()),
                                verdictTitle = verdict.title,
                                verdictDetail = verdict.detail,
                            )
                            vm.update { it.copy(shieldingRuns = it.shieldingRuns + run) }
                            edit { ShieldingDraft() }
                            toast(context, "Run saved to the session")
                        }) { Text("Save run") }
                        OutlinedButton(onClick = { edit { ShieldingDraft() } }) { Text("Discard") }
                    }
                }
            }
        }

        val runs = vm.session.shieldingRuns
        SectionCard("Recorded runs (${runs.size})") {
            if (runs.isEmpty()) Note("None yet. One run is an anecdote: plan on several.")
            for (index in runs.indices.reversed()) {
                val r = runs[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${Fmt.dateTime(r.startedMs)}   ${r.verdictTitle}", style = MaterialTheme.typography.bodyMedium)
                        Note("A: ${r.faraday.result.label}   B: ${r.hearingProtection.result.label}" +
                            if (r.returnedBetween) "" else "   (effect had not returned before B)")
                    }
                    IconButton(onClick = {
                        vm.update { s -> s.copy(shieldingRuns = s.shieldingRuns.filterIndexed { i, _ -> i != index }) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Delete run") }
                }
            }
            if (runs.size >= 2) {
                val titles = runs.map { it.verdictTitle }.distinct()
                Note(
                    if (titles.size == 1) "All ${runs.size} runs gave the same reading."
                    else "Runs disagree (${titles.size} different readings). Disagreement between runs is itself a result: " +
                        "it points to an inconsistent effect or uncontrolled conditions."
                )
            }
        }
    }
}

@Composable
private fun StepButtons(
    nextEnabled: Boolean,
    nextLabel: String = "Next",
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNext, enabled = nextEnabled) { Text(nextLabel) }
        OutlinedButton(onClick = onCancel) { Text("Cancel run") }
    }
}
