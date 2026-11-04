package dev.recorddisc.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.ScanState
import dev.recorddisc.player.app.rememberArtwork
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

enum class PhoneScreen { Home, NowPlaying, Library, Playlists, Settings, Inbox, EditTags, EditLyrics }

internal fun phoneSwipeDestination(
    screen: PhoneScreen,
    swipeRight: Boolean,
    nowPlayingReturn: PhoneScreen = PhoneScreen.Home,
): PhoneScreen? = when (screen) {
    PhoneScreen.Home -> if (swipeRight) PhoneScreen.Library else null
    PhoneScreen.Library -> if (swipeRight) PhoneScreen.Playlists else PhoneScreen.Home
    PhoneScreen.Playlists -> if (swipeRight) PhoneScreen.Settings else PhoneScreen.Library
    PhoneScreen.Settings -> if (swipeRight) PhoneScreen.Inbox else PhoneScreen.Playlists
    PhoneScreen.Inbox -> if (swipeRight) null else PhoneScreen.Settings
    PhoneScreen.NowPlaying -> if (swipeRight) null else nowPlayingReturn.takeIf { it in setOf(PhoneScreen.Library, PhoneScreen.Playlists) } ?: PhoneScreen.Home
    PhoneScreen.EditTags, PhoneScreen.EditLyrics -> null
}

/** The Android layout: the disc is home; Library, Playlists and Settings sit on its arc. */
@Composable
fun PhoneApp(
    model: AppModel,
    onChooseFolder: () -> Unit,
    openRequests: Flow<PhoneScreen> = emptyFlow(),
    editor: EditorActions = EditorActions(),
    playlistActions: PlaylistFileActions = PlaylistFileActions(),
) {
    val settings = model.settings.collectAsState().value
    val state by model.player.state.collectAsState()
    val toastMessages = remember(model) {
        merge(
            model.messages,
            model.player.state.mapNotNull { it.error }.distinctUntilChanged(),
        )
    }
    val artworkRevision by model.artwork.revision.collectAsState()
    val currentLocation = state.current?.location
    val coverAccent by produceState<Color?>(null, settings?.dynamicColor, currentLocation, artworkRevision) {
        value = null
        if (settings?.dynamicColor == true && currentLocation != null) {
            value = model.artwork.get(currentLocation, maxSizePx = 64)?.let(::representativeCoverAccent)
        }
    }
    val accent = if (settings?.dynamicColor == true) {
        coverAccent ?: Color(settings.accentArgb)
    } else {
        Color(settings?.accentArgb ?: 0xFFE0A458)
    }
    val light = settings?.useLightTheme() == true
    SystemBarsEffect(light)
    DiscTheme(accent, light) {
        Box(Modifier.fillMaxSize().background(disc.panel).safeDrawingPadding()) {
            if (settings == null) return@Box
            val scan by model.scan.collectAsState()
            val spin = rememberDiscSpin(state.isPlaying || scan is ScanState.Running, periodMs = 14_000f)
            if (!settings.setupDone) {
                PhoneWelcome(scan, spin, model.platform::folderLabel, onChooseFolder, model::cancelScan) { model.finishSetup() }
            } else {
                PhoneScreens(model, state, spin, openRequests, onChooseFolder, editor, playlistActions)
            }
            MessageToast(toastMessages, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
        }
    }
}

@Composable
private fun BoxScope.PhoneScreens(
    model: AppModel,
    state: PlaybackState,
    spin: DiscSpin,
    openRequests: Flow<PhoneScreen>,
    onChooseFolder: () -> Unit,
    editor: EditorActions,
    playlistActions: PlaylistFileActions,
) {
    var screen by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    var nowPlayingReturn by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    var inboxReturn by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var progressDialogTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var hiddenProgressTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var observedProgressTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    // The song being edited, and the screen the editor goes back to.
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editReturn by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    var lyricsId by rememberSaveable { mutableStateOf<Long?>(null) }
    val exportTask by model.playlistBundleExportTask.collectAsState()
    val closeEditor = { screen = editReturn }
    fun openNowPlaying(returnTo: PhoneScreen) {
        nowPlayingReturn = returnTo.takeIf { it == PhoneScreen.Library || it == PhoneScreen.Playlists } ?: PhoneScreen.Home
        screen = PhoneScreen.NowPlaying
    }
    fun openScreen(destination: PhoneScreen) {
        when (destination) {
            PhoneScreen.NowPlaying -> openNowPlaying(PhoneScreen.Home)
            PhoneScreen.Inbox -> { inboxReturn = screen; screen = PhoneScreen.Inbox }
            else -> screen = destination
        }
    }
    // Notification taps always have Home as their return destination.
    LaunchedEffect(openRequests) {
        openRequests.collect {
            when (it) {
                PhoneScreen.NowPlaying -> openNowPlaying(PhoneScreen.Home)
                PhoneScreen.Inbox -> { inboxReturn = PhoneScreen.Home; screen = PhoneScreen.Inbox }
                else -> screen = it
            }
        }
    }
    LaunchedEffect(exportTask?.id) {
        exportTask?.takeIf {
            it.status == dev.recorddisc.player.app.PlaylistBundleExportStatus.Running && it.id != observedProgressTaskId
        }?.let {
            observedProgressTaskId = it.id
            progressDialogTaskId = it.id
            hiddenProgressTaskId = null
        }
    }
    // The lyrics editor handles Back itself (it asks before discarding changes).
    PlatformBackHandler(enabled = screen != PhoneScreen.Home && screen != PhoneScreen.EditLyrics) {
        when (screen) {
            PhoneScreen.EditTags -> closeEditor()
            PhoneScreen.NowPlaying -> screen = nowPlayingReturn
            PhoneScreen.Inbox -> screen = inboxReturn
            else -> screen = PhoneScreen.Home
        }
    }
    val art by model.artwork.rememberArtwork(state.current?.location, 640)
    val tracks = rememberTrackIndex(model)
    PlaylistHost(model) {
    androidx.compose.runtime.CompositionLocalProvider(LocalEditLyrics provides { t -> lyricsId = t.id; editReturn = screen; screen = PhoneScreen.EditLyrics }) {
    ProvideEditSong({ t -> editingId = t.id; editReturn = screen; screen = PhoneScreen.EditTags }) {
        AnimatedContent(
            screen,
            transitionSpec = { (fadeIn() + slideInHorizontally { it / 12 }) togetherWith fadeOut() },
            modifier = Modifier.fillMaxSize()
                .swipeHorizontally { swipeRight ->
                    phoneSwipeDestination(screen, swipeRight, nowPlayingReturn)?.let {
                        if (it == PhoneScreen.Inbox) inboxReturn = screen
                        screen = it
                    }
                },
        ) { shown ->
            when (shown) {
                PhoneScreen.Home -> PhoneHome(spin, art, onOpen = ::openScreen)
                PhoneScreen.NowPlaying -> NowPlaying(model, state, spin, art, onClose = { screen = nowPlayingReturn })
                PhoneScreen.Library -> PhoneLibrary(model, onBack = { screen = PhoneScreen.Home }, onPlayed = { openNowPlaying(PhoneScreen.Library) })
                PhoneScreen.Playlists -> PhonePlaylists(
                    model,
                    playlistActions,
                    onBack = { screen = PhoneScreen.Home },
                    onOpenLibrary = { screen = PhoneScreen.Library },
                )
                PhoneScreen.Settings -> PhoneSettings(model, onBack = { screen = PhoneScreen.Home }, onChooseFolder, onCustomAccent = { picking = true })
                PhoneScreen.Inbox -> PhoneInbox(
                    model,
                    onBack = { screen = inboxReturn },
                    onOpenProgress = { exportTask?.let { progressDialogTaskId = it.id; hiddenProgressTaskId = null } },
                    onCancelExport = model::cancelPlaylistBundleExport,
                )
                PhoneScreen.EditTags -> {
                    val track = tracks[editingId]
                    if (track == null) LaunchedEffect(Unit) { closeEditor() } else PhoneTagEditor(model, track, editor, onClose = closeEditor)
                }
                PhoneScreen.EditLyrics -> Box(Modifier.fillMaxSize()) {
                    val track = tracks[lyricsId]
                    if (track == null) LaunchedEffect(Unit) { closeEditor() } else PhoneLyricsEditor(model, track, state, editor, onClose = closeEditor)
                }
            }
        }
    }
    }
    }
    exportTask?.takeIf {
        it.status == dev.recorddisc.player.app.PlaylistBundleExportStatus.Running &&
            it.id == progressDialogTaskId && it.id != hiddenProgressTaskId
    }?.let { task ->
        PlaylistBundleExportProgressDialog(
            task,
            onHide = { hiddenProgressTaskId = task.id },
            onCancel = model::cancelPlaylistBundleExport,
        )
    }
    if (screen in setOf(PhoneScreen.Library, PhoneScreen.Playlists, PhoneScreen.Settings, PhoneScreen.Inbox) && state.current != null) {
        MiniPlayer(model, state, Modifier.align(Alignment.BottomCenter)) { openNowPlaying(screen) }
    }
    if (picking) {
        PlatformBackHandler(enabled = true) { picking = false }
        val start = model.settings.collectAsState().value?.customAccent ?: 0xFFE0A458
        AccentPicker(start, onCancel = { picking = false }) {
            model.setCustomAccent(it)
            picking = false
        }
    }
}

/** Calls [onSwipe] after a horizontal swipe of at least 80 dp. */
private fun Modifier.swipeHorizontally(onSwipe: (swipeRight: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            when {
                total < -80.dp.toPx() -> onSwipe(false)
                total > 80.dp.toPx() -> onSwipe(true)
            }
        },
    ) { _, dx -> total += dx }
}

@Composable
private fun PhoneHome(spin: DiscSpin, art: androidx.compose.ui.graphics.ImageBitmap?, onOpen: (PhoneScreen) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        PhoneTopRow()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // A 320 dp stage like the mockup: the disc sits 34 dp left of centre, the arc hugs its right edge.
            Box(Modifier.size(320.dp)) {
                MarqueeArc(320.dp, running = true, modifier = Modifier.offset(x = (-34).dp))
                Disc(
                    222.dp, spin, art,
                    Modifier.align(Alignment.Center).offset(x = (-34).dp).clip(CircleShape)
                        .clickable(onClickLabel = "Open now playing") { onOpen(PhoneScreen.NowPlaying) }
                        .semantics { contentDescription = "Open now playing" },
                )
                ArcButton("Library", 212.3.dp, 73.7.dp, 7f) { onOpen(PhoneScreen.Library) }
                ArcButton("Playlists", 241.dp, 124.dp, 3f) { onOpen(PhoneScreen.Playlists) }
                ArcButton("Settings", 241.dp, 196.dp, -3f) { onOpen(PhoneScreen.Settings) }
                ArcButton("Inbox", 196.dp, 259.dp, 0f) { onOpen(PhoneScreen.Inbox) }
            }
        }
        Text(
            "TAP THE DISC FOR NOW PLAYING", Modifier.fillMaxWidth().padding(top = 8.dp),
            color = disc.muted, fontSize = 11.sp, letterSpacing = 0.12.em, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** A destination on the arc: a glowing dot and a label, its left edge vertically centred at ([x], [y]). */
@Composable
private fun ArcButton(label: String, x: Dp, y: Dp, rotation: Float, onClick: () -> Unit) {
    Box(
        Modifier.offset(x, y)
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                layout(p.width, p.height) { p.place(0, -p.height / 2) }
            }
            .graphicsLayer { rotationZ = rotation; transformOrigin = TransformOrigin(0f, 0.5f) }
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
    ) {
        Box(
            Modifier.offset(x = (-17).dp).align(Alignment.CenterStart).size(6.dp).clip(CircleShape)
                .background(disc.accentReadable).border(3.dp, disc.accent.copy(alpha = 0.12f), CircleShape),
        )
        Text(label.uppercase(), color = disc.text, fontSize = 12.sp, letterSpacing = 0.12.em)
    }
}

@Composable
private fun NowPlaying(model: AppModel, state: PlaybackState, spin: DiscSpin, art: androidx.compose.ui.graphics.ImageBitmap?, onClose: () -> Unit) {
    val current = state.current
    val track = rememberTrackIndex(model)[current?.id]
    var menu by remember { mutableStateOf(false) }
    var viewFileTrack by remember { mutableStateOf<TrackEntity?>(null) }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton("✕", "Close now playing", onClose)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val showLyrics = model.settings.collectAsState().value?.showLyrics == true
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (showLyrics) disc.soft else disc.tint.copy(alpha = 0.03f))
                        .border(1.dp, if (showLyrics) disc.accent.copy(alpha = 0.4f) else disc.line, RoundedCornerShape(50))
                        .clickable(onClickLabel = if (showLyrics) "Hide lyrics" else "Show lyrics") { model.setShowLyrics(!showLyrics) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val tint = if (showLyrics) disc.accentReadable else disc.text
                    Icon(DiscIcons.Lyrics, null, Modifier.size(16.dp), tint = tint)
                    Text("Lyrics", color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Box {
                    CircleIconButton("⋮", "More options") { menu = true }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        val editSong = LocalEditSong.current
                        val editLyrics = LocalEditLyrics.current
                        val addToPlaylist = LocalAddToPlaylist.current
                        for (item in listOf("Edit lyrics", "Edit metadata", "Add to playlist", "View file location")) {
                            val ready = track != null
                            DropdownMenuItem(
                                text = { Text(item, color = if (ready) disc.text else disc.muted) },
                                trailingIcon = { if (!ready) SoonTag() },
                                onClick = {
                                    menu = false
                                    when {
                                        !ready -> Unit
                                        item == "Add to playlist" -> addToPlaylist(track!!)
                                        item == "Edit lyrics" -> editLyrics(track!!)
                                        item == "Edit metadata" -> editSong(track!!)
                                        item == "View file location" -> viewFileTrack = track
                                    }
                                },
                            )
                        }
                    }
                viewFileTrack?.let { ft ->
                    viewFileTrack = null
                    viewFileLocation(ft.location, ft.folder)
                }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
            Disc(208.dp, spin, art)
        }
        Text(current?.title ?: "Nothing playing", color = disc.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.025).em, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(current?.artist ?: "Unknown artist", Modifier.padding(top = 5.dp).alpha(0.85f), color = disc.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(current?.album, track?.year?.toString()).joinToString(" · "), Modifier.padding(top = 3.dp), color = disc.muted, fontSize = 12.sp, maxLines = 1)
        SeekRow(model, state, Modifier.padding(top = 20.dp, bottom = 4.dp), textSize = 11)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CtrlButton(DiscIcons.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", 44.dp, 22.dp, { model.setShuffle(!state.shuffle) }, active = state.shuffle)
            CtrlButton(DiscIcons.Prev, "Previous", 44.dp, 22.dp, model.player::previous)
            PlayButton(state.isPlaying, 62.dp, 28.dp, model::togglePlay)
            CtrlButton(DiscIcons.Next, "Next", 44.dp, 22.dp, model.player::next)
            CtrlButton(
                DiscIcons.Repeat, state.repeatMode.label(), 44.dp, 22.dp, { model.setRepeatMode(state.repeatMode.next()) },
                active = state.repeatMode != RepeatMode.Off, badge = if (state.repeatMode == RepeatMode.One) "1" else null,
            )
        }
        if (model.settings.collectAsState().value?.showLyrics == true) {
            LyricsPanel(model, track, state, windowHeight = 104.dp, textSize = 14, modifier = Modifier.padding(top = 14.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            "SWIPE LEFT TO CLOSE", Modifier.fillMaxWidth(), color = disc.muted, fontSize = 10.sp, letterSpacing = 0.14.em,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** The library by ID, for details the queue doesn't carry (year, genre, format). */
@Composable
fun rememberTrackIndex(model: AppModel): Map<Long, dev.recorddisc.player.library.TrackEntity> {
    val tracks by model.tracks.collectAsState()
    return remember(tracks) { tracks.associateBy { it.id } }
}

/** Elapsed time, the seek bar and the length. */
@Composable
fun SeekRow(model: AppModel, state: PlaybackState, modifier: Modifier = Modifier, textSize: Int = 11, trackHeight: Dp = 4.dp) {
    val duration = state.durationMs
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(formatTime(state.positionMs), color = disc.muted, fontSize = textSize.sp)
        ThinSlider(
            value = if (duration != null && duration > 0) state.positionMs.toFloat() / duration else 0f,
            onCommit = { f -> duration?.let { model.player.seekTo((it * f).toLong()) } },
            label = "Seek",
            modifier = Modifier.weight(1f),
            trackHeight = trackHeight,
        )
        Text(formatTime(duration), color = disc.muted, fontSize = textSize.sp)
    }
}

@Composable
private fun MiniPlayer(model: AppModel, state: PlaybackState, modifier: Modifier, onOpen: () -> Unit) {
    val current = state.current ?: return
    Row(
        modifier.padding(10.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp))
            .background(disc.panel2.copy(alpha = 0.95f)).border(1.dp, disc.line, RoundedCornerShape(18.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArtThumb(model.artwork, current.location, 44.dp, 10.dp)
        Column(Modifier.weight(1f).clickable(onClickLabel = "Open now playing", onClick = onOpen)) {
            Text(current.title, color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(current.artist ?: "Unknown artist", color = disc.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CtrlButton(if (state.isPlaying) DiscIcons.Pause else DiscIcons.Play, if (state.isPlaying) "Pause" else "Play", 36.dp, 20.dp, model::togglePlay)
        CtrlButton(DiscIcons.Next, "Next", 36.dp, 20.dp, model.player::next)
    }
}

/** The 40 dp round header button with a text glyph (✕, ←, ⋮). */
@Composable
fun CircleIconButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(disc.tint.copy(alpha = 0.03f)).border(1.dp, disc.line, CircleShape)
            .clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = disc.text, fontSize = 17.sp)
    }
}

/** Screens of later steps/parts: the real header, with a short note. */
@Composable
private fun Placeholder(title: String, note: String, soon: Boolean = false, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton("←", "Back to disc", onBack)
            Text(title, color = disc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (soon) SoonTag()
        }
        Spacer(Modifier.height(40.dp))
        Text(note, color = disc.muted, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.width(1.dp))
    }
}
