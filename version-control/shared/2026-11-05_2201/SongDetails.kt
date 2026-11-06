package dev.recorddisc.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.rememberArtwork
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.displayTitle

/*
 * Song details (2026-11-04 mockups): ⋮ → View Details on a song that isn't playing. The art is
 * shown as a record sleeve with the record sliding out of it, instead of the spinning disc.
 */

/**
 * A square cover sleeve ([cover] wide) with a still record peeking out to its right. With
 * [peekOnHover] (desktop) the record slides further out while the pointer is over it.
 */
@Composable
fun RecordSleeve(art: ImageBitmap?, cover: Dp, modifier: Modifier = Modifier, peekOnHover: Boolean = false) {
    val record = cover * 0.9f
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val reduceMotion = rememberReduceMotion()
    val slide by animateDpAsState(
        if (peekOnHover && hovered) cover * 0.06f else 0.dp,
        if (reduceMotion) snap() else tween(500),
        label = "recordPeek",
    )
    val still = remember { DiscSpin() }
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.size(cover * 1.2f, cover).then(if (peekOnHover) Modifier.hoverable(hover) else Modifier)) {
        Disc(record, still, art, Modifier.align(Alignment.CenterEnd).offset(x = slide))
        Box(
            Modifier.size(cover).shadow(22.dp, shape).clip(shape)
                .then(if (art == null) Modifier.background(accentGradient()) else Modifier)
                .drawWithContent {
                    drawContent()
                    // Printed-card sheen and a darker spine edge, as in the mockup.
                    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent), end = androidx.compose.ui.geometry.Offset(size.width * 0.45f, size.height * 0.45f)))
                    drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.32f), 0.07f to Color.Transparent, 0.93f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f)))
                }
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        ) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** "Not playing. <song> keeps playing until you press Play." */
@Composable
private fun NotPlayingNote(playingTitle: String?, isPlaying: Boolean, modifier: Modifier, center: Boolean, textSize: Int) {
    val text = buildAnnotatedString {
        if (playingTitle == null) {
            append("Not playing. Press Play to start it.")
        } else {
            append("Not playing. ")
            withStyle(SpanStyle(color = disc.text, fontWeight = FontWeight.SemiBold)) { append(playingTitle) }
            append(if (isPlaying) " keeps playing until you press Play." else " stays paused until you press Play.")
        }
    }
    Text(text, modifier, color = disc.muted, fontSize = textSize.sp, lineHeight = (textSize * 1.45f).sp, textAlign = if (center) TextAlign.Center else TextAlign.Start)
}

/**
 * Song details → Play. A song opened from a playlist plays with that playlist as the queue;
 * anywhere else it plays with the whole library queued, like tapping it in the Library.
 */
internal fun playFromDetails(model: AppModel, track: TrackEntity, source: PlaySource?) {
    val playlistSongs = (source as? PlaySource.Playlist)?.let { s -> model.playlists.value.firstOrNull { it.id == s.id }?.songs }
    val index = playlistSongs?.indexOfFirst { it.id == track.id } ?: -1
    if (playlistSongs != null && index >= 0) model.playPlaylistSong(playlistSongs, index) else model.play(listOf(track), 0)
}

/** Year · Genre · Track · Length boxes; missing tags show "—". */
@Composable
private fun RowScope.Fact(label: String, value: String?, weighted: Boolean) {
    Column(
        (if (weighted) Modifier.weight(1f) else Modifier.widthIn(min = 92.dp))
            .clip(RoundedCornerShape(12.dp)).background(disc.tint.copy(alpha = 0.025f))
            .border(1.dp, disc.line, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label.uppercase(), color = disc.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em)
        Text(value ?: "—", Modifier.padding(top = 3.dp), color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun TrackEntity.facts() = listOf(
    "Year" to year?.toString(),
    "Genre" to genre,
    "Track" to trackNumber?.toString(),
    "Length" to durationMs?.let { formatTime(it) },
)

/** Android Song details, a full screen like Now Playing. Swipe left, ✕ or Back returns to [returnLabel]. */
@Composable
fun PhoneSongDetails(
    model: AppModel,
    track: TrackEntity,
    playingTitle: String?,
    isPlaying: Boolean,
    returnLabel: String,
    onClose: () -> Unit,
    onPlay: () -> Unit,
) {
    val art by model.artwork.rememberArtwork(track.location, 640)
    val addToPlaylist = LocalAddToPlaylist.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton("✕", "Close song details", onClose)
            Eyebrow("Song details")
            Box {
                CircleIconButton("⋮", "More options") { menu = true }
                SongMenu(menu, { menu = false }, track, showDetails = false)
            }
        }
        RecordSleeve(art, 204.dp, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 18.dp))
        Text(track.displayTitle, color = disc.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.025).em, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist ?: "Unknown artist", Modifier.padding(top = 5.dp), color = disc.text.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.album ?: "No album", Modifier.padding(top = 3.dp), color = disc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value) in track.facts()) Fact(label, value, weighted = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { PageButton(DiscIcons.Play, "Play", filled = true, pill = false, fill = true, onClick = onPlay) }
            Box(Modifier.weight(1.35f)) { PageButton(DiscIcons.Plus, "Add to playlist", pill = false, fill = true) { addToPlaylist(track) } }
        }
        NotPlayingNote(playingTitle, isPlaying, Modifier.fillMaxWidth().padding(top = 12.dp), center = true, textSize = 11)
        Spacer(Modifier.weight(1f))
        Text(
            "SWIPE LEFT TO RETURN TO ${returnLabel.uppercase()}", Modifier.fillMaxWidth(),
            color = disc.muted, fontSize = 10.sp, letterSpacing = 0.14.em, textAlign = TextAlign.Center,
        )
    }
}

/** Desktop Song details page: drawn over the main view, so ← returns to the page underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopSongDetails(
    model: AppModel,
    track: TrackEntity,
    playingTitle: String?,
    isPlaying: Boolean,
    narrow: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val art by model.artwork.rememberArtwork(track.location, 720)
    val addToPlaylist = LocalAddToPlaylist.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(disc.panel).verticalScroll(rememberScrollState())
            .padding(horizontal = if (narrow) 18.dp else 34.dp, vertical = if (narrow) 22.dp else 28.dp),
    ) {
        DesktopPageHeader("Song details", track.displayTitle, onBack)
        val info: @Composable () -> Unit = {
            Column {
                Text(track.artist ?: "Unknown artist", color = disc.text.copy(alpha = 0.88f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.album ?: "No album", Modifier.padding(top = 5.dp), color = disc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((label, value) in track.facts()) Row { Fact(label, value, weighted = false) }
                }
                Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PageButton(DiscIcons.Play, "Play", filled = true, pill = false, textSize = 12, onClick = onPlay)
                    PageButton(DiscIcons.Plus, "Add to playlist", pill = false, textSize = 12) { addToPlaylist(track) }
                    Box {
                        CircleIconButton("⋮", "More options") { menu = true }
                        SongMenu(menu, { menu = false }, track, showDetails = false)
                    }
                }
                NotPlayingNote(playingTitle, isPlaying, Modifier.padding(top = 12.dp), center = false, textSize = 11)
            }
        }
        if (narrow) {
            RecordSleeve(art, 220.dp, peekOnHover = true)
            Box(Modifier.padding(top = 26.dp)) { info() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                RecordSleeve(art, 300.dp, peekOnHover = true)
                Box(Modifier.weight(1f)) { info() }
            }
        }
    }
}
