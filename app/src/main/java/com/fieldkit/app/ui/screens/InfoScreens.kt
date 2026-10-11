package com.fieldkit.app.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.fieldkit.app.core.Texts
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.SectionCard

/** Reachable from the info icon in the top bar of every mode. Also shown on first launch. */
@Composable
fun SafetyScreen(onDone: () -> Unit) {
    ModeScreen(caption = null) {
        Text("Read this before any field session.", style = MaterialTheme.typography.titleMedium)
        for ((title, body) in Texts.SAFETY) {
            SectionCard(title) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onDone) { Text("Understood") }
    }
}

@Composable
fun HardwareLimitsScreen() {
    ModeScreen(caption = null) {
        Text("What a phone can and cannot measure", style = MaterialTheme.typography.titleMedium)
        for ((title, body) in Texts.HARDWARE_LIMITS) {
            SectionCard(title) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionCard("In short") {
            Note(Texts.DISCLAIMER)
        }
    }
}
