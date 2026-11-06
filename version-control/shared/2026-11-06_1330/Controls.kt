package dev.recorddisc.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.ArtworkCache
import dev.recorddisc.player.app.rememberArtwork
import dev.recorddisc.player.playback.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** "3:42" (or "1:02:03" for long files). */
fun formatTime(ms: Long?): String {
    if (ms == null || ms < 0) return "–:––"
    val total = ms / 1000
    val h = total / 3600
    val m = (total / 60) % 60
    val s = (total % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
}

/** A round icon button; [active] tints it with the accent (shuffle/repeat on). */
@Composable
fun CtrlButton(icon: ImageVector, label: String, size: Dp, iconSize: Dp, onClick: () -> Unit, active: Boolean = false, badge: String? = null) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = if (active) disc.accentReadable else disc.text)
        if (badge != null) {
            Text(
                badge, color = disc.accentReadable, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.offset(x = iconSize * 0.6f, y = -iconSize * 0.6f),
            )
        }
    }
}

/** The accent-filled play/pause button. */
@Composable
fun PlayButton(playing: Boolean, size: Dp, iconSize: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(disc.accent)
            .clickable(onClickLabel = if (playing) "Pause" else "Play", onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) DiscIcons.Pause else DiscIcons.Play, null, Modifier.size(iconSize), tint = disc.accentInk)
    }
}

/** Repeat cycles off → one → all, like the mockups. */
fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.One
    RepeatMode.One -> RepeatMode.All
    RepeatMode.All -> RepeatMode.Off
}

fun RepeatMode.label(): String = when (this) {
    RepeatMode.Off -> "Repeat off"
    RepeatMode.One -> "Repeat one"
    RepeatMode.All -> "Repeat all"
}

/**
 * Thin seek/volume bar. Shows [value] (0..1) unless the user is dragging, and reports the
 * final position through [onCommit] when they let go, so playback isn't flooded with seeks.
 */
@Composable
fun ThinSlider(value: Float, onCommit: (Float) -> Unit, label: String, modifier: Modifier = Modifier, trackHeight: Dp = 4.dp) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: value).coerceIn(0f, 1f)
    val accent = disc.accentReadable
    val track = disc.text.copy(alpha = 0.14f)
    Canvas(
        modifier.height(20.dp).fillMaxWidth()
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectTapGestures { onCommit((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onCommit); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val h = trackHeight.toPx()
        val y = size.height / 2
        val corner = CornerRadius(h / 2)
        drawRoundRect(track, Offset(0f, y - h / 2), Size(size.width, h), corner)
        drawRoundRect(accent, Offset(0f, y - h / 2), Size(size.width * shown, h), corner)
        drawCircle(accent, h * 1.6f, Offset(size.width * shown, y))
    }
}

/** Cover art thumbnail, or the accent gradient when the song has none. */
@Composable
fun ArtThumb(artwork: ArtworkCache, location: String?, size: Dp, corner: Dp, modifier: Modifier = Modifier, maxSizePx: Int = 160, round: Boolean = false) {
    val art by artwork.rememberArtwork(location, maxSizePx)
    val shape = if (round) CircleShape else RoundedCornerShape(corner)
    val base = Modifier.size(size).clip(shape).border(1.dp, disc.tint.copy(alpha = 0.08f), shape)
    val image = art
    if (image != null) {
        Image(androidx.compose.ui.graphics.painter.BitmapPainter(image), null, modifier.then(base), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.then(base).background(accentGradient()))
    }
}

@Composable
fun accentGradient(): Brush {
    val a = disc.accent
    return Brush.linearGradient(0f to a, 0.55f to lerp(a, Color(0xFF2B1A14), 0.55f), 1f to Color(0xFF2B1A14))
}

/** Marks a feature that comes in a later part: dimmed, not clickable, with a "Soon" tag. */
@Composable
fun SoonTag(modifier: Modifier = Modifier) {
    Text(
        "SOON",
        modifier.border(1.dp, disc.line, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = disc.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em,
    )
}

/** The mockups' small toast; shows each message from [messages] for 1.7 s. */
@Composable
fun MessageToast(messages: Flow<String>, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messages) {
        messages.collect {
            text = it
        }
    }
    LaunchedEffect(text) {
        if (text != null) {
            delay(1_700)
            text = null
        }
    }
    // Fades in while rising slightly, as in both mockups' toasts.
    AnimatedVisibility(text != null, modifier, enter = fadeIn() + androidx.compose.animation.slideInVertically { it / 3 }, exit = fadeOut()) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(disc.panel2).border(1.dp, disc.line, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(text.orEmpty(), color = disc.text, fontSize = 12.sp)
        }
    }
}
