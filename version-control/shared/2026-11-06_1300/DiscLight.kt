package dev.recorddisc.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.recorddisc.player.playback.SpectrumHub
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * The record's light (2026-11-05 mockups), drawn over the turning record but itself still: an
 * accent-coloured shine on the vinyl (never on the label) with spectrum lines running along the
 * grooves. It flares on the beat, and bends the way the record turns while it turns.
 */

/** The playing music's spectrum, provided by each app's layout from the player. */
val LocalSpectrum = staticCompositionLocalOf<SpectrumHub?> { null }

/** Settings → Battery Saver: all motion off. Provided by each app's layout. */
val LocalBatterySaver = staticCompositionLocalOf { false }

/** True when nothing should move: the system asks for less motion, or Battery Saver is on. */
@Composable
fun rememberStill(): Boolean = rememberReduceMotion() || LocalBatterySaver.current

/** The shine's two spots: from, brightest, to (degrees from the top, clockwise) and peak brightness. */
private val CONES = listOf(Cone(34f, 44f, 56f, 0.24f), Cone(178f, 196f, 214f, 0.15f))

private class Cone(val from: Float, val peak: Float, val to: Float, val top: Float)

/** Inner and outer edge of the vinyl the light covers, as fractions of the record's radius. */
private const val INNER = 0.57f
private const val OUTER = 0.97f

/** A full-level line reaches this far past its cone's edges. */
private const val SPREAD = 1.35f

/**
 * The still light over a record of [size] turning with [spin]. With [visualizer], it follows the
 * player's spectrum while on screen; otherwise it's just the soft shine.
 */
@Composable
fun DiscLight(size: Dp, spin: DiscSpin, visualizer: Boolean, modifier: Modifier = Modifier) {
    val accent = disc.accent
    val still = rememberStill()
    val hub = LocalSpectrum.current.takeIf { visualizer && !still }
    val viz = remember { ShineVisualizer() }
    var frame by remember { mutableLongStateOf(0L) }
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)

    // Ask for analysis only while shown in a started app; give it up as soon as that ends.
    if (hub != null && started) {
        DisposableEffect(hub) {
            hub.acquire()
            onDispose { hub.release() }
        }
    }
    LaunchedEffect(hub, started, still) {
        viz.update(0f, null, quiet = still || hub == null || !started)
        frame++
        if (still || !started) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = min((now - last) / 1_000_000f, 100f)
            last = now
            val bands = hub?.current()
            viz.update(dt, bands, quiet = false)
            frame++
            // Idle (paused, nothing left to fade, record stopped): wait for the record to turn again.
            if (bands == null && viz.beat == 0f && viz.levels.all { it == 0f } && spin.speed == 0f) {
                snapshotFlow { spin.speed }.first { it > 0f }
                last = withFrameNanos { it }
            }
        }
    }
    Canvas(modifier.size(size)) {
        frame // redraw on every visualizer frame
        drawLight(accent, viz, if (still) 0f else spin.speed, showLines = hub != null)
    }
}

private fun DrawScope.drawLight(accent: Color, viz: ShineVisualizer, speed: Float, showLines: Boolean) {
    val r = size.minDimension / 2
    val mid = center
    val inner = r * INNER
    val outer = r * OUTER
    val beat = viz.beat
    // Bend: the outer edge leans the way the record turns, more on a beat; straight when stopped.
    val sway = speed * (2f + 8f * beat)
    fun pointAt(angle: Float, radius: Float): Offset {
        val u = ((radius - inner) / (outer - inner)).coerceIn(0f, 1f)
        val t = (angle + sway * u.pow(1.4f) - 90f) * PI.toFloat() / 180f
        return Offset(mid.x + cos(t) * radius, mid.y + sin(t) * radius)
    }
    // The glow: brightest at each cone's peak, fading to its edges; brighter on a beat.
    val strength = 0.3f + 0.7f * beat
    val slice = 1f
    val rings = 10
    for (cone in CONES) {
        var a = cone.from
        while (a < cone.to) {
            val centre = a + slice / 2
            val shape = if (centre <= cone.peak) (centre - cone.from) / (cone.peak - cone.from) else (cone.to - centre) / (cone.to - cone.peak)
            val alpha = cone.top * shape * strength
            if (alpha >= 0.003f) {
                val path = Path()
                for (k in 0..rings) {
                    val p = pointAt(a, inner + (outer - inner) * k / rings)
                    if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                for (k in rings downTo 0) {
                    val p = pointAt(a + slice, inner + (outer - inner) * k / rings)
                    path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, accent.copy(alpha = alpha), blendMode = BlendMode.Screen)
            }
            a += slice
        }
    }
    if (!showLines) return
    // Spectrum lines: along the grooves, centred on the cone's peak, as long as their level.
    val width = max(1f, size.minDimension / 300f)
    val lift = 0.75f + 0.55f * beat
    val count = viz.lines
    for (cone in CONES) {
        for (i in 0 until count) {
            val level = viz.levels[i].coerceIn(0f, 1f)
            if (level < 0.02f) continue
            val radius = inner + (outer - inner) * i / (count - 1)
            val weight = 1f - kotlin.math.abs(i.toFloat() / (count - 1) - 0.5f) * 0.6f
            val start = cone.peak - (cone.peak - cone.from) * level * SPREAD
            val end = cone.peak + (cone.to - cone.peak) * level * SPREAD
            val alpha = min(0.55f, (0.08f + 0.42f * level * weight) * lift)
            val path = Path()
            val points = 12
            for (k in 0..points) {
                val p = pointAt(start + (end - start) * k / points, radius)
                if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, accent.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round), blendMode = BlendMode.Screen)
        }
    }
}
