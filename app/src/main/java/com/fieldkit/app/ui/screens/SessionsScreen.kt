package com.fieldkit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.data.SessionInfo
import com.fieldkit.app.ui.Field
import com.fieldkit.app.ui.ModeScreen
import com.fieldkit.app.ui.Note
import com.fieldkit.app.ui.SectionCard

/** Create, open and delete sessions. Every mode logs into the open session. */
@Composable
fun SessionsScreen(vm: AppViewModel, onOpened: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SessionInfo?>(null) }

    LaunchedEffect(Unit) { vm.refreshSessions() }

    ModeScreen(caption = null) {
        SectionCard("New session") {
            Field(newName, "Name (optional, e.g. place and date)") { newName = it.take(60) }
            Button(onClick = {
                vm.newSession(newName)
                newName = ""
                onOpened()
            }) { Text("Start new session") }
            Note("Use one session per site visit. Its report then covers one place and time.")
        }

        SectionCard("All sessions (${vm.sessions.size})") {
            for ((position, info) in vm.sessions.withIndex()) {
                if (position > 0) HorizontalDivider()
                val isOpen = info.id == vm.session.id
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.name + if (isOpen) "   (open)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Note("${Fmt.dateTime(info.createdMs)}   ${info.entryCount} entries")
                    }
                    if (!isOpen) {
                        OutlinedButton(onClick = {
                            vm.switchTo(info.id)
                            onOpened()
                        }) { Text("Open") }
                    }
                    IconButton(onClick = { pendingDelete = info }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete session")
                    }
                }
            }
        }
    }

    pendingDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this session?") },
            text = { Text("\"${info.name}\" and its ${info.entryCount} entries and snapshots will be permanently deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(info.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}
