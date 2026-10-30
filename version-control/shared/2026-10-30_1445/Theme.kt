package dev.recorddisc.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Colour tokens from the disc mockups (dark theme). */
@Immutable
data class DiscColors(
    val bg: Color = Color(0xFF0D0B09),
    val shell: Color = Color(0xFF12100D),
    val panel: Color = Color(0xFF15110E),
    val panel2: Color = Color(0xFF1D1712),
    val text: Color = Color(0xFFF3EBE0),
    val muted: Color = Color(0xFFA39380),
    val line: Color = Color(0x1AF3EBE0),
    val accent: Color = Color(0xFFE0A458),
    /** Text/icons on an accent background. */
    val accentInk: Color = Color(0xFF20140A),
    /** The accent as text on the dark background; lightened when the accent itself is too dark. */
    val accentReadable: Color = Color(0xFFE0A458),
) {
    /** Tinted fills, like the mockups' --soft. */
    val soft: Color get() = accent.copy(alpha = 0.16f)

    companion object {
        fun forAccent(accent: Color): DiscColors {
            val lum = accent.luminance()
            return DiscColors(
                accent = accent,
                accentInk = if (lum > 0.3f) Color(0xFF20140A) else Color(0xFFF3EBE0),
                accentReadable = if (lum < 0.25f) lerp(accent, Color.White, 0.45f) else accent,
            )
        }
    }
}

/**
 * Line height in proportion to the font size (like a browser's default), so text sized per
 * element keeps the mockups' spacing; Material's defaults fix it at 20–24 sp.
 */
private val typography = Typography().run {
    fun TextStyle.tight() = copy(lineHeight = 1.25.em, letterSpacing = 0.sp)
    copy(
        bodyLarge = bodyLarge.tight(), bodyMedium = bodyMedium.tight(), bodySmall = bodySmall.tight(),
        labelLarge = labelLarge.tight(), labelMedium = labelMedium.tight(), labelSmall = labelSmall.tight(),
        titleMedium = titleMedium.tight(),
    )
}

val LocalDiscColors = staticCompositionLocalOf { DiscColors() }

/** Shorthand for the current colours. */
val disc: DiscColors @Composable get() = LocalDiscColors.current

@Composable
fun DiscTheme(accent: Color, content: @Composable () -> Unit) {
    val colors = remember(accent) { DiscColors.forAccent(accent) }
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.accentInk,
        secondary = colors.accentReadable,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.panel,
        onSurface = colors.text,
        surfaceVariant = colors.panel2,
        onSurfaceVariant = colors.muted,
        outline = colors.line,
        surfaceContainer = colors.panel2,
        surfaceContainerHigh = colors.panel2,
    )
    CompositionLocalProvider(LocalDiscColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
