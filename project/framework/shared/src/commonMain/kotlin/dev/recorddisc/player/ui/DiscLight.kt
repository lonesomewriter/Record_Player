package dev.recorddisc.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The record's shine (2026-11-05 mockups; visualizer removed 2026-11-06 by the project manager):
 * two soft accent-coloured spots on the vinyl, never on the label. They're drawn over the turning
 * record but stay still themselves.
 */

/** Settings → Battery Saver: all motion off. Provided by each app's layout. */
val LocalBatterySaver = staticCompositionLocalOf { false }

/** Settings → Shine brightness as a factor of the default shine (0 = off, 1 = default, 3 = brightest). */
val LocalShine = staticCompositionLocalOf { 1f }

/** True when nothing should move: the system asks for less motion, or Battery Saver is on. */
@Composable
fun rememberStill(): Boolean = rememberReduceMotion() || LocalBatterySaver.current

/** The shine's two spots: from, brightest, to (degrees from the top, clockwise) and peak brightness. */
private val CONES = listOf(Cone(34f, 44f, 56f, 0.24f), Cone(178f, 196f, 214f, 0.15f))

private class Cone(val from: Float, val peak: Float, val to: Float, val top: Float)

/** Inner and outer edge of the vinyl the shine covers, as fractions of the record's radius. */
private const val INNER = 0.57f
private const val OUTER = 0.97f

/** How bright the shine is by default (the mockup's resting level); Settings scales it. */
private const val STRENGTH = 0.3f

/** The still shine over a record of [size]. */
@Composable
fun DiscLight(size: Dp, modifier: Modifier = Modifier) {
    val accent = disc.accent
    val shine = LocalShine.current
    if (shine <= 0f) return
    Canvas(modifier.size(size)) { drawShine(accent, STRENGTH * shine) }
}

private fun DrawScope.drawShine(accent: Color, strength: Float) {
    val r = size.minDimension / 2
    val mid = center
    val inner = r * INNER
    val outer = r * OUTER
    fun pointAt(angle: Float, radius: Float): Offset {
        val t = (angle - 90f) * PI.toFloat() / 180f
        return Offset(mid.x + cos(t) * radius, mid.y + sin(t) * radius)
    }
    // Each spot is brightest at its peak and fades to its edges, drawn in thin slices.
    val slice = 1f
    for (cone in CONES) {
        var a = cone.from
        while (a < cone.to) {
            val centre = a + slice / 2
            val shape = if (centre <= cone.peak) (centre - cone.from) / (cone.peak - cone.from) else (cone.to - centre) / (cone.to - cone.peak)
            val alpha = (cone.top * shape * strength).coerceAtMost(1f)
            if (alpha >= 0.003f) {
                val a0 = pointAt(a, inner)
                val path = Path().apply {
                    moveTo(a0.x, a0.y)
                    pointAt(a, outer).let { lineTo(it.x, it.y) }
                    pointAt(a + slice, outer).let { lineTo(it.x, it.y) }
                    pointAt(a + slice, inner).let { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, accent.copy(alpha = alpha), blendMode = BlendMode.Screen)
            }
            a += slice
        }
    }
}
