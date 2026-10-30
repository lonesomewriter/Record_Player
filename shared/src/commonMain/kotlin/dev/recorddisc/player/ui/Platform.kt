package dev.recorddisc.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** True when the system asks for less motion (Android: animations turned off). */
@Composable
expect fun rememberReduceMotion(): Boolean

/** Handles the system Back gesture/button (Android); does nothing on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** The wallpaper-based accent on Android 12+, or null where there is none. */
@Composable
expect fun dynamicAccentColor(): Color?

/** True when the phone or desktop is set to dark mode (unknown → dark). */
@Composable
expect fun systemPrefersDark(): Boolean

/** Android: keeps the status and navigation bar icons readable on the app's theme. */
@Composable
expect fun SystemBarsEffect(light: Boolean)
