package com.fieldkit.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import com.fieldkit.app.ui.AppRoot
import com.fieldkit.app.ui.theme.FieldKitTheme

/** The only activity. All navigation happens inside Compose (see ui/AppRoot.kt). */
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = viewModel.darkTheme
            // Match status/navigation bar icon colours to the app theme, not the system theme.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            FieldKitTheme(dark = dark) {
                AppRoot(viewModel)
            }
        }
    }
}
