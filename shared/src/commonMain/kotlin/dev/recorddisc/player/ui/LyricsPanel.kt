package dev.recorddisc.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.library.Lrc
import dev.recorddisc.player.library.Lyrics
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.isOutside
import dev.recorddisc.player.playback.PlaybackState

/** Opens the lyrics editor for a song; provided by each layout for the panels and song ⋮ menus. */
val LocalEditLyrics = androidx.compose.runtime.staticCompositionLocalOf<(TrackEntity) -> Unit> { {} }

/** [track]'s lyrics (null while loading or when it has none), read again after they change. */
@Composable
fun rememberLyrics(model: AppModel, track: TrackEntity?): State<Lyrics?> {
    val revision by model.lyricsRevision.collectAsState()
    return produceState<Lyrics?>(null, track?.location, track?.lyricsLocation, revision) {
        value = track?.let { model.lyrics(it) }
    }
}

/**
 * The lyrics under the disc (Android) or the song's buttons (desktop): the line being sung is
 * highlighted and kept in the middle; tapping a timed line jumps there. Lyrics without
 * timestamps are shown as plain text that scrolls.
 */
@Composable
fun LyricsPanel(model: AppModel, track: TrackEntity?, state: PlaybackState, windowHeight: Dp, textSize: Int, modifier: Modifier = Modifier) {
    val lyrics by rememberLyrics(model, track)
    val editLyrics = LocalEditLyrics.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(disc.tint.copy(alpha = 0.025f)).border(1.dp, disc.line, shape)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val label = if (lyrics?.embedded == true) "LYRICS · FROM THE SONG FILE" else "LYRICS"
            Text(label, Modifier.weight(1f), color = disc.muted, fontSize = 9.sp, letterSpacing = 0.16.em, fontWeight = FontWeight.Bold)
            if (track != null && !track.isOutside && !lyrics?.lines.isNullOrEmpty()) LinkText("✎ Edit") { editLyrics(track) }
        }
        val lines = lyrics?.lines.orEmpty()
        when {
            lines.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("No lyrics for this song yet", color = disc.muted, fontSize = 12.sp)
                if (track != null && !track.isOutside) LinkText("Write lyrics") { editLyrics(track) }
            }
            lyrics?.synced != true -> Column(
                Modifier.fillMaxWidth().height(windowHeight).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                lines.forEach {
                    Text(it.text, Modifier.padding(vertical = 4.dp), color = disc.text.copy(alpha = 0.9f), fontSize = textSize.sp, textAlign = TextAlign.Center)
                }
            }
            else -> SyncedLines(model, lyrics!!, track, state, windowHeight, textSize)
        }
    }
}

@Composable
private fun SyncedLines(model: AppModel, lyrics: Lyrics, track: TrackEntity?, state: PlaybackState, windowHeight: Dp, textSize: Int) {
    val isCurrent = track != null && state.current?.id == track.id
    val position = rememberSmoothPosition(state)
    val active = if (isCurrent) Lrc.activeLine(lyrics.lines, position) else -1
    // Where each line sits, to keep the active one (or the first) in the middle of the window.
    val tops = remember(lyrics) { mutableStateMapOf<Int, Pair<Float, Int>>() }
    var windowPx by remember { mutableStateOf(0) }
    val target = tops[active.coerceAtLeast(0)]?.let { (top, height) -> windowPx / 2f - top - height / 2f } ?: 0f
    val reduceMotion = rememberStill()
    val offset by animateFloatAsState(target, if (reduceMotion) snap() else tween(450))
    Box(
        Modifier.fillMaxWidth().height(windowHeight).clipToBounds().onSizeChanged { windowPx = it.height }
            // Lines fade out towards the top and bottom edges.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.28f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        // As tall as all the lines (not the window): the window clips it, and the offset slides it.
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = offset },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            lyrics.lines.forEachIndexed { i, line ->
                val color = when {
                    i == active -> disc.text
                    i < active -> disc.muted.copy(alpha = 0.35f)
                    else -> disc.muted.copy(alpha = 0.6f)
                }
                val time = line.timeMs
                Text(
                    line.text.ifEmpty { "♪" },
                    Modifier.fillMaxWidth().onPlaced { tops[i] = it.positionInParent().y to it.size.height }
                        .then(if (time != null && isCurrent) Modifier.clickable(onClickLabel = "Play from this line") { model.player.seekTo(time) } else Modifier)
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    color = color, fontSize = textSize.sp, fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center, lineHeight = 1.4.em,
                )
            }
        }
    }
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit) = Text(
    text, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 4.dp),
    color = disc.accentReadable, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
)
