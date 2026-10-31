package dev.recorddisc.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.ScanState
import dev.recorddisc.player.app.rememberArtwork
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.RepeatMode

enum class DesktopView(val title: String, val icon: ImageVector) {
    Home("Home", DiscIcons.Home),
    Library("Library", DiscIcons.Library),
    Playlists("Playlists", DiscIcons.Playlist),
    Settings("Settings", DiscIcons.Settings),
}

private const val SOON = "Coming in a later update"

/**
 * The Linux layout: sidebar, main view, "Up next" queue and the dock. Below 1220 dp the queue
 * hides; below 900 dp the sidebar becomes a top bar and everything stacks.
 * [chooseFolder] opens the system folder dialog and returns the chosen path, or null.
 * [startView], [startLibrary] and [startAccentPicker] open a given view, Library page or the
 * custom accent dialog (used by the screenshot test). [appVersion] is shown in Settings → About.
 * [editor] opens the cover image picker; [startEditor] opens the song details editor on the
 * current song (screenshot test).
 */
@Composable
fun DesktopApp(
    model: AppModel,
    chooseFolder: () -> String?,
    startView: DesktopView = DesktopView.Home,
    startLibrary: LibraryNav? = null,
    appVersion: String = "dev",
    startAccentPicker: Boolean = false,
    editor: EditorActions = EditorActions(),
    startEditor: Boolean = false,
) {
    val settings = model.settings.collectAsState().value
    DiscTheme(Color(settings?.accentArgb ?: 0xFFE0A458), light = settings?.useLightTheme() == true) {
        BoxWithConstraints(Modifier.fillMaxSize().background(disc.shell)) {
            if (settings == null) return@BoxWithConstraints
            val narrow = maxWidth < 900.dp
            val roomForQueue = maxWidth >= 1220.dp
            val state by model.player.state.collectAsState()
            val scan by model.scan.collectAsState()
            val spin = rememberDiscSpin(state.isPlaying || scan is ScanState.Running, periodMs = 18_000f)
            var picking by remember { mutableStateOf(startAccentPicker) }
            var editingId by remember { mutableStateOf(if (startEditor) state.current?.id else null) }
            val tracks = rememberTrackIndex(model)
            if (!settings.setupDone) {
                var folder by remember { mutableStateOf(model.platform.suggestedFolder) }
                DesktopWelcome(
                    scan, spin, folder, model.platform::folderLabel,
                    onChangeFolder = { chooseFolder()?.let { folder = it; model.cancelScan() } },
                    onScan = { folder?.let(model::scan) },
                    onCancelScan = model::cancelScan,
                    onStart = { model.finishSetup() },
                    narrow = narrow,
                )
            } else ProvideEditSong({ editingId = it.id }) {
                var view by remember { mutableStateOf(startView) }
                var queueOpen by remember { mutableStateOf(true) }
                val main = MainContext(narrow, startLibrary, appVersion, chooseFolder) { picking = true }
                if (narrow) {
                    Column(Modifier.fillMaxSize()) {
                        TopNav(view) { view = it }
                        Box(Modifier.weight(1f).fillMaxWidth().background(disc.panel)) { MainView(model, state, spin, view, main) }
                        Dock(model, state, narrow = true, queueOpen = false, onToggleQueue = null) { view = DesktopView.Home }
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(model, view, if (roomForQueue) 236.dp else 210.dp) { view = it }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                Box(Modifier.weight(1f).fillMaxHeight().background(disc.panel)) { MainView(model, state, spin, view, main) }
                                if (roomForQueue && queueOpen) QueuePanel(model, state)
                            }
                            Dock(model, state, narrow = false, queueOpen = queueOpen, onToggleQueue = if (roomForQueue) ({ queueOpen = !queueOpen }) else null) {
                                view = DesktopView.Home
                            }
                        }
                    }
                }
            }
            if (settings.setupDone && picking) {
                AccentPicker(settings.customAccent, eyebrow = "Appearance", onCancel = { picking = false }) {
                    model.setCustomAccent(it)
                    picking = false
                }
            }
            val editing = tracks[editingId]
            if (settings.setupDone && editing != null) DesktopTagEditor(model, editing, editor) { editingId = null }
            MessageToast(model.messages, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
        }
    }
}

@Composable
private fun Sidebar(model: AppModel, view: DesktopView, width: androidx.compose.ui.unit.Dp, onSelect: (DesktopView) -> Unit) {
    val count = model.tracks.collectAsState().value.size
    val divider = disc.line
    Column(
        Modifier.width(width).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(disc.sideTop, disc.sideBottom)))
            .drawBehind { drawLine(divider, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height)) }
            .padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 16.dp),
    ) {
        BrandRow(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 26.dp))
        for (v in DesktopView.entries) NavItem(v, v == view) { onSelect(v) }
        Text("YOUR PLAYLISTS", Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp), color = disc.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.17.em)
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Playlists arrive in a later update", color = disc.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(disc.line))
        Column(Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LocalLight()
                Text("LOCAL LIBRARY", color = disc.text, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em)
            }
            Text(plural(count, "track").uppercase(), color = disc.muted, fontSize = 9.sp, letterSpacing = 0.12.em)
        }
    }
}

@Composable
private fun NavItem(v: DesktopView, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth()).padding(vertical = 1.5.dp).clip(RoundedCornerShape(11.dp))
            .background(if (selected) disc.soft else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 11.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(v.icon, null, Modifier.size(18.dp), tint = if (selected) disc.text else disc.muted)
        Text(
            v.title, Modifier.then(if (compact) Modifier else Modifier.weight(1f)), color = if (selected) disc.text else disc.muted,
            fontSize = if (compact) 12.sp else 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (!compact) {
            Box(Modifier.size(6.dp).alpha(if (selected) 1f else 0.28f).clip(CircleShape).background(disc.accentReadable))
        }
        if (v == DesktopView.Playlists) SoonTag()
    }
}

@Composable
private fun TopNav(view: DesktopView, onSelect: (DesktopView) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(disc.sideTop).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandRow(showSub = false)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (v in DesktopView.entries) NavItem(v, v == view, compact = true) { onSelect(v) }
        }
    }
}

/** What the main view needs besides the model and player state. */
private class MainContext(
    val narrow: Boolean,
    val startLibrary: LibraryNav?,
    val appVersion: String,
    val chooseFolder: () -> String?,
    val onCustomAccent: () -> Unit,
)

@Composable
private fun MainView(model: AppModel, state: PlaybackState, spin: DiscSpin, view: DesktopView, main: MainContext) {
    val narrow = main.narrow
    if (view == DesktopView.Library) {
        LibraryView(model, state, narrow, main.startLibrary)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (narrow) 18.dp else 34.dp, vertical = if (narrow) 22.dp else 28.dp)) {
        ViewHeader(model, view)
        when (view) {
            DesktopView.Home -> HomeView(model, state, spin, narrow)
            DesktopView.Library -> Unit
            DesktopView.Playlists -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Playlists are coming in a later update.", color = disc.muted, fontSize = 13.sp)
                SoonTag()
            }
            DesktopView.Settings -> DesktopSettings(model, narrow, main.appVersion, main.chooseFolder, main.onCustomAccent)
        }
    }
}

/**
 * Library: header with the track count and Play all, then search, tabs and the songs, albums or
 * artists; or an album or artist page. One lazy list, so large libraries only draw what's on screen.
 */
@Composable
private fun LibraryView(model: AppModel, state: PlaybackState, narrow: Boolean, start: LibraryNav?) {
    val nav = remember { start ?: LibraryNav() }
    val songs by model.librarySongs.collectAsState()
    val albums by model.libraryAlbums.collectAsState()
    val artists by model.libraryArtists.collectAsState()
    val allAlbums by model.albums.collectAsState()
    val allArtists by model.artists.collectAsState()
    val all by model.tracks.collectAsState()
    val pad = if (narrow) 18.dp else 34.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val grid = CardGrid(maxWidth - pad * 2)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = pad, vertical = if (narrow) 22.dp else 28.dp)) {
            when (val page = nav.page) {
                is LibraryPage.AlbumPage -> desktopAlbumPage(model, allAlbums.firstOrNull { it.key == page.key }, nav, state.current?.id)
                is LibraryPage.ArtistPage -> desktopArtistPage(model, allArtists.firstOrNull { it.name == page.name }, nav, state.current?.id, grid, showAlbum = !narrow)
                null -> {
                    item(key = "head") { ViewHeader(model, DesktopView.Library) }
                    item(key = "bar") {
                        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LibrarySearch(model, Modifier.weight(1f), textSize = 12)
                            LibraryTabs(nav.tab, textSize = 11) { nav.tab = it }
                        }
                    }
                    when (nav.tab) {
                        LibraryTab.Songs -> desktopSongTable(model, songs, state.current?.id, showAlbum = !narrow)
                        LibraryTab.Albums -> {
                            if (albums.isEmpty()) item(key = "noAlbums") { NoMatches(model, nav.tab) }
                            desktopAlbumGrid(model, albums, grid, "album") { nav.open(LibraryPage.AlbumPage(it.key)) }
                        }
                        LibraryTab.Artists -> {
                            if (artists.isEmpty()) item(key = "noArtists") { NoMatches(model, nav.tab) }
                            desktopArtistGrid(model, artists, grid) { nav.open(LibraryPage.ArtistPage(it.name)) }
                        }
                    }
                    if (all.isEmpty()) item(key = "none") { Text("Your library is empty.", color = disc.muted, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** Eyebrow + title; Home adds the library pills, Library adds the count and Play all. */
@Composable
private fun ViewHeader(model: AppModel, view: DesktopView) {
    val tracks by model.tracks.collectAsState()
    val count = tracks.size
        Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Eyebrow(
                    when (view) {
                        DesktopView.Home -> "Now spinning"
                        DesktopView.Library -> "Collection"
                        DesktopView.Playlists -> "Your mixes"
                        DesktopView.Settings -> "Preferences"
                    },
                )
                Text(view.title, Modifier.padding(top = 5.dp), color = disc.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.035).em)
            }
            if (view == DesktopView.Settings) Pill("Stored locally")
            if (view == DesktopView.Home) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Pill("Local · ${plural(count, "track")}")
                    Pill("Offline")
                }
            }
            if (view == DesktopView.Library) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(plural(count, "track"))
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, disc.line, RoundedCornerShape(12.dp))
                            .clickable(enabled = tracks.isNotEmpty()) { model.play(tracks, 0); model.message("Playing all tracks") }
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(DiscIcons.Play, null, Modifier.size(16.dp), tint = disc.text)
                        Text("Play all", color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeView(model: AppModel, state: PlaybackState, spin: DiscSpin, narrow: Boolean) {
    val current = state.current
    val track = rememberTrackIndex(model)[current?.id]
    val art by model.artwork.rememberArtwork(current?.location, 720)
    val discSize = if (narrow) 280.dp else 360.dp
    val discPart: @Composable () -> Unit = {
        Box(Modifier.size(discSize * 1.3f), contentAlignment = Alignment.Center) {
            val glow = disc.accent.copy(alpha = 0.18f)
            Box(Modifier.size(discSize * 1.28f).drawBehind { drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent))) })
            MarqueeRing(discSize * 424f / 360f, running = true)
            Disc(
                discSize, spin, art,
                Modifier.clip(CircleShape).clickable(onClickLabel = if (state.isPlaying) "Pause" else "Play", onClick = model::togglePlay),
            )
        }
    }
    val align = if (narrow) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (narrow) TextAlign.Center else TextAlign.Start
    val copy: @Composable () -> Unit = {
        Column(horizontalAlignment = align) {
            Eyebrow("Now playing")
            Text(
                current?.title ?: "Nothing playing", Modifier.padding(top = 7.dp), color = disc.text, fontSize = 44.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.045).em, textAlign = textAlign, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Text(current?.artist ?: "Unknown artist", Modifier.padding(top = 9.dp), color = disc.text.copy(alpha = 0.88f), fontSize = 15.sp)
            Text(listOfNotNull(current?.album, track?.year?.toString()).joinToString("  ·  "), Modifier.padding(top = 5.dp), color = disc.muted, fontSize = 12.sp)
            FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                track?.genre?.let { Chip(it, accent = true) }
                track?.format?.let { Chip(it.uppercase()) }
                Chip("Local file")
            }
            FlowRow(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(disc.accent).clickable(onClick = model::togglePlay).padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(if (state.isPlaying) DiscIcons.Pause else DiscIcons.Play, null, Modifier.size(16.dp), tint = disc.accentInk)
                    Text(if (state.isPlaying) "Pause" else "Play", color = disc.accentInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                val editSong = LocalEditSong.current
                ActionButton(DiscIcons.Edit, "Edit metadata", enabled = track != null) { track?.let(editSong) }
                SoonButton(DiscIcons.Lyrics, "Lyrics") { model.message(SOON) }
            }
            Text("CLICK THE DISC TO PLAY OR PAUSE", Modifier.padding(top = 18.dp), color = disc.muted, fontSize = 10.sp, letterSpacing = 0.08.em)
        }
    }
    if (narrow) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            discPart()
            copy()
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(Modifier.weight(1.05f), contentAlignment = Alignment.Center) { discPart() }
            Box(Modifier.weight(0.95f)) { copy() }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(12.dp)).border(1.dp, disc.line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = disc.text)
        Text(label, color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SoonButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.alpha(0.45f).clip(RoundedCornerShape(12.dp)).border(1.dp, disc.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = disc.text)
        Text(label, color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        SoonTag()
    }
}

@Composable
private fun Pill(text: String) = Text(
    text, Modifier.clip(RoundedCornerShape(50)).border(1.dp, disc.line, RoundedCornerShape(50)).background(disc.tint.copy(alpha = 0.025f))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    color = disc.muted, fontSize = 11.sp,
)

@Composable
private fun Chip(text: String, accent: Boolean = false) = Text(
    text.uppercase(),
    Modifier.clip(RoundedCornerShape(50)).background(if (accent) disc.soft else disc.tint.copy(alpha = 0.025f))
        .border(1.dp, if (accent) disc.accent.copy(alpha = 0.28f) else disc.line, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp),
    color = if (accent) disc.accentReadable else disc.muted, fontSize = 10.sp, letterSpacing = 0.06.em,
)

@Composable
private fun QueuePanel(model: AppModel, state: PlaybackState) {
    val track = rememberTrackIndex(model)[state.current?.id]
    val divider = disc.line
    Column(
        Modifier.width(332.dp).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(disc.queueTop, disc.queueBottom)))
            .drawBehind { drawLine(divider, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, size.height)) },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", Modifier.weight(1f), color = disc.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(plural(state.queue.size, "track").uppercase(), color = disc.muted, fontSize = 10.sp, letterSpacing = 0.1.em)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            itemsIndexed(state.queue, key = { i, item -> "$i-${item.id}" }) { i, item ->
                val isCurrent = i == state.index
                val bar = disc.accentReadable
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.5.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (isCurrent) disc.soft else Color.Transparent)
                        .drawBehind {
                            // The current song's accent bar on the left edge.
                            if (isCurrent) drawRoundRect(bar, androidx.compose.ui.geometry.Offset(0f, size.height / 2 - 12.dp.toPx()), androidx.compose.ui.geometry.Size(3.dp.toPx(), 24.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        }
                        .clickable(onClickLabel = "Play ${item.title}") { model.player.skipTo(i) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ArtThumb(model.artwork, item.location, 40.dp, 9.dp)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = if (isCurrent) disc.accentReadable else disc.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(item.artist ?: "Unknown artist", item.album).joinToString(" · "), color = disc.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatTime(item.durationMs), color = disc.muted, fontSize = 10.sp)
                }
            }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .border(1.dp, disc.line, RoundedCornerShape(16.dp)).background(disc.tint.copy(alpha = 0.02f)).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("TRACK DETAILS", Modifier.padding(bottom = 3.dp), color = disc.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em)
            DetailRow("Album", track?.album)
            DetailRow("Year", track?.year?.toString())
            DetailRow("Genre", track?.genre)
            DetailRow("Source", "Local file")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(76.dp), color = disc.muted, fontSize = 11.sp)
        Text(value ?: "—", Modifier.weight(1f), color = disc.text, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Dock(model: AppModel, state: PlaybackState, narrow: Boolean, queueOpen: Boolean, onToggleQueue: (() -> Unit)?, onOpenHome: () -> Unit) {
    val divider = disc.line
    val current = state.current
    Row(
        Modifier.fillMaxWidth().height(86.dp).background(disc.dock)
            .drawBehind { drawLine(divider, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f)) }
            .padding(horizontal = if (narrow) 14.dp else 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 20.dp),
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Open now playing", onClick = onOpenHome),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArtThumb(model.artwork, current?.location, 46.dp, 10.dp)
            Column {
                Text(current?.title ?: "Nothing playing", color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current?.artist ?: "", color = disc.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(if (narrow) 1.6f else 2.2f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                CtrlButton(DiscIcons.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", 32.dp, 17.dp, { model.player.setShuffle(!state.shuffle) }, active = state.shuffle)
                CtrlButton(DiscIcons.Prev, "Previous", 32.dp, 17.dp, model.player::previous)
                PlayButton(state.isPlaying, 38.dp, 18.dp, model::togglePlay)
                CtrlButton(DiscIcons.Next, "Next", 32.dp, 17.dp, model.player::next)
                CtrlButton(
                    DiscIcons.Repeat, state.repeatMode.label(), 32.dp, 17.dp, { model.player.setRepeatMode(state.repeatMode.next()) },
                    active = state.repeatMode != RepeatMode.Off, badge = if (state.repeatMode == RepeatMode.One) "1" else null,
                )
            }
            SeekRow(model, state, textSize = 10, trackHeight = 3.dp)
        }
        Row(
            Modifier.then(if (narrow) Modifier else Modifier.weight(1f)),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onToggleQueue != null) {
                CtrlButton(DiscIcons.Queue, if (queueOpen) "Hide queue" else "Show queue", 32.dp, 17.dp, onToggleQueue, active = queueOpen)
            }
            if (!narrow) {
                Icon(DiscIcons.Volume, null, Modifier.size(16.dp), tint = disc.muted)
                ThinSlider(state.volume, model.player::setVolume, "Volume", Modifier.width(78.dp), trackHeight = 3.dp)
            }
        }
    }
}
