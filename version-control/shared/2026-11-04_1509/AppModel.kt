package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import dev.recorddisc.player.library.Album
import dev.recorddisc.player.library.Artist
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibraryScanner
import dev.recorddisc.player.library.LyricLine
import dev.recorddisc.player.library.Lrc
import dev.recorddisc.player.library.Lyrics
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.M3u
import dev.recorddisc.player.library.M3uImportItem
import dev.recorddisc.player.library.M3uImportPreview
import dev.recorddisc.player.library.M3uPathMode
import dev.recorddisc.player.library.M3uTrack
import dev.recorddisc.player.library.PlaylistBundlePreview
import dev.recorddisc.player.library.PlaylistEntity
import dev.recorddisc.player.library.PlaylistItemEntity
import dev.recorddisc.player.library.PlaylistView
import dev.recorddisc.player.library.PlaylistTextFile
import dev.recorddisc.player.library.PlaylistImportFile
import dev.recorddisc.player.library.ImportedPlaylistSong
import dev.recorddisc.player.library.SavedFile
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.library.SettingEntity
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagSaveException
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.groupAlbums
import dev.recorddisc.player.library.groupArtists
import dev.recorddisc.player.library.searchText
import dev.recorddisc.player.library.trackEntity
import dev.recorddisc.player.library.displayTitle
import dev.recorddisc.player.library.matchTrackByTags
import dev.recorddisc.player.playback.MusicPlayer
import dev.recorddisc.player.playback.RepeatMode
import dev.recorddisc.player.playback.toQueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PlaylistBundleExportStage { Copying, Packaging, Saving, Restoring }

data class PlaylistBundleExportProgress(
    val stage: PlaylistBundleExportStage,
    val completedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (completedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

enum class PlaylistBundleExportStatus { Running, Completed, Cancelled, Failed }

data class PlaylistBundleExportTask(
    val id: Long,
    val playlistId: Long,
    val playlistName: String,
    val status: PlaylistBundleExportStatus,
    val progress: PlaylistBundleExportProgress? = null,
    val failure: String? = null,
)

/** What each platform provides to the shared app. */
interface LibraryPlatform {
    /** Folder to offer on first run (desktop: the Music folder), or null to ask (Android). */
    val suggestedFolder: String?
    fun sourceFor(folder: String): LibrarySource
    /** Short name to show: "~/Music" on desktop, "Music" on Android. */
    fun folderLabel(folder: String): String
    /** Embedded cover art, scaled so its longest side is at most [maxSizePx]; null if none. */
    suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap?
    /** Calls [onChange] (from any thread) when files under [folder] change; null where not supported (Android). */
    fun watch(folder: String, onChange: () -> Unit): AutoCloseable? = null
    /** Gives up access to a folder that is no longer the music folder (Android's folder grant). */
    fun releaseFolder(folder: String) = Unit
    /** Current time used for persisted creation timestamps. */
    fun currentTimeMs(): Long = 0
    /** Saves a picked JPEG or PNG cover in private app storage and returns its stable path. */
    suspend fun savePlaylistCover(playlistId: Long, extension: String, bytes: ByteArray): String =
        throw UnsupportedOperationException("Playlist covers aren't available here")
    suspend fun deletePlaylistCover(path: String) = Unit
    suspend fun loadPlaylistCover(path: String, maxSizePx: Int): ImageBitmap? = null
    /** Writes a small text export to the destination selected by the user. */
    suspend fun writeExport(destination: String, bytes: ByteArray): Unit =
        throw UnsupportedOperationException("Playlist export isn't available here")
    /** Streams a generated file, such as a `.rdplaylist`, to the destination selected by the user. */
    suspend fun writeExportFile(
        destination: String,
        sourcePath: String,
        onProgress: (PlaylistBundleExportProgress) -> Unit = {},
        checkActive: () -> Unit = {},
    ): Unit =
        throw UnsupportedOperationException("Playlist export isn't available here")
    /** Copies new bundle songs and their lyrics into `Imported/<playlistName>` under [musicFolder]. */
    suspend fun importSongs(
        playlistName: String,
        musicFolder: String,
        files: List<PlaylistImportFile>,
    ): List<ImportedPlaylistSong> = throw UnsupportedOperationException("Playlist import isn't available here")
    suspend fun readPlaylistText(location: String): PlaylistTextFile =
        throw UnsupportedOperationException("Playlist import isn't available here")
    suspend fun writePlaylistBundle(
        destination: String,
        name: String,
        songs: List<TrackEntity>,
        customCoverPath: String?,
        fallbackCover: TrackEntity?,
        includeLyrics: Boolean,
        onProgress: (PlaylistBundleExportProgress) -> Unit = {},
    ): Unit = throw UnsupportedOperationException("Playlist bundle export isn't available here")
    suspend fun readPlaylistBundle(location: String, library: List<TrackEntity>): PlaylistBundlePreview =
        throw UnsupportedOperationException("Playlist bundle import isn't available here")
    suspend fun cleanupPlaylistBundlePreview(preview: PlaylistBundlePreview) = Unit
    /** Returns the path form stored in a Relative M3U export. */
    fun relativePlaylistPath(track: TrackEntity, musicFolder: String): String = track.location
    /** Resolves an M3U entry against its source file and the chosen music folder. */
    suspend fun resolvePlaylistPath(path: String, playlistLocation: String?, musicFolder: String): String? = null
    /** Whether [track]'s file can be changed by the song details editor. */
    suspend fun editSupport(track: TrackEntity): EditSupport = EditSupport.ReadOnly("Editing isn't available here")
    /** Saves [edit] into [track]'s file. Throws with a message for the user if it can't; the file is then unchanged. */
    suspend fun saveTags(track: TrackEntity, edit: TagEdit): SavedFile = throw UnsupportedOperationException("Editing isn't available here")
    /** True where saving rewrites the song file in place (Android), so the player has to let go of it first. */
    val rewritesInPlace: Boolean get() = false
    /** Finishes a save the app couldn't complete last time; returns a notice for the user, or null. */
    suspend fun recoverPendingSaves(): String? = null
    /** [track]'s lyrics: its `.lrc` if there is one, else lyrics stored inside the song file, else null. */
    suspend fun readLyrics(track: TrackEntity): Lyrics? = null
    /** Whether an `.lrc` can be written next to [track]. */
    suspend fun lyricsSupport(track: TrackEntity): EditSupport = EditSupport.ReadOnly("Editing isn't available here")
    /**
     * Writes [text] as [track]'s `.lrc` (creating it if needed), or deletes the `.lrc` when [text]
     * is null. Returns the `.lrc`'s location afterwards (null once deleted). Throws
     * [TagSaveException] with a message for the user if it can't.
     */
    suspend fun saveLyrics(track: TrackEntity, text: String?): String? = throw UnsupportedOperationException("Editing isn't available here")
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val folder: String, val checked: Int, val fileName: String) : ScanState
    data class Done(val folder: String, val songs: Int, val albums: Int, val artists: Int) : ScanState
    data class Failed(val folder: String, val message: String) : ScanState
}

/**
 * The app's state shared by both layouts: settings, the library, scanning and the player.
 * Lives as long as the app (Android: the Application; desktop: the process).
 */
class AppModel(
    db: LibraryDatabase,
    val player: MusicPlayer,
    val platform: LibraryPlatform,
    private val scope: CoroutineScope,
) {
    private val tracksDao = db.tracks()
    private val settingsDao = db.settings()
    private val playlistsDao = db.playlists()
    private val scanner = LibraryScanner(tracksDao)

    /** Null until loaded from the database. */
    val settings: StateFlow<AppSettings?> = settingsDao.observeAll()
        .map { rows -> AppSettings.from(rows.associate { it.name to it.value }) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    val tracks: StateFlow<List<TrackEntity>> = tracksDao.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Playlists with library songs resolved; missing tracks remain stored in their original positions. */
    val playlists: StateFlow<List<PlaylistView>> = combine(
        playlistsDao.observeAll(),
        playlistsDao.observeItems(),
        tracks,
    ) { savedPlaylists, savedItems, library ->
        val byId = library.associateBy { it.id }
        val itemsByPlaylist = savedItems.groupBy { it.playlistId }
        savedPlaylists.map { playlist ->
            val songs = itemsByPlaylist[playlist.id].orEmpty()
                .sortedBy { it.position }
                .mapNotNull { byId[it.trackId] }
            PlaylistView(
                id = playlist.id,
                name = playlist.name,
                songs = songs,
                songCount = songs.size,
                durationMs = songs.sumOf { it.durationMs ?: 0L },
                coverPath = playlist.coverPath,
                fallbackCover = songs.firstOrNull(),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _libraryQuery = MutableStateFlow("")
    /** The Library's search text. */
    val libraryQuery: StateFlow<String> = _libraryQuery.asStateFlow()

    /** The Library's song list: everything, or the songs matching [libraryQuery] by title, artist, album or file name. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val librarySongs: StateFlow<List<TrackEntity>> = _libraryQuery
        .flatMapLatest { q -> if (q.isBlank()) tracks else tracksDao.searchText(q.trim()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every album and artist, for their pages. */
    val albums: StateFlow<List<Album>> = tracks.map(::groupAlbums).stateIn(scope, SharingStarted.Eagerly, emptyList())
    val artists: StateFlow<List<Artist>> = tracks.map(::groupArtists).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The Library's Albums and Artists tabs: those with a song matching [libraryQuery]. */
    val libraryAlbums: StateFlow<List<Album>> = librarySongs.map(::groupAlbums).stateIn(scope, SharingStarted.Eagerly, emptyList())
    val libraryArtists: StateFlow<List<Artist>> = librarySongs.map(::groupArtists).stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun setLibraryQuery(text: String) {
        _libraryQuery.value = text
    }

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private var scanJob: Job? = null

    private val _playlistBundleExportTask = MutableStateFlow<PlaylistBundleExportTask?>(null)
    /** The latest custom-format export, kept beyond the screen that started it. */
    val playlistBundleExportTask: StateFlow<PlaylistBundleExportTask?> = _playlistBundleExportTask.asStateFlow()
    private var playlistBundleExportJob: Job? = null
    private var nextPlaylistBundleExportId = 0L

    fun startPlaylistBundleExport(playlistId: Long, playlistName: String, destination: String, includeLyrics: Boolean): Boolean {
        if (playlistBundleExportJob?.isActive == true) return false
        val task = PlaylistBundleExportTask(
            id = ++nextPlaylistBundleExportId,
            playlistId = playlistId,
            playlistName = playlistName,
            status = PlaylistBundleExportStatus.Running,
        )
        _playlistBundleExportTask.value = task
        playlistBundleExportJob = scope.launch {
            try {
                exportPlaylistBundle(playlistId, destination, includeLyrics) { progress ->
                    if (_playlistBundleExportTask.value?.id == task.id) {
                        _playlistBundleExportTask.value = task.copy(status = PlaylistBundleExportStatus.Running, progress = progress)
                    }
                }
                _playlistBundleExportTask.value = task.copy(
                    status = PlaylistBundleExportStatus.Completed,
                    progress = _playlistBundleExportTask.value?.takeIf { it.id == task.id }?.progress,
                )
            } catch (cancelled: CancellationException) {
                val cleanupFailure = cancelled.suppressedExceptions.firstOrNull()
                _playlistBundleExportTask.value = task.copy(
                    status = if (cleanupFailure == null) PlaylistBundleExportStatus.Cancelled else PlaylistBundleExportStatus.Failed,
                    progress = _playlistBundleExportTask.value?.takeIf { it.id == task.id }?.progress,
                    failure = cleanupFailure?.let { "Export cleanup failed: ${it.message ?: "destination may need attention"}" },
                )
                throw cancelled
            } catch (failure: Exception) {
                _playlistBundleExportTask.value = task.copy(
                    status = PlaylistBundleExportStatus.Failed,
                    progress = _playlistBundleExportTask.value?.takeIf { it.id == task.id }?.progress,
                    failure = failure.message ?: "Playlist export failed",
                )
                message("Couldn't export ${task.playlistName}")
            } finally {
                playlistBundleExportJob = null
            }
        }
        return true
    }

    fun cancelPlaylistBundleExport() {
        playlistBundleExportJob?.cancel()
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Short notices for a toast or snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val artwork = ArtworkCache(platform)

    /** Held while song details are being saved; scans wait for it. Declared before init, which can start a scan. */
    private val saveLock = Mutex()
    private val playlistLock = Mutex()

    private val _accessRevision = MutableStateFlow(0)
    /** Goes up when folder access changes (Android: write access granted), so the editor checks again. */
    val accessRevision: StateFlow<Int> = _accessRevision.asStateFlow()

    private var quietScan: Job? = null
    private val folderChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            // A tag save the app couldn't finish last time (Android) is completed or undone first.
            runCatching { platform.recoverPendingSaves() }.getOrNull()?.let(::message)
            val s = settings.filterNotNull().first()
            player.setShuffle(s.shuffle)
            player.setRepeatMode(s.repeatMode)
            val folder = s.musicFolder
            if (s.setupDone && folder != null) {
                // Show the library straight away (or the queue as it was left), then pick up any
                // changes made since last time.
                val library = tracksDao.observeAll().first()
                if (!(s.resumeOnStart && restoreQueue(library))) loadQueue(library, play = false)
                rescan(folder)
            }
            rememberQueue()
            _initialized.value = true
        }
        watchFolder()
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    /**
     * Scans [folder] and makes it the music folder if it has music. On first run
     * [ScanState.Done] says how much was found; from Settings (folder change, Rescan) the result
     * is a short message instead. A folder without music never changes the library.
     */
    fun scan(folder: String) {
        scanJob?.cancel()
        scanJob = scope.launch {
            val before = settings.value
            val fromSettings = before?.setupDone == true
            val label = platform.folderLabel(folder)
            _scan.value = ScanState.Running(folder, 0, "")
            try {
                val result = scanner.scan(platform.sourceFor(folder)) { checked, name -> _scan.value = ScanState.Running(folder, checked, name) }
                val songs = if (result.found > 0) tracksDao.count() else 0
                if (songs > 0) {
                    put(AppSettings.MUSIC_FOLDER, folder)
                    before?.musicFolder?.takeIf { it != folder }?.let(platform::releaseFolder)
                }
                if (fromSettings) {
                    _scan.value = ScanState.Idle
                    message(if (songs > 0) "Library updated · $songs song${if (songs == 1) "" else "s"}" else "No music found in $label. Your library wasn't changed.")
                } else {
                    _scan.value = ScanState.Done(folder, songs, tracksDao.observeAlbums().first().size, tracksDao.observeArtists().first().size)
                }
            } catch (e: CancellationException) {
                _scan.value = ScanState.Idle
                throw e
            } catch (e: Exception) {
                val text = e.message ?: "Couldn't read $label"
                if (fromSettings) {
                    _scan.value = ScanState.Idle
                    message("Couldn't scan $label")
                } else {
                    _scan.value = ScanState.Failed(folder, text)
                }
            }
        }
    }

    /** Settings' Rescan button: scans the music folder again, with progress. */
    fun rescanNow() {
        settings.value?.musicFolder?.let(::scan)
    }

    fun cancelScan() {
        scanJob?.cancel()
        _scan.value = ScanState.Idle
    }

    /** Leaves first run: the library becomes the queue, ready to play. */
    fun finishSetup() = scope.launch {
        put(AppSettings.SETUP_DONE, "true")
        _scan.value = ScanState.Idle
        loadQueue(tracks.value.ifEmpty { tracksDao.observeAll().first() }, play = false)
    }

    /** Quiet background rescan; the library updates when it finishes. Skipped while another scan runs. */
    private fun rescan(folder: String) {
        if (quietScan?.isActive == true || scanJob?.isActive == true || saveLock.isLocked) return
        quietScan = scope.launch {
            try {
                scanner.scan(platform.sourceFor(folder))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Couldn't rescan ${platform.folderLabel(folder)}")
            }
        }
    }

    /** "Watch for changes": rescans a moment after files in the music folder stop changing. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun watchFolder() = scope.launch {
        settings.filterNotNull().map { s -> s.musicFolder.takeIf { s.setupDone && s.watchFolder } }.distinctUntilChanged()
            .collectLatest { folder ->
                if (folder == null) return@collectLatest
                val watcher = platform.watch(folder) { folderChanges.tryEmit(Unit) } ?: return@collectLatest
                try {
                    folderChanges.debounce(WATCH_SETTLE_MS).collect { rescan(folder) }
                } finally {
                    watcher.close()
                }
            }
    }

    /** Resume on start: reopens the saved queue at the saved song and position, paused. False if there's nothing to restore. */
    private suspend fun restoreQueue(library: List<TrackEntity>): Boolean {
        val saved = settingsDao.observeAll().first().associate { it.name to it.value }
        val ids = saved[RESUME_QUEUE]?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return false
        val currentId = ids.getOrNull(saved[RESUME_INDEX]?.toIntOrNull() ?: 0)
        val byId = library.associateBy { it.id }
        // Songs removed from the library since are left out.
        val items = ids.mapNotNull { byId[it] }
        if (items.isEmpty()) return false
        val index = items.indexOfFirst { it.id == currentId }
        val position = if (index >= 0) saved[RESUME_POSITION]?.toLongOrNull()?.coerceAtLeast(0) ?: 0 else 0
        player.setQueue(items.map { it.toQueueItem() }, index.coerceAtLeast(0), playWhenReady = false, startPositionMs = position)
        return true
    }

    /** Saves the queue (in play order), the current song and its position for Resume on start. */
    private fun rememberQueue() {
        scope.launch {
            player.state.map { s -> s.queue.joinToString(",") { it.id.toString() } }.distinctUntilChanged()
                .collect { ids -> if (ids.isNotEmpty()) put(RESUME_QUEUE, ids) }
        }
        scope.launch {
            // While playing, the position is saved every few seconds; when paused or seeking, exactly.
            player.state.filter { it.current != null }
                .map { s -> Triple(s.index, s.isPlaying, if (s.isPlaying) s.positionMs / SAVE_POSITION_EVERY_MS else s.positionMs) }
                .distinctUntilChanged()
                .collect { (index, _, _) ->
                    put(RESUME_INDEX, index.toString())
                    put(RESUME_POSITION, player.state.value.positionMs.toString())
                }
        }
    }

    /** Starts a library song with the full library in the queue, independent of the visible search results. */
    fun play(tracks: List<TrackEntity>, index: Int) {
        val selected = tracks.getOrNull(index) ?: return
        val library = this.tracks.value
        val libraryIndex = library.indexOfFirst { it.id == selected.id }
        check(libraryIndex >= 0) { "Song is no longer in the library" }
        loadQueue(library, play = true, libraryIndex)
    }

    /** Starts a song from a playlist while keeping that playlist as the queue. */
    fun playPlaylistSong(songs: List<TrackEntity>, index: Int) {
        if (songs.isEmpty()) return
        require(index in songs.indices) { "Playlist song index is out of range" }
        loadQueue(songs, play = true, index)
    }

    /** An album's or artist's Play (in order) and Shuffle buttons. */
    fun playAll(tracks: List<TrackEntity>, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        setShuffle(shuffle)
        play(tracks, if (shuffle) tracks.indices.random() else 0)
    }

    suspend fun createPlaylist(name: String): PlaylistEntity = playlistLock.withLock {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Playlist name can't be empty" }
        val uniqueName = uniquePlaylistName(cleanName)
        val playlist = PlaylistEntity(name = uniqueName, createdMs = platform.currentTimeMs())
        val id = playlistsDao.insert(playlist)
        playlist.copy(id = id).also { message("Playlist created") }
    }

    suspend fun renamePlaylist(id: Long, name: String): PlaylistEntity = playlistLock.withLock {
        val current = playlistsDao.get(id) ?: throw NoSuchElementException("Playlist not found")
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Playlist name can't be empty" }
        val renamed = current.copy(name = uniquePlaylistName(cleanName, id))
        playlistsDao.update(renamed)
        message("Playlist renamed")
        renamed
    }

    suspend fun deletePlaylist(id: Long) = playlistLock.withLock {
        val playlist = playlistsDao.get(id) ?: throw NoSuchElementException("Playlist not found")
        check(playlistsDao.delete(id) > 0) { "Playlist not found" }
        if (playlist.coverPath != null) platform.deletePlaylistCover(playlist.coverPath)
        message("Playlist deleted")
    }

    /** Returns false, with a notice, when the song is already in this playlist. */
    suspend fun addToPlaylist(playlistId: Long, trackId: Long): Boolean = playlistLock.withLock {
        val playlist = playlistsDao.get(playlistId) ?: throw NoSuchElementException("Playlist not found")
        val trackExists = tracksDao.observeAll().first().any { it.id == trackId }
        require(trackExists) { "Song not found in the library" }
        val items = playlistsDao.itemsFor(playlistId)
        if (items.any { it.trackId == trackId }) {
            message("Already in ${playlist.name}")
            return@withLock false
        }
        val position = (items.maxOfOrNull { it.position } ?: -1) + 1
        val inserted = playlistsDao.insertItem(PlaylistItemEntity(playlistId = playlistId, trackId = trackId, position = position))
        if (inserted < 0) {
            message("Already in ${playlist.name}")
            return@withLock false
        }
        message("Added to ${playlist.name}")
        true
    }

    suspend fun removeFromPlaylist(playlistId: Long, trackId: Long): Boolean = playlistLock.withLock {
        check(playlistsDao.get(playlistId) != null) { "Playlist not found" }
        val removed = playlistsDao.deleteItem(playlistId, trackId) > 0
        if (removed) message("Removed from playlist")
        removed
    }

    /** Moves [trackId] up or down relative to the other songs that are currently in the library. */
    suspend fun movePlaylistTrack(playlistId: Long, trackId: Long, direction: Int): Boolean = playlistLock.withLock {
        require(direction == -1 || direction == 1) { "Direction must be -1 or 1" }
        check(playlistsDao.get(playlistId) != null) { "Playlist not found" }
        val items = playlistsDao.itemsFor(playlistId)
        val libraryIds = tracksDao.observeAll().first().mapTo(HashSet()) { it.id }
        val visible = items.filter { it.trackId in libraryIds }
        val currentIndex = visible.indexOfFirst { it.trackId == trackId }
        val targetIndex = currentIndex + direction
        if (currentIndex < 0 || targetIndex !in visible.indices) return@withLock false
        val first = visible[currentIndex]
        val second = visible[targetIndex]
        playlistsDao.updateItems(
            listOf(
                first.copy(position = second.position),
                second.copy(position = first.position),
            ),
        )
        true
    }

    suspend fun setPlaylistCover(playlistId: Long, fileName: String, bytes: ByteArray) = playlistLock.withLock {
        val playlist = playlistsDao.get(playlistId) ?: throw NoSuchElementException("Playlist not found")
        require(bytes.isNotEmpty() && bytes.size <= TagRules.MAX_COVER_BYTES) { "Cover image must be 10 MB or smaller" }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(extension in PLAYLIST_COVER_EXTENSIONS) { "Playlist cover must be a JPEG or PNG image" }
        val path = platform.savePlaylistCover(playlistId, extension, bytes)
        playlistsDao.update(playlist.copy(coverPath = path))
        if (playlist.coverPath != null && playlist.coverPath != path) platform.deletePlaylistCover(playlist.coverPath)
        message("Playlist cover updated")
    }

    suspend fun clearPlaylistCover(playlistId: Long) = playlistLock.withLock {
        val playlist = playlistsDao.get(playlistId) ?: throw NoSuchElementException("Playlist not found")
        playlistsDao.update(playlist.copy(coverPath = null))
        if (playlist.coverPath != null) platform.deletePlaylistCover(playlist.coverPath)
        message("Playlist cover removed")
    }

    suspend fun playPlaylist(playlistId: Long, shuffle: Boolean = false) {
        check(playlistsDao.get(playlistId) != null) { "Playlist not found" }
        val byId = tracksDao.observeAll().first().associateBy { it.id }
        val songs = playlistsDao.itemsFor(playlistId).sortedBy { it.position }.mapNotNull { byId[it.trackId] }
        if (songs.isEmpty()) {
            message("This playlist has no available songs")
            return
        }
        if (songs.isNotEmpty()) {
            setShuffle(shuffle)
            loadQueue(songs, play = true, if (shuffle) songs.indices.random() else 0)
        }
    }

    suspend fun exportM3u(
        playlistId: Long,
        destination: String,
        paths: M3uPathMode = M3uPathMode.Relative,
        trackInfo: Boolean = true,
    ) {
        val playlist = playlistsDao.get(playlistId) ?: throw NoSuchElementException("Playlist not found")
        val library = tracksDao.observeAll().first().associateBy { it.id }
        val songs = playlistsDao.itemsFor(playlistId).sortedBy { it.position }.mapNotNull { library[it.trackId] }
        val musicFolder = settings.filterNotNull().first().musicFolder
        if (paths == M3uPathMode.Relative) requireNotNull(musicFolder) { "Choose a music folder before exporting relative paths" }
        val entries = songs.map { song ->
            M3uTrack(
                fullPath = song.location,
                relativePath = musicFolder?.let { platform.relativePlaylistPath(song, it) } ?: song.location,
                title = song.displayTitle,
                artist = song.artist,
                durationMs = song.durationMs,
            )
        }
        platform.writeExport(
            destination,
            M3u.format(entries, paths, trackInfo, playlist.name).encodeToByteArray(),
        )
        message("Exported ${playlist.name}")
    }

    suspend fun exportPlaylistBundle(
        playlistId: Long,
        destination: String,
        includeLyrics: Boolean = true,
        onProgress: (PlaylistBundleExportProgress) -> Unit = {},
    ) {
        val playlist = playlistsDao.get(playlistId) ?: throw NoSuchElementException("Playlist not found")
        val library = tracksDao.observeAll().first().associateBy { it.id }
        val songs = playlistsDao.itemsFor(playlistId).sortedBy { it.position }.mapNotNull { library[it.trackId] }
        platform.writePlaylistBundle(destination, playlist.name, songs, playlist.coverPath, songs.firstOrNull(), includeLyrics, onProgress)
        message("Exported ${playlist.name}")
    }

    suspend fun previewM3uImport(location: String): M3uImportPreview {
        val source = platform.readPlaylistText(location)
        val document = M3u.parse(source.text)
        val musicFolder = settings.filterNotNull().first().musicFolder
        val library = tracksDao.observeAll().first()
        val items = document.entries.map { entry ->
            val resolved = musicFolder?.let { platform.resolvePlaylistPath(entry.path, location, it) }
            val track = M3u.match(listOf(entry), library) { resolved ?: entry.path }.matched.firstOrNull()?.track
            M3uImportItem(entry, track)
        }
        val fallbackName = source.name.substringBeforeLast('.', source.name).trim().ifEmpty { "Imported Playlist" }
        return M3uImportPreview(document.playlistName?.takeIf(String::isNotBlank) ?: fallbackName, items)
    }

    suspend fun previewPlaylistBundle(location: String): PlaylistBundlePreview =
        platform.readPlaylistBundle(location, tracksDao.observeAll().first())

    suspend fun importM3u(preview: M3uImportPreview, name: String = preview.name): PlaylistEntity = playlistLock.withLock {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Playlist name can't be empty" }
        val uniqueName = uniquePlaylistName(cleanName)
        val playlist = PlaylistEntity(name = uniqueName, createdMs = platform.currentTimeMs())
        val tracksToAdd = preview.items.mapNotNull { it.track }.distinctBy { it.id }
        val items = tracksToAdd.mapIndexed { index, track ->
            PlaylistItemEntity(playlistId = 0, trackId = track.id, position = index)
        }
        val id = playlistsDao.insertWithItems(playlist, items)
        message("Imported $uniqueName · ${tracksToAdd.size} songs")
        playlist.copy(id = id)
    }

    suspend fun importPlaylistBundle(preview: PlaylistBundlePreview, name: String = preview.name): PlaylistEntity = playlistLock.withLock {
        var importFailure: Exception? = null
        try {
            val cleanName = name.trim()
            require(cleanName.isNotEmpty()) { "Playlist name can't be empty" }
            val uniqueName = uniquePlaylistName(cleanName)
            val folder = settings.filterNotNull().first().musicFolder ?: throw IllegalStateException("Choose a music folder before importing a playlist")
            val newSongs = preview.songs.filter { it.existingTrack == null }
            val importedFiles = if (newSongs.isEmpty()) {
                emptyList()
            } else {
                platform.importSongs(
                    uniqueName,
                    folder,
                    newSongs.map { PlaylistImportFile(it.sourcePath, it.fileName, it.lyricsPath) },
                )
            }
            check(importedFiles.size == newSongs.size) { "Not all playlist songs were copied" }

            if (newSongs.isNotEmpty()) {
                scanJob?.join()
                quietScan?.join()
                saveLock.withLock { scanner.scan(platform.sourceFor(folder)) }
            }
            val library = tracksDao.observeAll().first()
            val byId = library.associateBy { it.id }
            val byLocation = library.associateBy { it.location }
            val importedBySource = newSongs.zip(importedFiles).associate { (song, imported) -> song.sourcePath to imported }
            val unresolvedNewSongs = newSongs.filter { song ->
                val location = importedBySource[song.sourcePath]?.location
                location == null || (location !in byLocation && song.tags?.let {
                    matchTrackByTags(it.title, it.artist, it.durationMs, library)
                } == null)
            }
            check(unresolvedNewSongs.isEmpty()) { "Some copied songs couldn't be added to the library" }
            val trackIds = preview.songs.mapNotNull { song ->
                song.existingTrack?.id?.takeIf { it in byId }
                    ?: importedBySource[song.sourcePath]?.let { byLocation[it.location]?.id }
                    ?: song.tags?.let { matchTrackByTags(it.title, it.artist, it.durationMs, library)?.id }
            }.distinct()
            val playlist = PlaylistEntity(name = uniqueName, createdMs = platform.currentTimeMs())
            val items = trackIds.mapIndexed { index, trackId ->
                PlaylistItemEntity(playlistId = 0, trackId = trackId, position = index)
            }
            val id = playlistsDao.insertWithItems(playlist, items)
            var coverPath: String? = null
            try {
                if (preview.coverBytes != null) {
                    val extension = preview.coverExtension ?: throw IllegalArgumentException("Playlist bundle cover has no file extension")
                    coverPath = platform.savePlaylistCover(id, extension, preview.coverBytes)
                    playlistsDao.update(playlist.copy(id = id, coverPath = coverPath))
                }
            } catch (error: Exception) {
                playlistsDao.delete(id)
                if (coverPath != null) {
                    try {
                        platform.deletePlaylistCover(coverPath)
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }
            message("Imported $uniqueName · ${trackIds.size} songs")
            playlist.copy(id = id, coverPath = coverPath)
        } catch (error: Exception) {
            importFailure = error
            throw error
        } finally {
            try {
                platform.cleanupPlaylistBundlePreview(preview)
            } catch (cleanupError: Exception) {
                val failure = importFailure
                if (failure == null) throw cleanupError
                failure.addSuppressed(cleanupError)
            }
        }
    }

    private suspend fun uniquePlaylistName(name: String, exceptId: Long = -1): String {
        if (playlistsDao.countNamed(name, exceptId) == 0) return name
        var suffix = 2
        while (playlistsDao.countNamed("$name ($suffix)", exceptId) > 0) suffix++
        return "$name ($suffix)"
    }

    /** The disc's play button: starts the library if nothing is queued yet. */
    fun togglePlay() {
        val s = player.state.value
        when {
            s.current == null -> if (tracks.value.isNotEmpty()) play(tracks.value, 0)
            s.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    private fun loadQueue(list: List<TrackEntity>, play: Boolean, index: Int = 0) {
        if (list.isNotEmpty()) player.setQueue(list.map { it.toQueueItem() }, index, play)
    }

    fun accessChanged() {
        _accessRevision.value++
    }

    /** Whether [track]'s file can be changed by the song details editor. */
    suspend fun editSupport(track: TrackEntity): EditSupport = try {
        platform.editSupport(track)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EditSupport.ReadOnly("The file can't be checked right now")
    }

    /**
     * Saves [edit] into [track]'s file, then updates the library, the queue and the cover art.
     * Returns null when saved, or a message for the editor to show (the file is then unchanged).
     * Where saving rewrites the file in place (Android) and the song is playing, it pauses for the
     * save and then carries on from the same spot.
     */
    suspend fun saveTags(track: TrackEntity, edit: TagEdit): String? = saveLock.withLock {
        // Let a running scan finish, so it can't write the old tags back afterwards.
        scanJob?.join()
        quietScan?.join()
        val before = player.state.value
        val release = platform.rewritesInPlace && before.current?.id == track.id
        if (release) player.pause()
        val saved: SavedFile = try {
            platform.saveTags(track, edit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (release) player.updateItem(track.toQueueItem(), reload = true, play = before.isPlaying)
            return@withLock if (e is TagSaveException) "Couldn't save: ${e.message}" else "Couldn't save the song details. The file wasn't changed."
        }
        val updated = trackEntity(track.id, FileEntry(track.location, track.fileName, saved.sizeBytes, saved.modifiedMs), saved.tags)
        tracksDao.upsert(listOf(updated))
        artwork.invalidate(track.location)
        player.updateItem(updated.toQueueItem(), reload = release, play = release && before.isPlaying)
        message("Song details saved")
        null
    }

    private val lyricsLock = Mutex()
    /** Lyrics already read, by song location (null = the song has none). */
    private val lyricsCache = HashMap<String, Lyrics?>()
    private val _lyricsRevision = MutableStateFlow(0)
    /** Goes up when a song's lyrics change, so lyrics on screen are read again. */
    val lyricsRevision: StateFlow<Int> = _lyricsRevision.asStateFlow()

    /** [track]'s lyrics (its `.lrc`, else lyrics inside the song), or null. Read once, then kept. */
    suspend fun lyrics(track: TrackEntity): Lyrics? {
        val key = track.location + "|" + track.lyricsLocation
        lyricsLock.withLock { if (key in lyricsCache) return lyricsCache[key] }
        val lyrics = try {
            platform.readLyrics(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        lyricsLock.withLock { lyricsCache[key] = lyrics }
        return lyrics
    }

    /** Whether lyrics can be saved for [track]. */
    suspend fun lyricsSupport(track: TrackEntity): EditSupport = try {
        platform.lyricsSupport(track)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EditSupport.ReadOnly("The folder can't be checked right now")
    }

    /**
     * Saves [lines] as [track]'s `.lrc` (sorted by time; untimed lines keep their place), or
     * removes the `.lrc` when no line has text. Returns null when saved, or a message for the editor.
     */
    suspend fun saveLyrics(track: TrackEntity, lines: List<LyricLine>): String? {
        val kept = Lrc.sortForSave(lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() })
        val name = track.fileName.substringBeforeLast('.') + ".lrc"
        // Header tags of an existing .lrc are kept; lyrics from inside the song have none to keep.
        val tags = lyrics(track)?.takeIf { !it.embedded }?.tags.orEmpty()
        val newLocation = try {
            platform.saveLyrics(track, if (kept.isEmpty()) null else Lrc.format(Lyrics(kept, tags)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (e is TagSaveException) "Couldn't save the lyrics: ${e.message}" else "Couldn't save the lyrics. Nothing was changed."
        }
        tracksDao.updateSidecar(track.location, track.folder, newLocation)
        lyricsLock.withLock { lyricsCache.keys.removeAll { it.startsWith(track.location + "|") } }
        _lyricsRevision.value++
        val untimed = kept.count { it.timeMs == null }
        message(
            when {
                kept.isEmpty() -> "Lyrics removed"
                untimed > 0 -> "Saved $name · $untimed not timed"
                else -> "Saved $name"
            },
        )
        return null
    }

    fun setShowLyrics(on: Boolean) = setFlag(AppSettings.SHOW_LYRICS, on)

    fun setShuffle(enabled: Boolean) {
        player.setShuffle(enabled)
        put(AppSettings.SHUFFLE, enabled.toString())
    }

    fun setRepeatMode(mode: RepeatMode) {
        player.setRepeatMode(mode)
        put(AppSettings.REPEAT_MODE, mode.name)
    }

    /**
     * The lyrics editor's play button for a song that isn't the current one: plays [track] from
     * the queue if it's there, otherwise within the library (like tapping it in the Library).
     */
    fun playForEditing(track: TrackEntity) {
        val s = player.state.value
        if (s.current?.id == track.id) return player.play()
        val inQueue = s.queue.indexOfFirst { it.id == track.id }
        if (inQueue >= 0) {
            player.skipTo(inQueue)
        } else {
            val library = tracks.value
            loadQueue(library, play = true, index = library.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        }
    }

    fun setTheme(mode: ThemeMode) = put(AppSettings.THEME, mode.name)

    fun setAccent(preset: AccentPreset) = put(AppSettings.ACCENT, preset.name)

    fun setCustomAccent(argb: Long) = scope.launch {
        put(AppSettings.CUSTOM_ACCENT, (argb and 0xFFFFFF).toString(16).padStart(6, '0'))
        put(AppSettings.ACCENT, AppSettings.CUSTOM)
    }

    fun setFlag(name: String, value: Boolean) = put(name, value.toString())

    private fun put(name: String, value: String) = scope.launch { settingsDao.put(SettingEntity(name, value)) }

    private companion object {
        const val RESUME_QUEUE = "resumeQueue"
        const val RESUME_INDEX = "resumeIndex"
        const val RESUME_POSITION = "resumePosition"
        const val SAVE_POSITION_EVERY_MS = 5_000L
        const val WATCH_SETTLE_MS = 2_000L
        val PLAYLIST_COVER_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
