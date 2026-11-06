package dev.recorddisc.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import dev.recorddisc.player.app.OpenedAs
import dev.recorddisc.player.library.isOutside
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

enum class PhoneScreen { Home, NowPlaying, Library, Playlists, Settings, Inbox, EditTags, EditLyrics, SongDetails }

/**
 * Which way a page change slides. Pages move opposite to the finger (2026-11-04 mockups): a
 * Forward change (finger right) brings the new page in from the right and pushes the old one
 * out to the left; Back (finger left, the Back button) does the reverse. Taps use None.
 */
enum class NavDirection { Forward, Back, None }

/**
 * Where a swipe goes: right moves forward through Home, Library, Playlists, Settings, Inbox;
 * left goes back. On Now Playing and Song details a left swipe returns to [nowPlayingReturn] /
 * [detailsReturn] (the caller works these out from where the song was chosen).
 */
internal fun phoneSwipeDestination(
    screen: PhoneScreen,
    swipeRight: Boolean,
    nowPlayingReturn: PhoneScreen = PhoneScreen.Home,
    detailsReturn: PhoneScreen = PhoneScreen.Home,
): PhoneScreen? = when (screen) {
    PhoneScreen.Home -> if (swipeRight) PhoneScreen.Library else null
    PhoneScreen.Library -> if (swipeRight) PhoneScreen.Playlists else PhoneScreen.Home
    PhoneScreen.Playlists -> if (swipeRight) PhoneScreen.Settings else PhoneScreen.Library
    PhoneScreen.Settings -> if (swipeRight) PhoneScreen.Inbox else PhoneScreen.Playlists
    PhoneScreen.Inbox -> if (swipeRight) null else PhoneScreen.Settings
    PhoneScreen.NowPlaying -> if (swipeRight) null else nowPlayingReturn
    PhoneScreen.SongDetails -> if (swipeRight) null else detailsReturn
    PhoneScreen.EditTags, PhoneScreen.EditLyrics -> null
}

/** The screen Now Playing returns to for a play source: its playlist or Library page, or Home. */
internal fun nowPlayingReturnScreen(source: PlaySource?): PhoneScreen = when (source) {
    null -> PhoneScreen.Home
    is PlaySource.Playlist -> PhoneScreen.Playlists
    else -> PhoneScreen.Library
}

/** The Android layout: the disc is home; Library, Playlists and Settings sit on its arc. */
@Composable
fun PhoneApp(
    model: AppModel,
    onChooseFolder: () -> Unit,
    openRequests: Flow<PhoneScreen> = emptyFlow(),
    /** Files opened from a file manager ("Open with"), once each has started playing. */
    openedFiles: Flow<OpenedAs> = emptyFlow(),
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
    // The previous song's colour stays until the new cover's colour is ready, so the accent fades
    // straight from one cover to the next instead of flashing the preset colour in between.
    val coverAccent by produceState<Color?>(null, settings?.dynamicColor, currentLocation, artworkRevision) {
        value = if (settings?.dynamicColor == true && currentLocation != null) {
            model.artwork.get(currentLocation, maxSizePx = 64)?.let(::representativeCoverAccent)
        } else {
            null
        }
    }
    val accent = if (settings?.dynamicColor == true) {
        coverAccent ?: Color(settings.accentArgb)
    } else {
        Color(settings?.accentArgb ?: 0xFFE0A458)
    }
    val light = settings?.useLightTheme() == true
    SystemBarsEffect(light)
    DiscTheme(accent, light, batterySaver = settings?.batterySaver == true, spectrum = model.player.spectrum) {
        Box(Modifier.fillMaxSize().background(disc.panel).safeDrawingPadding()) {
            if (settings == null) return@Box
            val scan by model.scan.collectAsState()
            val spin = rememberDiscSpin(state.isPlaying || scan is ScanState.Running, periodMs = 14_000f)
            if (!settings.setupDone) {
                PhoneWelcome(scan, spin, model.platform::folderLabel, onChooseFolder, model::cancelScan) { model.finishSetup() }
            } else {
                PhoneScreens(model, state, spin, openRequests, openedFiles, onChooseFolder, editor, playlistActions)
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
    openedFiles: Flow<OpenedAs>,
    onChooseFolder: () -> Unit,
    editor: EditorActions,
    playlistActions: PlaylistFileActions,
) {
    var screen by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    var navDirection by rememberSaveable { mutableStateOf(NavDirection.None) }
    var inboxReturn by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    // The Library's open page and the open playlist live here, so Now Playing can return to them.
    val libraryNav = rememberSaveable(saver = LibraryNav.Saver) { LibraryNav() }
    var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var playSource by rememberSaveable(stateSaver = PlaySource.Saver) { mutableStateOf<PlaySource?>(null) }
    // Song details: the song, the screen it was opened from, and where that song sits (for Play).
    var detailsId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailsReturn by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    var detailsSource by rememberSaveable(stateSaver = PlaySource.Saver) { mutableStateOf<PlaySource?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var progressDialogTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var hiddenProgressTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var observedProgressTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    // The song being edited, and the screen the editor goes back to.
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editReturn by rememberSaveable { mutableStateOf(PhoneScreen.Home) }
    var lyricsId by rememberSaveable { mutableStateOf<Long?>(null) }
    val exportTask by model.playlistBundleExportTask.collectAsState()
    fun go(destination: PhoneScreen, direction: NavDirection = NavDirection.None) {
        navDirection = direction
        screen = destination
    }
    // Arriving at Library or Playlists from the arc, a swipe or a notification starts at the top
    // level, as before; returning from Now Playing, an editor or Song details keeps the open page.
    fun enterFresh(destination: PhoneScreen, direction: NavDirection = NavDirection.None) {
        if (destination == PhoneScreen.Library) libraryNav.show()
        if (destination == PhoneScreen.Playlists) openPlaylistId = null
        if (destination == PhoneScreen.Inbox) inboxReturn = screen
        go(destination, direction)
    }
    val closeEditor = { go(editReturn) }
    val nowPlayingReturn = nowPlayingReturnScreen(playSource)
    /** Leaves Now Playing for the page the playing song was last chosen from. */
    fun closeNowPlaying(direction: NavDirection) {
        when (val source = playSource) {
            null -> Unit
            PlaySource.Library -> libraryNav.show()
            is PlaySource.Album -> libraryNav.show(LibraryPage.AlbumPage(source.key))
            is PlaySource.Artist -> libraryNav.show(LibraryPage.ArtistPage(source.name))
            // A deleted playlist falls back to the Playlists list.
            is PlaySource.Playlist -> openPlaylistId = source.id.takeIf { id -> model.playlists.value.any { it.id == id } }
        }
        go(nowPlayingReturn, direction)
    }
    /** The play source for the page on screen now (where a song shown from here sits). */
    fun sourceHere(): PlaySource? = when (screen) {
        PhoneScreen.Library -> when (val page = libraryNav.page) {
            null -> PlaySource.Library
            is LibraryPage.AlbumPage -> PlaySource.Album(page.key)
            is LibraryPage.ArtistPage -> PlaySource.Artist(page.name)
        }
        PhoneScreen.Playlists -> openPlaylistId?.let(PlaySource::Playlist)
        else -> null
    }
    fun openScreen(destination: PhoneScreen) {
        if (destination == PhoneScreen.NowPlaying) go(PhoneScreen.NowPlaying) else enterFresh(destination)
    }
    // Notification taps always have Home as their return destination.
    LaunchedEffect(openRequests) {
        openRequests.collect {
            when (it) {
                PhoneScreen.NowPlaying -> go(PhoneScreen.NowPlaying)
                PhoneScreen.Inbox -> { inboxReturn = PhoneScreen.Home; go(PhoneScreen.Inbox) }
                else -> enterFresh(it)
            }
        }
    }
    // A file opened from a file manager shows on Now Playing. A library song counts as chosen in
    // Library; leaving Now Playing for any other file goes Home.
    LaunchedEffect(openedFiles) {
        openedFiles.collect { opened ->
            playSource = if (opened == OpenedAs.LibrarySong) PlaySource.Library else null
            go(PhoneScreen.NowPlaying)
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
    // The lyrics editor handles Back itself (it asks before discarding changes). Back slides like a
    // left swipe. Library and playlist pages close their own open page first.
    PlatformBackHandler(enabled = screen != PhoneScreen.Home && screen != PhoneScreen.EditLyrics) {
        when (screen) {
            PhoneScreen.EditTags -> go(editReturn, NavDirection.Back)
            PhoneScreen.NowPlaying -> closeNowPlaying(NavDirection.Back)
            PhoneScreen.SongDetails -> go(detailsReturn, NavDirection.Back)
            PhoneScreen.Inbox -> go(inboxReturn, NavDirection.Back)
            else -> go(PhoneScreen.Home, NavDirection.Back)
        }
    }
    val art by model.artwork.rememberArtwork(state.current?.location, 640)
    val tracks = rememberTrackIndex(model)
    val reduceMotion = rememberStill()
    // View Details: Now Playing for the playing song, otherwise its Song details.
    val viewDetails: (TrackEntity) -> Unit = { t ->
        if (t.id == state.current?.id) {
            go(PhoneScreen.NowPlaying)
        } else {
            if (screen != PhoneScreen.SongDetails) {
                detailsReturn = screen
                detailsSource = sourceHere()
            }
            detailsId = t.id
            go(PhoneScreen.SongDetails)
        }
    }
    PlaylistHost(model) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalEditLyrics provides { t -> lyricsId = t.id; editReturn = screen; go(PhoneScreen.EditLyrics) },
        LocalViewDetails provides viewDetails,
    ) {
    ProvideEditSong({ t -> editingId = t.id; editReturn = screen; go(PhoneScreen.EditTags) }) {
        AnimatedContent(
            screen,
            transitionSpec = {
                val step = { full: Int -> full / 12 }
                when {
                    reduceMotion -> fadeIn() togetherWith fadeOut()
                    navDirection == NavDirection.Forward ->
                        (fadeIn() + slideInHorizontally { step(it) }) togetherWith (fadeOut() + slideOutHorizontally { -step(it) })
                    navDirection == NavDirection.Back ->
                        (fadeIn() + slideInHorizontally { -step(it) }) togetherWith (fadeOut() + slideOutHorizontally { step(it) })
                    else -> (fadeIn() + slideInHorizontally { step(it) }) togetherWith fadeOut()
                }
            },
            modifier = Modifier.fillMaxSize()
                .swipeHorizontally { swipeRight ->
                    val direction = if (swipeRight) NavDirection.Forward else NavDirection.Back
                    phoneSwipeDestination(screen, swipeRight, nowPlayingReturn, detailsReturn)?.let {
                        when (screen) {
                            PhoneScreen.NowPlaying -> closeNowPlaying(direction)
                            PhoneScreen.SongDetails -> go(it, direction)
                            else -> enterFresh(it, direction)
                        }
                    }
                },
        ) { shown ->
            when (shown) {
                PhoneScreen.Home -> PhoneHome(spin, art, onOpen = ::openScreen)
                PhoneScreen.NowPlaying -> NowPlaying(
                    model, state, spin, art, returnLabel = returnLabel(model, playSource),
                    onClose = { closeNowPlaying(NavDirection.None) },
                )
                PhoneScreen.Library -> PhoneLibrary(model, libraryNav, onBack = { go(PhoneScreen.Home) }, onPlayed = { playSource = it })
                PhoneScreen.Playlists -> PhonePlaylists(
                    model,
                    playlistActions,
                    selectedId = openPlaylistId,
                    onSelect = { openPlaylistId = it },
                    onPlayed = { playSource = PlaySource.Playlist(it) },
                    onBack = { go(PhoneScreen.Home) },
                    onOpenLibrary = { enterFresh(PhoneScreen.Library) },
                )
                PhoneScreen.SongDetails -> {
                    val track = tracks[detailsId]
                    if (track == null) {
                        LaunchedEffect(Unit) { go(detailsReturn) }
                    } else {
                        PhoneSongDetails(
                            model, track, state.current?.title, state.isPlaying,
                            returnLabel = when (detailsReturn) {
                                PhoneScreen.Playlists -> if (openPlaylistId != null) "playlist" else "playlists"
                                else -> detailsReturn.name.lowercase()
                            },
                            onClose = { go(detailsReturn) },
                            onPlay = {
                                playFromDetails(model, track, detailsSource)
                                playSource = detailsSource ?: PlaySource.Library
                                go(PhoneScreen.NowPlaying)
                            },
                        )
                    }
                }
                PhoneScreen.Settings -> PhoneSettings(model, onBack = { go(PhoneScreen.Home) }, onChooseFolder, onCustomAccent = { picking = true })
                PhoneScreen.Inbox -> PhoneInbox(
                    model,
                    onBack = { go(inboxReturn) },
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
        MiniPlayer(model, state, Modifier.align(Alignment.BottomCenter)) { go(PhoneScreen.NowPlaying) }
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

/** Where Now Playing's footer says a left swipe goes: the playlist, album or artist name, "library" or "home". */
private fun returnLabel(model: AppModel, source: PlaySource?): String = when (source) {
    null -> "home"
    PlaySource.Library -> "library"
    is PlaySource.Album -> model.albums.value.firstOrNull { it.key == source.key }?.name ?: "library"
    is PlaySource.Artist -> source.name
    is PlaySource.Playlist -> model.playlists.value.firstOrNull { it.id == source.id }?.name ?: "playlists"
}

@Composable
private fun NowPlaying(
    model: AppModel,
    state: PlaybackState,
    spin: DiscSpin,
    art: androidx.compose.ui.graphics.ImageBitmap?,
    returnLabel: String,
    onClose: () -> Unit,
) {
    val current = state.current
    val track = rememberTrackIndex(model)[current?.id]
    var menu by remember { mutableStateOf(false) }
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
                    SongMenu(menu, { menu = false }, track, showDetails = false)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
            Disc(208.dp, spin, art)
        }
        Text(current?.title ?: "Nothing playing", color = disc.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.025).em, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(current?.artist ?: "Unknown artist", Modifier.padding(top = 5.dp).alpha(0.85f), color = disc.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val albumLine = listOfNotNull(current?.album, track?.year?.toString()).joinToString(" · ")
        if (albumLine.isNotEmpty()) Text(albumLine, Modifier.padding(top = 3.dp), color = disc.muted, fontSize = 12.sp, maxLines = 1)
        if (track?.isOutside == true) OutsideFileLabel(Modifier.padding(top = 8.dp))
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
            "SWIPE LEFT TO RETURN TO ${returnLabel.uppercase()}", Modifier.fillMaxWidth(), color = disc.muted, fontSize = 10.sp, letterSpacing = 0.14.em,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * The library by ID, for details the queue doesn't carry (year, genre, format). A file opened from
 * a file manager that isn't in the library is included while it's in the queue.
 */
@Composable
fun rememberTrackIndex(model: AppModel): Map<Long, dev.recorddisc.player.library.TrackEntity> {
    val tracks by model.tracks.collectAsState()
    val outside by model.outsideTrack.collectAsState()
    return remember(tracks, outside) { tracks.associateBy { it.id } + listOfNotNull(outside).associateBy { it.id } }
}

/** The "Opened from Files · not in your library" pill under a file that isn't in the library. */
@Composable
fun OutsideFileLabel(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(disc.tint.copy(alpha = 0.07f)).border(1.dp, disc.line, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(DiscIcons.Folder, null, Modifier.size(13.dp), tint = disc.accentReadable)
        Text(OUTSIDE_FILE_LABEL, color = disc.accentReadable, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
