package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import dev.recorddisc.player.library.Album
import dev.recorddisc.player.library.Artist
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibraryScanner
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.SettingEntity
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.groupAlbums
import dev.recorddisc.player.library.groupArtists
import dev.recorddisc.player.library.searchText
import dev.recorddisc.player.playback.MusicPlayer
import dev.recorddisc.player.playback.toQueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What each platform provides to the shared app. */
interface LibraryPlatform {
    /** Folder to offer on first run (desktop: the Music folder), or null to ask (Android). */
    val suggestedFolder: String?
    fun sourceFor(folder: String): LibrarySource
    /** Short name to show: "~/Music" on desktop, "Music" on Android. */
    fun folderLabel(folder: String): String
    /** Embedded cover art, scaled so its longest side is at most [maxSizePx]; null if none. */
    suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap?
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

    init {
        scope.launch {
            val s = settings.filterNotNull().first()
            val folder = s.musicFolder
            if (s.setupDone && folder != null) {
                // Show the library straight away, then pick up any changes made since last time.
                loadQueue(tracksDao.observeAll().first(), play = false)
                rescan(folder)
            }
        }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** First run / folder change: scans [folder]; [ScanState.Done] says how much was found. */
    fun scan(folder: String) {
        scanJob?.cancel()
        scanJob = scope.launch {
            _scan.value = ScanState.Running(folder, 0, "")
            try {
                scanner.scan(platform.sourceFor(folder)) { checked, name -> _scan.value = ScanState.Running(folder, checked, name) }
                val songs = tracksDao.count()
                if (songs > 0) put(AppSettings.MUSIC_FOLDER, folder)
                _scan.value = ScanState.Done(folder, songs, tracksDao.observeAlbums().first().size, tracksDao.observeArtists().first().size)
            } catch (e: CancellationException) {
                _scan.value = ScanState.Idle
                throw e
            } catch (e: Exception) {
                _scan.value = ScanState.Failed(folder, e.message ?: "Couldn't read ${platform.folderLabel(folder)}")
            }
        }
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

    /** Quiet background rescan; the library updates when it finishes. */
    private fun rescan(folder: String) = scope.launch {
        try {
            scanner.scan(platform.sourceFor(folder))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message("Couldn't rescan ${platform.folderLabel(folder)}")
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

    fun setAccent(preset: AccentPreset) = put(AppSettings.ACCENT, preset.name)

    fun setCustomAccent(argb: Long) = scope.launch {
        put(AppSettings.CUSTOM_ACCENT, (argb and 0xFFFFFF).toString(16).padStart(6, '0'))
        put(AppSettings.ACCENT, AppSettings.CUSTOM)
    }

    fun setFlag(name: String, value: Boolean) = put(name, value.toString())

    private fun put(name: String, value: String) = scope.launch { settingsDao.put(SettingEntity(name, value)) }
}
