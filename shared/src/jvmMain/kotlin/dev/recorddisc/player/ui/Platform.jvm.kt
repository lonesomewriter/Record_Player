package dev.recorddisc.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
actual fun rememberReduceMotion(): Boolean = false

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun dynamicAccentColor(): Color? = null
