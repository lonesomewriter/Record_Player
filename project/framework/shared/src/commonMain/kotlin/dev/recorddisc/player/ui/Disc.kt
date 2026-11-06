package dev.recorddisc.player.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/** The disc's current angle, shared by every disc on screen so they turn together. */
@Stable
class DiscSpin {
    var angle by mutableFloatStateOf(0f)
}

/**
 * Turntable momentum, as in the mockups: speed eases up to full while [spinning] and coasts down
 * when it stops, stopping at whatever angle it reached. Both timings come from web-configs.json.
 */
@Composable
fun rememberDiscSpin(spinning: Boolean, periodMs: Float): DiscSpin {
    val spin = remember { DiscSpin() }
    val still = rememberStill()
    val batterySaver = LocalBatterySaver.current
    val target by rememberUpdatedState(spinning && !still)
    val stopAtOnce by rememberUpdatedState(batterySaver)
    LaunchedEffect(spin, periodMs) {
        var speed = 0f
        while (true) {
            if (speed == 0f) snapshotFlow { target }.first { it }
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // Cap the step so the disc doesn't jump after the app was in the background.
                val dt = min((now - last) / 1_000_000f, 100f)
                last = now
                val goal = if (target) 1f else 0f
                val timeConstant = if (goal > speed) WebConfigs.DISC_SPIN_UP_MS else WebConfigs.DISC_SPIN_DOWN_MS
                speed = if (timeConstant <= 0f) goal else speed + (goal - speed) * (1f - exp(-dt / timeConstant))
                // Battery Saver stops the record at once instead of letting it coast.
                if (goal == 0f && (speed < 0.001f || stopAtOnce)) {
                    speed = 0f
                    break
                }
                spin.angle = (spin.angle + speed * dt * 360f / periodMs) % 360f
            }
        }
    }
    return spin
}

/**
 * A vinyl record. The label shows [art] when there is cover art, otherwise an accent gradient
 * with "SIDE A"; [blank] is the grey label of the first-run screen. The record turns with [spin];
 * its shine stays still over it, and an accent
 * backlight glows around it (2026-11-05 mockups).
 */
@Composable
fun Disc(size: Dp, spin: DiscSpin, art: ImageBitmap?, modifier: Modifier = Modifier, blank: Boolean = false) {
    val accent = disc.accent
    Box(modifier.size(size).then(if (blank) Modifier else Modifier.drawBehind { drawBacklight(accent) }), contentAlignment = Alignment.Center) {
        DiscRecord(size, spin, art, blank)
        DiscLight(size)
    }
}

/** A soft rim of the accent colour around the record, as if lit from behind. Still, so it costs nothing. */
private fun DrawScope.drawBacklight(accent: Color) {
    val r = size.minDimension / 2
    for ((spread, alpha) in listOf(28.dp.toPx() to 0.24f, 64.dp.toPx() to 0.10f)) {
        val outer = r + spread
        drawCircle(
            Brush.radialGradient(
                (r * 0.9f / outer) to accent.copy(alpha = alpha), 1f to Color.Transparent,
                center = center, radius = outer,
            ),
            outer,
        )
    }
}

@Composable
private fun DiscRecord(size: Dp, spin: DiscSpin, art: ImageBitmap?, blank: Boolean) {
    val accent = disc.accent
    val measurer = rememberTextMeasurer()
    val small = size < 260.dp
    val sideStyle = TextStyle(
        color = if (blank || art != null) Color(0x59F3EBE0) else Color(0xC71A120C),
        fontSize = if (small) 8.sp else 10.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.18.em,
    )
    Canvas(
        Modifier
            .size(size)
            .shadow(26.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .graphicsLayer { rotationZ = spin.angle },
    ) {
        val r = this.size.minDimension / 2
        drawGrooves(r)
        val labelR = r * 0.54f
        if (art != null) {
            drawArt(art, labelR)
        } else {
            val stops = if (blank) {
                arrayOf(0f to Color(0xFF3A312A), 0.6f to Color(0xFF211B16), 1f to Color(0xFF16120F))
            } else {
                arrayOf(0f to accent, 0.55f to lerp(accent, Color(0xFF2B1A14), 0.55f), 1f to Color(0xFF2B1A14))
            }
            drawCircle(
                Brush.linearGradient(*stops, start = center - Offset(labelR, labelR), end = center + Offset(labelR, labelR)),
                labelR,
            )
        }
        drawLabelLight(labelR)
        if (art == null) {
            val text = measurer.measure("SIDE A", sideStyle)
            drawText(
                text,
                topLeft = Offset(
                    center.x - text.size.width / 2f,
                    center.y + labelR - (if (small) 8 else 11).dp.toPx() - text.size.height,
                ),
            )
        }
        val hole = (if (small) 4.5f else 5f).dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.35f), hole + 3.dp.toPx())
        drawCircle(Color(0xFF0B0908), hole)
    }
}

private fun DrawScope.drawGrooves(r: Float) {
    drawCircle(Color(0xFF0A0908), r)
    val step = 3.dp.toPx()
    val groove = Stroke(1.dp.toPx())
    var gr = r * 0.28f
    while (gr < r) {
        drawCircle(Color(0xFF14110E), gr, style = groove)
        gr += step
    }
    // Shadow round the rim. (The shine is drawn still, over the turning record: DiscLight.)
    drawCircle(Brush.radialGradient(0.72f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f), center = center, radius = r), r)
    drawCircle(Color.White.copy(alpha = 0.07f), r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawArt(art: ImageBitmap, labelR: Float) {
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, labelR)) }
    clipPath(circle) {
        // Centre-crop the image to a square filling the label.
        val side = min(art.width, art.height)
        val src = IntOffset((art.width - side) / 2, (art.height - side) / 2)
        val d = (labelR * 2).roundToInt()
        drawImage(
            art,
            srcOffset = src,
            srcSize = IntSize(side, side),
            dstOffset = IntOffset((center.x - labelR).roundToInt(), (center.y - labelR).roundToInt()),
            dstSize = IntSize(d, d),
        )
    }
}

private fun DrawScope.drawLabelLight(labelR: Float) {
    val topLeft = center - Offset(labelR, labelR)
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.35f), 0.42f to Color.Transparent,
            center = topLeft + Offset(labelR * 0.6f, labelR * 0.5f), radius = labelR * 1.6f,
        ),
        labelR,
    )
    drawCircle(
        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.38f), startY = topLeft.y, endY = topLeft.y + 2 * labelR),
        labelR,
    )
    drawCircle(Brush.radialGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f), center = center, radius = labelR), labelR)
    drawCircle(Color.Black.copy(alpha = 0.45f), labelR - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
}

/**
 * The dotted "marquee lights" arc beside the disc (Android home). [running] moves the dots along
 * the arc like theatre lights; otherwise they stand still. [bothSides] adds the mirrored left arc.
 */
@Composable
fun MarqueeArc(boxSize: Dp, running: Boolean, modifier: Modifier = Modifier, bothSides: Boolean = false, dim: Boolean = false) {
    val color = disc.accentReadable
    val reduceMotion = rememberStill()
    val phase = if (running && !reduceMotion) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 8f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart)).value
    } else {
        0f
    }
    Canvas(modifier.size(boxSize)) {
        // Same geometry as the mockup's SVG: radius 122 in a 320 box, ±55° either side of the right.
        val unit = this.size.minDimension / 320f
        val radius = 122f * unit
        val arcTopLeft = center - Offset(radius, radius)
        val arcSize = Size(radius * 2, radius * 2)
        val dots = PathEffect.dashPathEffect(floatArrayOf(2f * unit, 6f * unit), -phase * unit)
        val alpha = if (dim) 0.3f else 0.6f
        fun arc(start: Float) {
            drawArc(color.copy(alpha = alpha * 0.3f), start, 110f, false, arcTopLeft, arcSize, style = Stroke(4f * unit, cap = StrokeCap.Round, pathEffect = dots))
            drawArc(color.copy(alpha = alpha), start, 110f, false, arcTopLeft, arcSize, style = Stroke(1.3f * unit, cap = StrokeCap.Round, pathEffect = dots))
        }
        arc(-55f)
        if (bothSides) arc(125f)
    }
}

/** Desktop: the dotted ring round the disc, turning slowly (faster while [fast]). */
@Composable
fun MarqueeRing(diameter: Dp, running: Boolean, modifier: Modifier = Modifier, fast: Boolean = false, dim: Boolean = false) {
    val color = disc.accentReadable
    val reduceMotion = rememberStill()
    val turn = if (running && !reduceMotion) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (fast) 16_000 else 120_000, easing = LinearEasing))).value
    } else {
        0f
    }
    Canvas(modifier.size(diameter).graphicsLayer { rotationZ = turn }) {
        val w = 2.dp.toPx()
        val dots = PathEffect.dashPathEffect(floatArrayOf(0.01f, 2 * w), 0f)
        val alpha = if (dim) 0.45f else 1f
        drawCircle(color.copy(alpha = 0.08f * alpha), this.size.minDimension / 2 - w, style = Stroke(w * 2.5f, cap = StrokeCap.Round, pathEffect = dots))
        drawCircle(color.copy(alpha = 0.42f * alpha), this.size.minDimension / 2 - w, style = Stroke(w * 0.8f, cap = StrokeCap.Round, pathEffect = dots))
    }
}

/**
 * The glowing dot beside "Local": fades to a quarter and back, slowly, as in the mockups (the
 * speed comes from web-configs.json). It stays lit when the system asks for less motion.
 */
@Composable
fun LocalLight(modifier: Modifier = Modifier) {
    val color = disc.accentReadable
    val glow = disc.accent
    val reduceMotion = rememberStill()
    val level = if (reduceMotion) {
        1f
    } else {
        val t = rememberInfiniteTransition()
        t.animateFloat(1f, 0f, infiniteRepeatable(tween(WebConfigs.LOCAL_BLINK_MS / 2, easing = EaseInOut), RepeatMode.Reverse)).value
    }
    Canvas(modifier.size(6.dp)) {
        val opacity = 0.25f + 0.75f * level
        val dot = this.size.minDimension / 2
        // Like the mockup's box-shadow: a 10 dp glow that shrinks to 2 dp at the dimmest point.
        val spread = dot + (2f + 8f * level).dp.toPx()
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = (0.4f + 0.4f * level) * opacity), Color.Transparent), center, spread), spread)
        drawCircle(color.copy(alpha = opacity), dot)
    }
}

/** CSS's ease-in-out. */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
