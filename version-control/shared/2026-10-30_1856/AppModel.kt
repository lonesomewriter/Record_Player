package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import dev.recorddisc.player.library.Album
import dev.recorddisc.player.library.Artist
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibraryScanner
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.SavedFile
import dev.recorddisc.player.library.SettingEntity
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagSaveException
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.groupAlbums
import dev.recorddisc.player.library.groupArtists
import dev.recorddisc.player.library.searchText
import dev.recorddisc.player.library.trackEntity
import dev.recorddisc.player.playback.MusicPlayer
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
    /** Whether [track]'s file can be changed by the song details editor. */
    suspend fun editSupport(track: TrackEntity): EditSupport = EditSupport.ReadOnly("Editing isn't available here")
    /** Saves [edit] into [track]'s file. Throws with a message for the user if it can't; the file is then unchanged. */
    suspend fun saveTags(track: TrackEntity, edit: TagEdit): SavedFile = throw UnsupportedOperationException("Editing isn't available here")
    /** True where saving rewrites the song file in place (Android), so the player has to let go of it first. */
    val rewritesInPlace: Boolean get() = false
    /** Finishes a save the app couldn't complete last time; returns a notice for the user, or null. */
    suspend fun recoverPendingSaves(): String? = null
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
    private val scanner = LibraryScanner(tracksDao)

    /** Null until loaded from the database. */
    val settings: StateFlow<AppSettings?> = settingsDao.observeAll()
        .map { rows -> AppSettings.from(rows.associate { it.name to it.value }) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val tracks: StateFlow<List<TrackEntity>> = tracksDao.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Short notices for a toast or snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val artwork = ArtworkCache(platform)

    /** Held while song details are being saved; scans wait for it. Declared before init, which can start a scan. */
    private val saveLock = Mutex()

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
            val folder = s.musicFolder
            if (s.setupDone && folder != null) {
                // Show the library straight away (or the queue as it was left), then pick up any
                // changes made since last time.
                val library = tracksDao.observeAll().first()
                if (!(s.resumeOnStart && restoreQueue(library))) loadQueue(library, play = false)
                rescan(folder)
            }
            rememberQueue()
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

    fun play(tracks: List<TrackEntity>, index: Int) = loadQueue(tracks, play = true, index)

    /** An album's or artist's Play (in order) and Shuffle buttons. */
    fun playAll(tracks: List<TrackEntity>, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        player.setShuffle(shuffle)
        play(tracks, if (shuffle) tracks.indices.random() else 0)
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
    }
}
