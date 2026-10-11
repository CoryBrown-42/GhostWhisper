package com.fieldkit.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.fieldkit.app.AppViewModel
import com.fieldkit.app.ui.screens.AcousticScreen
import com.fieldkit.app.ui.screens.HardwareLimitsScreen
import com.fieldkit.app.ui.screens.MagnetometerScreen
import com.fieldkit.app.ui.screens.PhenomenologyScreen
import com.fieldkit.app.ui.screens.RangeScreen
import com.fieldkit.app.ui.screens.ReportScreen
import com.fieldkit.app.ui.screens.SafetyScreen
import com.fieldkit.app.ui.screens.SessionsScreen
import com.fieldkit.app.ui.screens.ShieldingScreen

/** The six modes on the bottom bar. */
enum class Mode(val tabLabel: String, val title: String, val icon: ImageVector) {
    ACOUSTIC("Acoustic", "Acoustic Survey", Icons.Filled.Notifications),
    LOG("Log", "Phenomenology Log", Icons.Filled.Create),
    SHIELD("Shield", "Shielding Test", Icons.Filled.Lock),
    RANGE("Range", "Range Map", Icons.Filled.Place),
    MAGNET("Magnet", "Magnetometer Sweep", Icons.Filled.Build),
    REPORT("Report", "Session Report", Icons.Filled.Share),
}

/** Full-screen pages opened from the top bar, on top of whichever mode is showing. */
enum class Overlay(val title: String) {
    NONE(""),
    SAFETY("Safety"),
    LIMITS("Hardware limits"),
    SESSIONS("Sessions"),
}

@Composable
fun AppRoot(vm: AppViewModel) {
    var mode by rememberSaveable { mutableStateOf(Mode.ACOUSTIC) }
    // First launch opens on the Safety screen.
    var overlay by rememberSaveable {
        mutableStateOf(if (vm.store.safetyAcknowledged) Overlay.NONE else Overlay.SAFETY)
    }
    var menuOpen by remember { mutableStateOf(false) }

    fun closeOverlay() {
        if (overlay == Overlay.SAFETY) vm.store.safetyAcknowledged = true
        overlay = Overlay.NONE
    }

    BackHandler(enabled = overlay != Overlay.NONE) { closeOverlay() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (overlay == Overlay.NONE) mode.title else overlay.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = vm.session.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (overlay != Overlay.NONE) {
                        IconButton(onClick = { closeOverlay() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // Safety is one tap away from every mode.
                    IconButton(onClick = { overlay = Overlay.SAFETY }) {
                        Icon(Icons.Filled.Info, contentDescription = "Safety information")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Sessions") },
                                onClick = { menuOpen = false; overlay = Overlay.SESSIONS },
                            )
                            DropdownMenuItem(
                                text = { Text("Hardware limits") },
                                onClick = { menuOpen = false; overlay = Overlay.LIMITS },
                            )
                            DropdownMenuItem(
                                text = { Text("Safety") },
                                onClick = { menuOpen = false; overlay = Overlay.SAFETY },
                            )
                            DropdownMenuItem(
                                text = { Text(if (vm.darkTheme) "Use light theme" else "Use dark theme") },
                                onClick = { menuOpen = false; vm.changeTheme(!vm.darkTheme) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (overlay == Overlay.NONE) {
                NavigationBar {
                    for (item in Mode.entries) {
                        NavigationBarItem(
                            selected = mode == item,
                            onClick = { mode = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = {
                                Text(item.tabLabel, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            when (overlay) {
                Overlay.SAFETY -> SafetyScreen(onDone = { closeOverlay() })
                Overlay.LIMITS -> HardwareLimitsScreen()
                Overlay.SESSIONS -> SessionsScreen(vm, onOpened = { overlay = Overlay.NONE })
                Overlay.NONE -> when (mode) {
                    Mode.ACOUSTIC -> AcousticScreen(vm)
                    Mode.LOG -> PhenomenologyScreen(vm)
                    Mode.SHIELD -> ShieldingScreen(vm)
                    Mode.RANGE -> RangeScreen(vm)
                    Mode.MAGNET -> MagnetometerScreen(vm)
                    Mode.REPORT -> ReportScreen(vm)
                }
            }
        }
    }
}
