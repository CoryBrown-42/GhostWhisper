package com.fieldkit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.core.Texts
import com.fieldkit.app.report.Exporter
import com.fieldkit.app.report.ReportText
import com.fieldkit.app.ui.Field
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.SectionCard
import com.fieldkit.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReportScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = vm.session
    var working by remember { mutableStateOf(false) }

    /** Runs a file write off the main thread and reports the outcome. */
    fun export(successMessage: String, job: () -> Boolean) {
        working = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { job() }
            working = false
            toast(context, if (ok) successMessage else "Export failed")
        }
    }

    /** Builds the file off the main thread, then opens the system share sheet. */
    fun share(pdf: Boolean) {
        working = true
        scope.launch {
            val intent = withContext(Dispatchers.IO) { Exporter.buildShareIntent(context, pdf, vm.session, vm.store) }
            working = false
            if (intent == null) {
                toast(context, "Export failed")
            } else {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    toast(context, "No app available to share with")
                }
            }
        }
    }

    // Storage Access Framework: Android shows its own file picker and the user chooses the destination.
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Exporter.MIME_PDF)) { uri ->
        if (uri != null) export("PDF saved") { Exporter.saveTo(context, uri, true, vm.session, vm.store) }
    }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Exporter.MIME_TEXT)) { uri ->
        if (uri != null) export("Text report saved") { Exporter.saveTo(context, uri, false, vm.session, vm.store) }
    }

    ModeScreen(Texts.CAPTION_REPORT) {
        SectionCard("Session") {
            Field(session.name, "Session name") { v -> vm.update { it.copy(name = v.take(60)) } }
            Field(session.notes, "Session notes (location, purpose, who was present)", singleLine = false) { v ->
                vm.update { it.copy(notes = v) }
            }
            Note("Started ${Fmt.dateTime(session.createdMs)}")
            Note(
                "${session.peaks.size} acoustic peaks, ${session.snapshots.size} snapshots, " +
                    "${session.observations.size} observations, ${session.shieldingRuns.size} shielding runs, " +
                    "${session.transects.size} transects, ${session.magSweeps.size} magnetometer sweeps"
            )
        }

        SectionCard("Export") {
            Note("The report is built on this phone. Nothing leaves it unless you save or share the file yourself.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !working, onClick = { savePdf.launch(Exporter.fileName(session, "pdf")) }) { Text("Save PDF...") }
                Button(enabled = !working, onClick = { saveText.launch(Exporter.fileName(session, "txt")) }) { Text("Save text...") }
                OutlinedButton(enabled = !working, onClick = { share(pdf = true) }) { Text("Share PDF") }
                OutlinedButton(enabled = !working, onClick = { share(pdf = false) }) { Text("Share text") }
            }
            if (working) Note("Building report...")
        }

        SectionCard("Preview") {
            val text = remember(session) { ReportText.build(session, System.currentTimeMillis()) }
            for (line in text.lines()) {
                when {
                    line.isBlank() -> Spacer(Modifier.height(4.dp))
                    line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.titleMedium)
                    line.startsWith("## ") -> Text(
                        line.removePrefix("## "),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    line.startsWith("| ") -> Text(
                        line.removePrefix("| "),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                    else -> Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            Note("Spectrogram snapshots appear as images in the PDF.")
        }
    }
}
