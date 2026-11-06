package dev.recorddisc.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppSettings
import dev.recorddisc.player.app.ThemeMode

/** Colour tokens from the disc mockups. The defaults are the dark theme; [LIGHT] is the warm-paper light theme. */
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
    /** The accent as text on the background; adjusted when the accent itself doesn't read on it. */
    val accentReadable: Color = Color(0xFFE0A458),
    val isLight: Boolean = false,
    /** Base for faint overlays (hover, borders, fills): white on dark, warm brown on light. */
    val tint: Color = Color.White,
    /** Desktop sidebar gradient. */
    val sideTop: Color = Color(0xFF15110E),
    val sideBottom: Color = Color(0xFF100D0B),
    /** Desktop player bar. */
    val dock: Color = Color(0xF517130F),
    /** Desktop queue column gradient. */
    val queueTop: Color = Color(0x801D1712),
    val queueBottom: Color = Color(0xE0110E0B),
    /** Dims the screen behind dialogs. */
    val scrim: Color = Color(0x99000000),
    /** Error text and borders (e.g. a field that needs fixing). */
    val error: Color = Color(0xFFEF8B78),
) {
    /** Tinted fills, like the mockups' --soft. */
    val soft: Color get() = accent.copy(alpha = 0.16f)

    companion object {
        /** The light theme from the approved mockup; the disc itself stays black vinyl. */
        val LIGHT = DiscColors(
            bg = Color(0xFFECE3D6),
            shell = Color(0xFFF3ECE1),
            panel = Color(0xFFF7F1E7),
            panel2 = Color(0xFFFFFAF2),
            text = Color(0xFF241A12),
            muted = Color(0xFF6B5C4E),
            line = Color(0x1F241A12),
            isLight = true,
            tint = Color(0xFF46301C),
            sideTop = Color(0xFFEFE6D8),
            sideBottom = Color(0xFFE9DFCF),
            dock = Color(0xF5FFFAF2),
            queueTop = Color(0x80FFFAF2),
            queueBottom = Color(0xE0F3ECE1),
            scrim = Color(0x5C281C12),
            error = Color(0xFFA63D2C),
        )

        fun forAccent(accent: Color, light: Boolean = false): DiscColors {
            val base = if (light) LIGHT else DiscColors()
            val lum = accent.luminance()
            return base.copy(
                accent = accent,
                accentInk = if (lum > 0.3f) Color(0xFF20140A) else Color(0xFFF3EBE0),
                accentReadable = when {
                    light -> darkenToRead(accent, base)
                    lum < 0.25f -> lerp(accent, Color.White, 0.45f)
                    else -> accent
                },
            )
        }

        /** Darkens [accent] in small steps until it reads (4.5:1) on both light panels. */
        private fun darkenToRead(accent: Color, base: DiscColors): Color {
            fun reads(c: Color) = contrast(c, base.panel) >= 4.5f && contrast(c, base.panel2) >= 4.5f
            var c = accent
            repeat(12) {
                if (reads(c)) return c
                c = Color(c.red * 0.82f, c.green * 0.82f, c.blue * 0.82f)
            }
            return if (reads(c)) c else base.text
        }
    }
}

/** WCAG contrast ratio between two colours. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Settings → Theme resolved to light or dark; System asks the platform. */
@Composable
fun AppSettings.useLightTheme(): Boolean = when (theme) {
    ThemeMode.Light -> true
    ThemeMode.Dark -> false
    ThemeMode.System -> !systemPrefersDark()
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

/**
 * The app's colours for [accent]. A new accent (a swatch, Dynamic color on a new song, the system
 * accent) blends in over 360 ms instead of snapping (2026-11-04 mockups); with reduced motion it
 * switches at once. The derived colours (ink, readable accent) follow the blended colour.
 */
@Composable
fun DiscTheme(
    accent: Color,
    light: Boolean = false,
    /** Settings → Battery Saver: all motion off, here and below. */
    batterySaver: Boolean = false,
    /** Settings → Shine brightness, as a factor of the default shine (1 = default). */
    shine: Float = 1f,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion() || batterySaver
    val shownAccent by animateColorAsState(
        accent,
        if (reduceMotion) snap() else tween(360, easing = FastOutSlowInEasing),
        label = "accent",
    )
    val colors = remember(shownAccent, light) { DiscColors.forAccent(shownAccent, light) }
    val scheme = remember(colors) {
        val base = if (light) lightColorScheme() else darkColorScheme()
        base.copy(
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
    }
    CompositionLocalProvider(LocalDiscColors provides colors, LocalBatterySaver provides batterySaver, LocalShine provides shine) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
