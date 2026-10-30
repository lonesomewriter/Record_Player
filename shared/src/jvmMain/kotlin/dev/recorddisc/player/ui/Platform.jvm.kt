package dev.recorddisc.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberReduceMotion(): Boolean = false

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun dynamicAccentColor(): Color? = null

/** Linux: the desktop's dark/light setting, read at start and again when the window gains or loses focus. */
@Composable
actual fun systemPrefersDark(): Boolean {
    var dark by remember { mutableStateOf(true) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        dark = withContext(Dispatchers.IO) { LinuxColorScheme.prefersDark() } ?: true
    }
    return dark
}

@Composable
actual fun SystemBarsEffect(light: Boolean) = Unit
