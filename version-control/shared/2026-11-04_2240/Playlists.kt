package dev.recorddisc.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.PlaylistBundleExportProgress
import dev.recorddisc.player.app.PlaylistBundleExportStage
import dev.recorddisc.player.app.PlaylistBundleExportStatus
import dev.recorddisc.player.app.PlaylistBundleExportTask
import dev.recorddisc.player.library.M3uImportPreview
import dev.recorddisc.player.library.M3uPathMode
import dev.recorddisc.player.library.PlaylistBundlePreview
import dev.recorddisc.player.library.PlaylistView
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.displayTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PlaylistCoverFile(val name: String, val bytes: ByteArray)

/** Platform file dialogs; callbacks return null when the user cancels. */
data class PlaylistFileActions(
    val saveM3u: (String, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    val saveBundle: (String, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    val openM3u: ((String?) -> Unit) -> Unit = { done -> done(null) },
    val openBundle: ((String?) -> Unit) -> Unit = { done -> done(null) },
    val chooseCover: ((PlaylistCoverFile?) -> Unit) -> Unit = { done -> done(null) },
    val requestExportNotificationPermission: ((Boolean) -> Unit) -> Unit = { done -> done(false) },
    val startBackgroundBundleExport: ((Long, String, String, Boolean) -> String?)? = null,
    /**
     * TEMPORARY (To-audio-file prototype, 2026-11-04): set only in Android dev builds. Long-pressing
     * the "To audio file · SOON" pill runs the prototype conversion. Remove before the full build.
     */
    val prototypeConvert: ((PlaylistView) -> Unit)? = null,
)

val LocalAddToPlaylist = staticCompositionLocalOf<(TrackEntity) -> Unit> {
    error("PlaylistHost is not installed")
}

@Composable
fun PlaylistHost(model: AppModel, content: @Composable () -> Unit) {
    var pendingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalAddToPlaylist provides { pendingTrack = it },
            content = content,
        )
        pendingTrack?.let { track ->
            AddToPlaylistDialog(model, track, onDismiss = { pendingTrack = null })
        }
    }
}

/** Android Playlists. [selectedId] is the open playlist, kept by the caller so Now Playing can return to it. */
@Composable
fun PhonePlaylists(
    model: AppModel,
    actions: PlaylistFileActions,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onPlayed: (playlistId: Long) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    PlaylistScreen(
        model, actions, desktop = false, selectedId = selectedId, onSelect = onSelect,
        onBack = onBack, onPlayed = onPlayed, onOpenLibrary = onOpenLibrary,
    )
}

@Composable
fun PhoneInbox(model: AppModel, onBack: () -> Unit, onOpenProgress: () -> Unit, onCancelExport: () -> Unit) {
    val task by model.playlistBundleExportTask.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton("←", "Back to disc", onBack)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("YOUR TASKS", color = disc.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em)
                Text("Inbox", Modifier.padding(top = 3.dp), color = disc.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (task == null) {
            Text("No tasks yet", color = disc.muted, fontSize = 14.sp)
        } else {
            val current = requireNotNull(task)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(disc.panel2).border(1.dp, disc.line, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(current.playlistName, color = disc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (current.status) {
                        PlaylistBundleExportStatus.Running -> "Export in progress"
                        PlaylistBundleExportStatus.Completed -> "Export complete"
                        PlaylistBundleExportStatus.Cancelled -> "Export canceled"
                        PlaylistBundleExportStatus.Failed -> current.failure ?: "Export failed"
                    },
                    color = disc.muted,
                    fontSize = 12.sp,
                )
                if (current.status == PlaylistBundleExportStatus.Running) {
                    current.progress?.let { PlaylistExportProgress(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenProgress) { Text("Open progress") }
                        TextButton(onClick = onCancelExport) { Text("Cancel export") }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistBundleExportProgressDialog(task: PlaylistBundleExportTask, onHide: () -> Unit, onCancel: () -> Unit) {
    if (task.status != PlaylistBundleExportStatus.Running) return
    AlertDialog(
        onDismissRequest = onHide,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Export progress", Modifier.weight(1f))
                CircleIconButton("✕", "Hide export progress", onHide)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(task.playlistName, color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                task.progress?.let { PlaylistExportProgress(it) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth(), color = disc.accentReadable, trackColor = disc.line)
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Cancel export") }
        },
        dismissButton = {
            TextButton(onClick = onHide) { Text("Hide") }
        },
    )
}

@Composable
private fun PlaylistExportProgress(progress: PlaylistBundleExportProgress) {
    val fraction = progress.fraction
    val stageName = when (progress.stage) {
        PlaylistBundleExportStage.Copying -> "Copying songs and lyrics"
        PlaylistBundleExportStage.Packaging -> "Building the playlist package"
        PlaylistBundleExportStage.Saving -> "Saving the bundle"
        PlaylistBundleExportStage.Restoring -> "Restoring the previous file"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stageName, color = disc.text, fontSize = 12.sp)
            fraction?.let { Text("${(it * 100).toInt()}%", color = disc.muted, fontSize = 12.sp) }
        }
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = disc.accentReadable, trackColor = disc.line)
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = disc.accentReadable,
                trackColor = disc.line,
            )
        }
        Text(
            progress.totalBytes?.let { "${prettyBytes(progress.completedBytes)} of ${prettyBytes(it)} processed" }
                ?: "${prettyBytes(progress.completedBytes)} processed",
            color = disc.muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
fun DesktopPlaylists(
    model: AppModel,
    actions: PlaylistFileActions,
    narrow: Boolean,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    PlaylistScreen(
        model,
        actions,
        desktop = true,
        narrow = narrow,
        selectedId = selectedId,
        onSelect = onSelect,
        onOpenLibrary = onOpenLibrary,
    )
}

@Composable
private fun PlaylistScreen(
    model: AppModel,
    actions: PlaylistFileActions,
    desktop: Boolean,
    narrow: Boolean = false,
    selectedId: Long? = null,
    onSelect: (Long?) -> Unit = {},
    onBack: () -> Unit = {},
    onPlayed: (playlistId: Long) -> Unit = {},
    onOpenLibrary: () -> Unit,
) {
    val playlists by model.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var pageId by remember { mutableStateOf<Long?>(selectedId) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var exportFor by remember { mutableStateOf<PlaylistView?>(null) }
    var importChoice by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<ImportReview?>(null) }
    var pageMenu by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<PlaylistView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = playlists.firstOrNull { it.id == pageId }
    PlatformBackHandler(enabled = !desktop && current != null) {
        pageId = null
        onSelect(null)
    }
    // Both layouts own the open playlist outside this screen, so Now Playing can return to it.
    LaunchedEffect(selectedId) {
        pageId = selectedId
    }

    fun fail(message: String) {
        error = message
    }

    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure.message ?: "Playlist action failed")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = if (desktop && !narrow) 34.dp else 20.dp, vertical = if (desktop && !narrow) 28.dp else 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!desktop || current != null) {
                CircleIconButton("←", if (current == null) "Back to disc" else "Back to playlists") {
                    if (current != null) {
                        pageId = null
                        onSelect(null)
                    } else onBack()
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (current == null) "YOUR MIXES" else "PLAYLIST", color = disc.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em)
                Text(current?.name ?: "Playlists", Modifier.padding(top = 3.dp), color = disc.text, fontSize = if (desktop) 28.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current == null) {
                TextButton(onClick = { importChoice = true }) { Text("Import") }
                TextButton(onClick = { nameDialog = NameDialog("New playlist", "Create") }) { Text("+ New") }
            } else {
                Box {
                    Text("⋮", Modifier.clip(RoundedCornerShape(10.dp)).clickable { pageMenu = true }.padding(horizontal = 12.dp, vertical = 7.dp), color = disc.text, fontSize = 22.sp)
                    DropdownMenu(pageMenu, onDismissRequest = { pageMenu = false }) {
                        DropdownMenuItem(text = { Text("Add songs to playlist") }, onClick = {
                            pageMenu = false
                            onOpenLibrary()
                        })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            pageMenu = false
                            nameDialog = NameDialog("Rename playlist", "Save", current.name, current.id)
                        })
                        DropdownMenuItem(text = { Text("Change cover") }, onClick = {
                            pageMenu = false
                            actions.chooseCover { file -> file?.let { action { model.setPlaylistCover(current.id, it.name, it.bytes) } } }
                        })
                        if (current.coverPath != null) DropdownMenuItem(text = { Text("Remove cover") }, onClick = {
                            pageMenu = false
                            action { model.clearPlaylistCover(current.id) }
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { pageMenu = false; deleteConfirm = current })
                    }
                }
            }
        }

        if (current == null) {
            PlaylistCollection(
                modifier = Modifier.weight(1f),
                model = model,
                playlists = playlists,
                desktop = desktop,
                narrow = narrow,
                onOpen = {
                    pageId = it.id
                    onSelect(it.id)
                },
                onCreate = { nameDialog = NameDialog("New playlist", "Create") },
            )
        } else {
            PlaylistDetail(
                modifier = Modifier.weight(1f),
                model = model,
                playlist = current,
                desktop = desktop,
                // Play / Shuffle Play only play; they don't open Now Playing (2026-11-04 mockups).
                onPlay = { action { model.playPlaylist(current.id) }; onPlayed(current.id) },
                onShufflePlay = { action { model.playPlaylist(current.id, shuffle = true) }; onPlayed(current.id) },
                onExport = { exportFor = current },
                onSoon = { model.message("To audio file is coming later") },
                onPrototypeConvert = actions.prototypeConvert?.let { run -> { run(current) } },
                onRemove = { track -> action { model.removeFromPlaylist(current.id, track.id) } },
                onMoveTo = { track, to -> action { model.movePlaylistTrackTo(current.id, track.id, to) } },
                onPlaySong = { songs, index -> model.playPlaylistSong(songs, index); onPlayed(current.id) },
                onOpenLibrary = onOpenLibrary,
            )
        }
    }

    nameDialog?.let { dialog ->
        NameDialogUi(dialog, onDismiss = { nameDialog = null }) { name ->
            nameDialog = null
            action {
                val playlist = if (dialog.playlistId == null) model.createPlaylist(name) else model.renamePlaylist(dialog.playlistId, name)
                if (dialog.playlistId == null) {
                    pageId = playlist.id
                    onSelect(playlist.id)
                }
            }
        }
    }
    deleteConfirm?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete ${playlist.name}?") },
            text = { Text("This removes the playlist, not the songs from your library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = null
                    action {
                        model.deletePlaylist(playlist.id)
                        pageId = null
                        onSelect(null)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") } },
        )
    }
    exportFor?.let { playlist ->
        ExportDialog(model, playlist, actions, desktop, onDismiss = { exportFor = null }, onError = ::fail)
    }
    if (importChoice) {
        AlertDialog(
            onDismissRequest = { importChoice = false },
            title = { Text("Import playlist") },
            text = { Text("Choose an M3U/M3U8 playlist or a Record Disc bundle.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        importChoice = false
                        actions.openM3u { location ->
                            if (location != null) action { review = ImportReview.M3u(model.previewM3uImport(location)) }
                        }
                    }) { Text("M3U8") }
                    TextButton(onClick = {
                        importChoice = false
                        actions.openBundle { location ->
                            if (location != null) action { review = ImportReview.Bundle(model.previewPlaylistBundle(location)) }
                        }
                    }) { Text("Record Disc") }
                }
            },
            dismissButton = { TextButton(onClick = { importChoice = false }) { Text("Cancel") } },
        )
    }
    review?.let { currentReview ->
        ImportReviewDialog(
            currentReview,
            onDismiss = {
                review = null
                if (currentReview is ImportReview.Bundle) action { model.platform.cleanupPlaylistBundlePreview(currentReview.preview) }
            },
            onImport = { name ->
                action {
                    val imported = try {
                        when (currentReview) {
                            is ImportReview.M3u -> model.importM3u(currentReview.preview, name)
                            is ImportReview.Bundle -> model.importPlaylistBundle(currentReview.preview, name)
                        }
                    } catch (failure: Exception) {
                        if (currentReview is ImportReview.Bundle) review = null
                        throw failure
                    }
                    review = null
                    pageId = imported.id
                    onSelect(imported.id)
                }
            },
        )
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Playlist error") }, text = { Text(message) }, confirmButton = {
            TextButton(onClick = { error = null }) { Text("OK") }
        })
    }
}

@Composable
private fun PlaylistCollection(
    modifier: Modifier,
    model: AppModel,
    playlists: List<PlaylistView>,
    desktop: Boolean,
    narrow: Boolean,
    onOpen: (PlaylistView) -> Unit,
    onCreate: () -> Unit,
) {
    if (playlists.isEmpty()) {
        Column(modifier.padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No playlists yet", color = disc.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Collect songs into a mix you can play or share.", color = disc.muted, fontSize = 13.sp)
            ActionPill("＋", "New playlist", onClick = onCreate)
        }
    } else if (desktop && !narrow) {
        BoxWithConstraints(modifier) {
            val gap = 16.dp
            val columns = (maxWidth / 220.dp).toInt().coerceAtLeast(2)
            val cell = (maxWidth - gap * (columns - 1)) / columns
            LazyColumn(verticalArrangement = Arrangement.spacedBy(gap), contentPadding = PaddingValues(bottom = 100.dp)) {
                items(playlists.chunked(columns), key = { it.first().id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { playlist ->
                            PlaylistCard(model, playlist, cell, onClick = { onOpen(playlist) })
                        }
                        repeat(columns - row.size) { Spacer(Modifier.width(cell)) }
                    }
                }
            }
        }
    } else {
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable { onOpen(playlist) }.padding(9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    PlaylistArtwork(model, playlist, if (desktop) 56.dp else 54.dp)
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(plural(playlist.songCount, "song"), Modifier.padding(top = 3.dp), color = disc.muted, fontSize = 11.sp)
                    }
                    Text("›", color = disc.muted, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(model: AppModel, playlist: PlaylistView, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(10.dp)) {
        PlaylistArtwork(model, playlist, width - 20.dp)
        Text(playlist.name, Modifier.padding(top = 10.dp), color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(plural(playlist.songCount, "song"), Modifier.padding(top = 3.dp), color = disc.muted, fontSize = 11.sp)
    }
}

@Composable
internal fun PlaylistArtwork(model: AppModel, playlist: PlaylistView, size: androidx.compose.ui.unit.Dp) {
    var bitmap by remember(playlist.coverPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(playlist.coverPath) {
        try {
            bitmap = playlist.coverPath?.let { model.platform.loadPlaylistCover(it, 512) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            model.message("Couldn't load the playlist cover")
        }
    }
    if (bitmap == null) {
        Cover(model, playlist.fallbackCover, size)
    } else {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!,
            contentDescription = "${playlist.name} cover",
            modifier = Modifier.size(size).clip(RoundedCornerShape(14.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlaylistDetail(
    modifier: Modifier,
    model: AppModel,
    playlist: PlaylistView,
    desktop: Boolean,
    onPlay: () -> Unit,
    onShufflePlay: () -> Unit,
    onExport: () -> Unit,
    onSoon: () -> Unit,
    onPrototypeConvert: (() -> Unit)?,
    onRemove: (TrackEntity) -> Unit,
    onMoveTo: (TrackEntity, Int) -> Unit,
    onPlaySong: (List<TrackEntity>, Int) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    // Dropping a song reorders this local copy at once, so the row doesn't jump back while the
    // database catches up; a fresh list from the database replaces it.
    var songs by remember(playlist.songs) { mutableStateOf(playlist.songs) }
    val listState = rememberLazyListState()
    val reorder = remember(playlist.id) { PlaylistReorder() }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
        item(key = "hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlaylistArtwork(model, playlist, if (desktop) 150.dp else 116.dp)
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, color = disc.text, fontSize = if (desktop) 26.sp else 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${plural(playlist.songCount, "song")} · ${playlistDuration(playlist.durationMs)}", Modifier.padding(top = 5.dp), color = disc.muted, fontSize = 12.sp)
                }
            }
            FlowRow(
                Modifier.padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPill("▶", "Play", onClick = onPlay, enabled = playlist.songs.isNotEmpty(), primary = true)
                ActionPill("⇄", "Shuffle Play", onClick = onShufflePlay, enabled = playlist.songs.isNotEmpty())
                ActionPill("⇧", "Export", onClick = onExport)
                // TEMPORARY prototype hook: in Android dev builds only (see PlaylistFileActions), the
                // whole "To audio file · SOON" area (pill and tag) answers a long press. A tap still does nothing.
                Row(
                    if (onPrototypeConvert == null) Modifier else Modifier.clip(RoundedCornerShape(50))
                        .combinedClickable(onClickLabel = null, onLongClickLabel = "Run the convert prototype", onLongClick = onPrototypeConvert, onClick = {}),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    ActionPill("♫", "To audio file", onClick = onSoon, enabled = false)
                    SoonTag()
                }
            }
            if (playlist.songs.isEmpty()) {
                Text(
                    "Add songs to this playlist from your Library ›",
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Go to Library to add songs", onClick = onOpenLibrary)
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    color = disc.accentReadable,
                    fontSize = 13.sp,
                )
            } else if (desktop) {
                Text(
                    "Hold a song, then drag it to reorder. Use ⋮ for song options.",
                    Modifier.padding(start = 8.dp, bottom = 6.dp), color = disc.muted, fontSize = 11.sp,
                )
            }
        }
        itemsIndexed(songs, key = { _, track -> track.id }) { index, track ->
            PlaylistSongRow(
                model, track, index, songs.size, reorder, listState,
                onPlay = { onPlaySong(songs, index) },
                onRemove = { onRemove(track) },
                onDrop = { to ->
                    songs = songs.toMutableList().apply { add(to, removeAt(index)) }
                    onMoveTo(track, to)
                },
            )
        }
    }
}

/**
 * Hold-and-drag state for one playlist page: which row is lifted, how far it has moved, and
 * where it would land. The other rows slide aside by one row height to show the gap.
 */
@Stable
private class PlaylistReorder {
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var dragY by mutableFloatStateOf(0f)
    var from by mutableIntStateOf(0)
        private set
    var stepPx by mutableFloatStateOf(0f)
    var count = 0
        private set
    /** The release after a drag isn't a tap, so it mustn't play the song. */
    var suppressClick = false

    val target: Int
        get() = if (stepPx <= 0f) from else (from + (dragY / stepPx).roundToInt()).coerceIn(0, count - 1)

    fun start(id: Long, index: Int, size: Int) {
        draggingId = id; from = index; count = size; dragY = 0f; suppressClick = true
    }

    fun end() {
        draggingId = null; dragY = 0f
    }

    /** Keeps the lifted row within the list. */
    fun clampDrag() {
        dragY = dragY.coerceIn(-from * stepPx, (count - 1 - from) * stepPx)
    }

    /** How far a non-lifted row at [index] slides to make room. */
    fun shiftFor(index: Int): Float {
        if (draggingId == null) return 0f
        val to = target
        return when {
            index in (from + 1)..to -> -stepPx
            index in to until from -> stepPx
            else -> 0f
        }
    }
}

@Composable
private fun PlaylistSongRow(
    model: AppModel,
    track: TrackEntity,
    index: Int,
    count: Int,
    reorder: PlaylistReorder,
    listState: LazyListState,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDrop: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val lifted = reorder.draggingId == track.id
    val shift by animateFloatAsState(
        if (lifted) 0f else reorder.shiftFor(index),
        if (reduceMotion || reorder.draggingId == null) snap() else tween(180),
        label = "rowShift",
    )
    val shape = RoundedCornerShape(12.dp)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    Row(
        Modifier.fillMaxWidth()
            .onSizeChanged { reorder.stepPx = it.height.toFloat() }
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer { translationY = if (lifted) reorder.dragY else shift }
            // Hold (touch or mouse), then drag: a quick tap still plays the song.
            .pointerInput(track.id, index, count) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        reorder.start(track.id, index, count)
                    },
                    onDragEnd = {
                        val to = reorder.target
                        reorder.end()
                        if (to != index) {
                            onDrop(to)
                            model.message("Moved to position ${to + 1}")
                        }
                        scope.launch { delay(400); reorder.suppressClick = false }
                    },
                    onDragCancel = {
                        reorder.end()
                        scope.launch { delay(400); reorder.suppressClick = false }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.dragY += amount.y
                        // Near the top or bottom of the list, scroll it and keep the row under the pointer.
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.key == track.id }
                        if (item != null) {
                            val top = item.offset + reorder.dragY
                            val delta = when {
                                top < info.viewportStartOffset + edge -> -12f
                                top + item.size > info.viewportEndOffset - edge -> 12f
                                else -> 0f
                            }
                            if (delta != 0f) reorder.dragY += listState.dispatchRawDelta(delta)
                        }
                        reorder.clampDrag()
                    },
                )
            }
            .padding(vertical = 2.dp)
            .then(if (lifted) Modifier.shadow(12.dp, shape).border(1.dp, disc.accent.copy(alpha = 0.5f), shape).background(disc.panel2, shape) else Modifier)
            .clip(shape)
            .clickable(onClickLabel = "Play ${track.displayTitle}") { if (!reorder.suppressClick) onPlay() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        ArtThumb(model.artwork, track.location, 42.dp, 9.dp)
        Column(Modifier.weight(1f)) {
            Text(track.displayTitle, color = disc.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(track.artist, track.album).joinToString(" · ").ifEmpty { "Unknown artist" }, color = disc.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(track.durationMs), color = disc.muted, fontSize = 10.sp)
        SongMenuButton(track, onRemoveFromPlaylist = onRemove)
    }
}

@Composable
private fun ExportDialog(
    model: AppModel,
    playlist: PlaylistView,
    actions: PlaylistFileActions,
    desktop: Boolean,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var bundle by remember { mutableStateOf(false) }
    var paths by remember { mutableStateOf(M3uPathMode.Relative) }
    var trackInfo by remember { mutableStateOf(true) }
    var includeLyrics by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("Export") }
    var canceling by remember { mutableStateOf(false) }
    var exportCanceled by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val progressUpdates = remember { MutableStateFlow<PlaylistBundleExportProgress?>(null) }
    val progress by progressUpdates.collectAsState()
    fun reportFailure(error: Exception) { onError(error.message ?: "Playlist export failed") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Export ${playlist.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!bundle, onClick = { bundle = false })
                    Text("M3U8", Modifier.clickable { bundle = false }, color = disc.text)
                    Spacer(Modifier.width(10.dp))
                    RadioButton(bundle, onClick = { bundle = true })
                    Text("Record Disc", Modifier.clickable { bundle = true }, color = disc.text)
                }
                if (bundle) {
                    Text("${playlist.songCount} songs · ${playlist.songs.sumOf { it.sizeBytes }.let(::prettyBytes)}", color = disc.muted, fontSize = 12.sp)
                    Text("${playlist.songs.count { it.lyricsLocation != null }} songs have lyrics", color = disc.muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PlaylistArtwork(model, playlist, 42.dp)
                        Text(if (playlist.coverPath == null) "Using first song's cover" else "Custom playlist cover", Modifier.weight(1f), color = disc.muted, fontSize = 12.sp)
                        TextButton(onClick = {
                            actions.chooseCover { file ->
                                if (file != null) scope.launch {
                                    try {
                                        model.setPlaylistCover(playlist.id, file.name, file.bytes)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        reportFailure(error)
                                    }
                                }
                            }
                        }) { Text("Change") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeLyrics, onCheckedChange = { includeLyrics = it })
                        Text("Include lyrics (.lrc)", color = disc.text, fontSize = 13.sp)
                    }
                    if (exportJob != null && progress != null) {
                        val currentProgress = requireNotNull(progress)
                        val fraction = currentProgress.fraction
                        val stageName = when (currentProgress.stage) {
                            PlaylistBundleExportStage.Copying -> "Copying songs and lyrics"
                            PlaylistBundleExportStage.Packaging -> "Building the playlist package"
                            PlaylistBundleExportStage.Saving -> "Saving the bundle"
                            PlaylistBundleExportStage.Restoring -> "Restoring the previous file"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stageName, color = disc.text, fontSize = 12.sp)
                                fraction?.let { Text("${(it * 100).toInt()}%", color = disc.muted, fontSize = 12.sp) }
                            }
                            if (fraction == null) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = disc.accentReadable,
                                    trackColor = disc.line,
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = disc.accentReadable,
                                    trackColor = disc.line,
                                )
                            }
                            Text(
                                currentProgress.totalBytes?.let { "${prettyBytes(currentProgress.completedBytes)} of ${prettyBytes(it)} processed" }
                                    ?: "${prettyBytes(currentProgress.completedBytes)} processed",
                                color = disc.muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                } else {
                    Text("File paths", color = disc.muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(paths == M3uPathMode.Relative, onClick = { paths = M3uPathMode.Relative })
                        Text("Relative", Modifier.clickable { paths = M3uPathMode.Relative }, color = disc.text)
                        RadioButton(paths == M3uPathMode.Full, onClick = { paths = M3uPathMode.Full })
                        Text("Full", Modifier.clickable { paths = M3uPathMode.Full }, color = disc.text)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(trackInfo, onCheckedChange = { trackInfo = it })
                        Text("Include track info", color = disc.text, fontSize = 13.sp)
                    }
                    Text("Only the track list is exported; audio files stay where they are.", color = disc.muted, fontSize = 11.sp)
                }
                if (exportCanceled) {
                    Text("Export canceled. You can try again.", color = disc.muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                busyLabel = "Choose location…"
                canceling = false
                exportCanceled = false
                if (bundle && !desktop && actions.startBackgroundBundleExport != null) {
                    actions.requestExportNotificationPermission {
                        actions.saveBundle("${playlist.name}.rdplaylist") { destination ->
                            if (destination == null) {
                                busy = false
                                busyLabel = "Export"
                            } else {
                                val failure = requireNotNull(actions.startBackgroundBundleExport).invoke(
                                    playlist.id,
                                    playlist.name,
                                    destination,
                                    includeLyrics,
                                )
                                if (failure == null) {
                                    onDismiss()
                                } else {
                                    busy = false
                                    busyLabel = "Export"
                                    reportFailure(IllegalStateException(failure))
                                }
                            }
                        }
                    }
                } else if (bundle) actions.saveBundle("${playlist.name}.rdplaylist") { destination ->
                    if (destination == null) {
                        busy = false
                        busyLabel = "Export"
                    } else {
                        busyLabel = "Exporting…"
                        progressUpdates.value = null
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                model.exportPlaylistBundle(playlist.id, destination, includeLyrics) { progressUpdates.value = it }
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                val cleanupError = cancelled.suppressedExceptions.firstOrNull()
                                if (cleanupError != null) reportFailure(IllegalStateException("Export cancellation cleanup failed", cleanupError))
                                else exportCanceled = true
                            } catch (error: Exception) {
                                reportFailure(error)
                            } finally {
                                busy = false
                                busyLabel = "Export"
                                canceling = false
                                exportJob = null
                                progressUpdates.value = null
                            }
                        }
                        exportJob = job
                        job.start()
                    }
                } else actions.saveM3u("${playlist.name}.m3u8") { destination ->
                    if (destination == null) {
                        busy = false
                        busyLabel = "Export"
                    } else {
                        busyLabel = "Exporting…"
                        scope.launch {
                            try {
                                model.exportM3u(playlist.id, destination, paths, trackInfo)
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                busy = false
                                busyLabel = "Export"
                                reportFailure(error)
                            }
                        }
                    }
                }
            }) { Text(if (busy) busyLabel else "Export") }
        },
        dismissButton = {
            TextButton(
                enabled = if (exportJob != null) !canceling else !busy,
                onClick = {
                    val job = exportJob
                    if (job != null) {
                        canceling = true
                        job.cancel()
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(
                    when {
                        canceling -> "Canceling…"
                        exportJob != null -> "Cancel export"
                        else -> "Cancel"
                    },
                )
            }
        },
    )
}

private sealed interface ImportReview {
    data class M3u(val preview: M3uImportPreview) : ImportReview
    data class Bundle(val preview: PlaylistBundlePreview) : ImportReview
}

@Composable
private fun ImportReviewDialog(review: ImportReview, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var name by remember(review) { mutableStateOf(when (review) {
        is ImportReview.M3u -> review.preview.name
        is ImportReview.Bundle -> review.preview.name
    }) }
    val title = when (review) {
        is ImportReview.M3u -> "Review M3U8"
        is ImportReview.Bundle -> "Review Record Disc"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (review) {
                    is ImportReview.M3u -> {
                        val preview = review.preview
                        Text("${preview.items.size} entries · ${preview.matchedCount} matched · ${preview.notFoundCount} not found", color = disc.muted, fontSize = 12.sp)
                        preview.items.filter { it.track == null }.take(8).forEach { Text("Not found: ${it.entry.title ?: it.entry.path}", color = disc.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    is ImportReview.Bundle -> {
                        val preview = review.preview
                        val existing = preview.songs.count { it.existingTrack != null }
                        Text("${preview.songs.size} songs · ${preview.songs.size - existing} new · $existing already in library", color = disc.muted, fontSize = 12.sp)
                        Text("${preview.songs.count { it.lyricsPath != null }} lyrics · ${if (preview.coverBytes == null) "no cover" else "playlist cover included"}", color = disc.muted, fontSize = 12.sp)
                        preview.songs.mapNotNull { it.warning }.take(3).forEach { Text(it, color = disc.muted, fontSize = 11.sp) }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onImport(name) }) {
                val count = when (review) {
                    is ImportReview.M3u -> review.preview.matchedCount
                    is ImportReview.Bundle -> review.preview.songs.size
                }
                Text("Import $count songs")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddToPlaylistDialog(model: AppModel, track: TrackEntity, onDismiss: () -> Unit) {
    val playlists by model.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.displayTitle, color = disc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                playlists.forEach { playlist ->
                    val already = playlist.songs.any { it.id == track.id }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                model.addToPlaylist(playlist.id, track.id)
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Couldn't add song"
                            }
                        }
                    }, enabled = !already, modifier = Modifier.fillMaxWidth()) {
                        Text(if (already) "Already in ${playlist.name}" else playlist.name, Modifier.weight(1f), color = if (already) disc.muted else disc.text)
                        Text(plural(playlist.songCount, "song"), color = disc.muted, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { creating = true }) { Text("+ Create playlist") }
                error?.let { Text(it, color = disc.accentReadable, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (creating) {
        NameDialogUi(NameDialog("New playlist", "Create"), onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch {
                try {
                    val playlist = model.createPlaylist(name)
                    model.addToPlaylist(playlist.id, track.id)
                    onDismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Couldn't create playlist"
                }
            }
        }
    }
}

private data class NameDialog(val title: String, val confirm: String, val initial: String = "", val playlistId: Long? = null)

@Composable
private fun NameDialogUi(dialog: NameDialog, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(dialog) { mutableStateOf(dialog.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(dialog.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionPill(symbol: String, label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    Row(
        Modifier.alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(50))
            .background(if (primary) disc.accent else disc.tint.copy(alpha = 0.03f))
            .border(1.dp, if (primary) disc.accent else disc.line, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(symbol, color = if (primary) disc.accentInk else disc.text, fontSize = 12.sp)
        Text(label, color = if (primary) disc.accentInk else disc.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun playlistDuration(milliseconds: Long): String {
    val minutes = ((milliseconds + 30_000) / 60_000).coerceAtLeast(0)
    return "$minutes min"
}

private fun prettyBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${bytes / 1_000_000_000}.${(bytes % 1_000_000_000) / 100_000_000} GB"
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    else -> "$bytes bytes"
}
