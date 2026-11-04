package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.LyricLine
import dev.recorddisc.player.library.M3u
import dev.recorddisc.player.library.M3uPathMode
import dev.recorddisc.player.library.PlaylistEntity
import dev.recorddisc.player.library.PlaylistBundlePreview
import dev.recorddisc.player.library.PlaylistBundlePreviewSong
import dev.recorddisc.player.library.PlaylistImportFile
import dev.recorddisc.player.library.PlaylistTextFile
import dev.recorddisc.player.library.SettingEntity
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.playback.MusicPlayer
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.QueueItem
import dev.recorddisc.player.playback.RepeatMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val databases = ArrayList<LibraryDatabase>()

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        databases.forEach { it.close() }
    }

    private fun database(file: File = File(tmp.root, "library.db")): LibraryDatabase =
        Room.databaseBuilder<LibraryDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { databases += it }

    private fun <T> StateFlow<T>.await(what: String, check: (T) -> Boolean): T = runBlocking {
        runCatching { withTimeout(5_000) { first(check) } }.getOrElse { throw AssertionError("Timed out waiting for $what (last: $value)") }
    }

    @Test
    fun firstRunScansSavesTheFolderAndQueuesTheLibrary() {
        val player = FakePlayer()
        val model = AppModel(database(), player, FakePlatform(), scope)
        assertEquals(false, model.settings.await("settings") { it != null }!!.setupDone)

        model.scan("/music")
        val done = model.scan.await("scan done") { it is ScanState.Done } as ScanState.Done
        assertEquals(ScanState.Done("/music", songs = 3, albums = 2, artists = 2), done)
        model.settings.await("folder saved") { it?.musicFolder == "/music" }
        assertEquals(false, model.settings.value!!.setupDone, "still in first run until Start listening")

        model.finishSetup()
        model.settings.await("setup done") { it?.setupDone == true }
        assertEquals(ScanState.Idle, model.scan.value)
        val (queue, index, play) = runBlocking { withTimeout(5_000) { while (player.queues.isEmpty()) kotlinx.coroutines.delay(20) }; player.queues.single() }
        assertEquals(listOf("Midnight Drive", "Paper Lanterns", "Static Bloom"), queue.map { it.title })
        assertEquals(0, index)
        assertEquals(false, play, "ready to play, not playing")
    }

    @Test
    fun emptyOrUnreadableFoldersAreNotSaved() {
        val model = AppModel(database(), FakePlayer(), FakePlatform(), scope)
        model.scan("/empty")
        assertEquals(0, (model.scan.await("empty done") { it is ScanState.Done } as ScanState.Done).songs)

        model.scan("/broken")
        val failed = model.scan.await("failed") { it is ScanState.Failed } as ScanState.Failed
        assertEquals("Permission denied", failed.message)
        Thread.sleep(200)
        assertNull(model.settings.value!!.musicFolder)
    }

    @Test
    fun laterLaunchesQueueTheLibraryWithoutPlaying() {
        val file = File(tmp.root, "existing.db")
        val first = AppModel(database(file), FakePlayer(), FakePlatform(), scope)
        first.scan("/music")
        first.scan.await("scan done") { it is ScanState.Done }
        first.finishSetup()
        first.settings.await("setup done") { it?.setupDone == true }

        val player = FakePlayer()
        val again = AppModel(database(file), player, FakePlatform(), scope)
        player.state.await("library queued") { it.queue.size == 3 }
        assertEquals(false, player.state.value.isPlaying)
        assertEquals(ScanState.Idle, again.scan.value, "the startup rescan runs quietly")
    }

    /** First run on /music in [file], ready to use. */
    private fun setUp(file: File, player: FakePlayer = FakePlayer(), platform: FakePlatform = FakePlatform()): AppModel {
        return readyModel(database(file), player, platform)
    }

    private fun readyModel(db: LibraryDatabase, player: FakePlayer, platform: FakePlatform): AppModel {
        val model = AppModel(db, player, platform, scope)
        model.scan("/music")
        model.scan.await("scan done") { it is ScanState.Done }
        model.tracks.await("initial tracks") { it.size == 3 }
        model.finishSetup()
        model.settings.await("setup done") { it?.setupDone == true }
        model.initialized.await("app model initialized") { it }
        return model
    }

    @Test
    fun resumeOnStartReopensTheQueueWhereItWasLeftPaused() {
        val file = File(tmp.root, "resume.db")
        val firstPlayer = FakePlayer()
        val first = setUp(file, firstPlayer)
        val library = first.tracks.await("tracks") { it.size == 3 }
        first.play(library, 1)
        firstPlayer.seekTo(83_000)
        firstPlayer.pause()
        // Saving happens in the background; wait until the paused position is stored.
        val saved = database(file).settings()
        runBlocking { withTimeout(5_000) { while (saved.observeAll().first().none { it.name == "resumePosition" && it.value == "83000" }) delay(50) } }

        val player = FakePlayer()
        AppModel(database(file), player, FakePlatform(), scope)
        val restored = player.state.await("queue restored") { it.queue.size == 3 }
        assertEquals(library[1].id, restored.current?.id)
        assertEquals(false, restored.isPlaying)
        assertEquals(83_000, restored.positionMs, "same position")
    }

    @Test
    fun withResumeOffTheLibraryStartsFromTheTop() {
        val file = File(tmp.root, "noresume.db")
        val firstPlayer = FakePlayer()
        val first = setUp(file, firstPlayer)
        first.play(first.tracks.await("tracks") { it.size == 3 }, 2)
        first.setFlag(AppSettings.RESUME_ON_START, false)
        first.settings.await("resume off") { it?.resumeOnStart == false }
        Thread.sleep(300)

        val player = FakePlayer()
        AppModel(database(file), player, FakePlatform(), scope)
        val queued = player.state.await("library queued") { it.queue.size == 3 }
        assertEquals(0, queued.index)
        assertEquals(0, queued.positionMs)
    }

    @Test
    fun changingTheFolderReplacesTheLibraryButAnEmptyFolderDoesNot() {
        val platform = FakePlatform()
        val model = setUp(File(tmp.root, "change.db"), platform = platform)
        val messages = ArrayList<String>()
        scope.launch { model.messages.collect { messages += it } }
        Thread.sleep(100)

        model.scan("/empty")
        runBlocking { withTimeout(5_000) { while (messages.isEmpty()) delay(20) } }
        assertTrue(messages.last().startsWith("No music found"), messages.last())
        assertEquals(3, model.tracks.value.size)
        assertEquals("/music", model.settings.value?.musicFolder)
        assertEquals(ScanState.Idle, model.scan.value, "Settings scans don't show the first-run screens")

        model.scan("/other")
        model.settings.await("new folder saved") { it?.musicFolder == "/other" }
        assertEquals(listOf("Copper Sky"), model.tracks.await("library replaced") { it.size == 1 }.map { it.title })
        assertEquals(listOf("/music"), platform.released, "the old folder's access is given back")
    }

    @Test
    fun watchedFolderRescansAfterChangesSettle() {
        val platform = FakePlatform()
        val model = setUp(File(tmp.root, "watch.db"), platform = platform)
        val (folder, signal) = runBlocking { withTimeout(5_000) { while (platform.watching == null) delay(20); platform.watching!! } }
        assertEquals("/music", folder)
        platform.folders["/music"] = platform.folders.getValue("/music") + Triple("Low Tide", "Neon Coast", "Night Swim")
        signal()
        signal()
        model.tracks.await("new song picked up") { it.size == 4 }

        model.setFlag(AppSettings.WATCH_FOLDER, false)
        runBlocking { withTimeout(5_000) { while (platform.watching != null) delay(20) } }
    }

    @Test
    fun playButtonStartsTheLibraryWhenNothingIsQueued() {
        val player = FakePlayer()
        val model = AppModel(database(), player, FakePlatform(), scope)
        model.scan("/music")
        model.scan.await("scan done") { it is ScanState.Done }
        model.tracks.await("tracks") { it.size == 3 }

        model.togglePlay()
        assertEquals(true, player.state.value.isPlaying)
        assertEquals(3, player.state.value.queue.size)
        model.togglePlay()
        assertEquals(false, player.state.value.isPlaying)
    }

    @Test
    fun librarySearchMatchesTitlesArtistsAndAlbums() {
        val model = AppModel(database(), FakePlayer(), FakePlatform(), scope)
        model.scan("/music")
        model.librarySongs.await("all songs") { it.size == 3 }
        model.setLibraryQuery("neon")
        assertEquals(listOf("Midnight Drive", "Paper Lanterns"), model.librarySongs.await("artist match") { it.size == 2 }.map { it.title })
        model.setLibraryQuery("slow light")
        assertEquals(listOf("Static Bloom"), model.librarySongs.await("album match") { it.size == 1 }.map { it.title })
        model.setLibraryQuery("100%")
        model.librarySongs.await("no match; % taken literally") { it.isEmpty() }
        model.setLibraryQuery("")
        model.librarySongs.await("all again") { it.size == 3 }
    }

    @Test
    fun playingASearchResultQueuesTheFullLibrary() {
        val player = FakePlayer()
        val model = AppModel(database(), player, FakePlatform(), scope)
        model.scan("/music")
        val library = model.tracks.await("library loaded") { it.size == 3 }
        model.setLibraryQuery("neon")
        val results = model.librarySongs.await("search results") { it.size == 2 }

        model.play(results, 1)

        val (queue, index, play) = player.queues.last()
        assertEquals(library.map { it.id }, queue.map { it.id })
        assertEquals(library.indexOfFirst { it.id == results[1].id }, index)
        assertTrue(play)
    }

    @Test
    fun albumsAndArtistsFollowTheSearch() {
        val model = AppModel(database(), FakePlayer(), FakePlatform(), scope)
        model.scan("/music")
        assertEquals(listOf("After Hours", "Slow Light"), model.libraryAlbums.await("all albums") { it.size == 2 }.map { it.name })
        assertEquals(listOf("Mira Vale", "Neon Coast"), model.libraryArtists.await("all artists") { it.size == 2 }.map { it.name })
        model.setLibraryQuery("static")
        assertEquals(listOf("Slow Light"), model.libraryAlbums.await("matching album") { it.size == 1 }.map { it.name })
        assertEquals(listOf("Mira Vale"), model.libraryArtists.await("matching artist") { it.size == 1 }.map { it.name })
        // Pages always show the whole album or artist.
        assertEquals(2, model.albums.value.size)
    }

    @Test
    fun albumPlayAndShuffleSetShuffleAndStartPlaying() {
        val player = FakePlayer()
        val model = AppModel(database(), player, FakePlatform(), scope)
        model.initialized.await("app model initialized") { it }
        model.scan("/music")
        val library = model.tracks.await("tracks") { it.size == 3 }
        val album = model.albums.await("albums") { it.size == 2 }.first { it.name == "After Hours" }
        model.playAll(album.tracks, shuffle = true)
        assertTrue(player.shuffleOn)
        model.settings.await("shuffle preference saved") { it?.shuffle == true }
        assertEquals(library.map { it.id }, player.queues.last().first.map { it.id })
        assertTrue(player.queues.last().first[player.queues.last().second].id in album.tracks.map { it.id })
        assertTrue(player.queues.last().third)
        model.playAll(album.tracks, shuffle = false)
        assertEquals(false, player.shuffleOn)
        model.settings.await("shuffle preference cleared") { it?.shuffle == false }
        assertEquals(library.map { it.id }, player.queues.last().first.map { it.id })
        assertEquals(library.indexOfFirst { it.id == album.tracks.first().id }, player.queues.last().second)
    }

    @Test
    fun accentChoicesAreSaved() {
        val model = AppModel(database(), FakePlayer(), FakePlatform(), scope)
        model.setAccent(AccentPreset.Mint)
        assertEquals(AccentPreset.Mint.argb, model.settings.await("mint") { it?.accent == AccentPreset.Mint }!!.accentArgb)
        model.setCustomAccent(0xFF123456)
        val custom = model.settings.await("custom") { it?.accent == null }!!
        assertEquals(0xFF123456, custom.accentArgb)
    }

    @Test
    fun themeChoiceIsSavedAndDefaultsToDark() {
        val model = AppModel(database(), FakePlayer(), FakePlatform(), scope)
        assertEquals(ThemeMode.Dark, model.settings.await("settings") { it != null }!!.theme)
        model.setTheme(ThemeMode.Light)
        model.settings.await("light") { it?.theme == ThemeMode.Light }
        model.setTheme(ThemeMode.System)
        model.settings.await("system") { it?.theme == ThemeMode.System }
    }

    private suspend fun libraryWithQueue(platform: FakePlatform, player: FakePlayer): Pair<AppModel, TrackEntity> {
        val model = AppModel(database(), player, platform, scope)
        model.scan("/music")
        val tracks = model.tracks.await("tracks") { it.size == 3 }
        val song = tracks.first { it.title == "Paper Lanterns" }
        model.playAll(tracks, shuffle = false)
        player.skipTo(tracks.indexOf(song))
        return model to song
    }

    private val edit = TagEdit("Lanterns (Remaster)", "Neon Coast", "After Hours", "Neon Coast", 2024, 2, "Synthwave")

    @Test
    fun savedTagsUpdateTheLibraryAndQueue() = runBlocking {
        val platform = FakePlatform()
        val player = FakePlayer()
        val (model, song) = libraryWithQueue(platform, player)
        val revision = model.artwork.revision.value
        assertNull(model.saveTags(song, edit))
        val updated = model.tracks.await("saved") { list -> list.any { it.title == "Lanterns (Remaster)" } }.first { it.id == song.id }
        assertEquals(2024, updated.year)
        assertEquals(110, updated.sizeBytes)
        assertEquals("Lanterns (Remaster)", player.state.value.current?.title)
        // Desktop swaps the file atomically: the song isn't reloaded.
        assertEquals(false, player.updates.last().second)
        assertTrue(model.artwork.revision.value > revision)
        // A later scan sees the file as unchanged and doesn't read it again.
        val reads = platform.tagReads
        val listed = platform.listCalls
        model.rescanNow()
        withTimeout(5_000) { while (platform.listCalls == listed || model.scan.value !is ScanState.Done) delay(20) }
        assertEquals(reads, platform.tagReads)
    }

    @Test
    fun inPlaceSaveOfThePlayingSongResumesIt() = runBlocking {
        val platform = FakePlatform().apply { inPlace = true }
        val player = FakePlayer()
        val (model, song) = libraryWithQueue(platform, player)
        assertTrue(player.state.value.isPlaying)
        assertNull(model.saveTags(song, edit))
        val (_, reload, play) = player.updates.last()
        assertTrue(reload && play, "reloaded and playing again")
    }

    @Test
    fun failedSaveReturnsTheReasonAndChangesNothing() = runBlocking {
        val platform = FakePlatform().apply { failSave = "Not enough free space to save safely" }
        val player = FakePlayer()
        val (model, song) = libraryWithQueue(platform, player)
        assertEquals("Couldn't save: Not enough free space to save safely", model.saveTags(song, edit))
        assertEquals("Paper Lanterns", model.tracks.value.first { it.id == song.id }.title)
        assertTrue(player.updates.isEmpty())
    }

    @Test
    fun lyricsAreSavedSortedAndRemovedWhenEmpty() = runBlocking {
        val platform = FakePlatform()
        val (model, song) = libraryWithQueue(platform, FakePlayer())
        assertNull(model.lyrics(song))
        val lines = listOf(LyricLine(12_800, "Neon hums"), LyricLine(6_200, "Headlights"), LyricLine(null, "  "), LyricLine(null, "(spoken)"))
        assertNull(model.saveLyrics(song, lines))
        // Sorted by time and the blank line dropped; the untimed line stays after the line written
        // just before it ("Headlights"), as in the editor.
        assertEquals("[00:06.20]Headlights\n(spoken)\n[00:12.80]Neon hums\n", platform.lrcFiles[song.location])
        val saved = model.tracks.await("lyrics location") { list -> list.any { it.id == song.id && it.lyricsLocation != null } }.first { it.id == song.id }
        assertEquals(3, model.lyrics(saved)!!.lines.size)

        assertNull(model.saveLyrics(saved, emptyList()))
        assertNull(platform.lrcFiles[song.location])
        model.tracks.await("lyrics removed") { list -> list.first { it.id == song.id }.lyricsLocation == null }

        platform.failSave = "the music folder isn't available"
        assertEquals("Couldn't save the lyrics: the music folder isn't available", model.saveLyrics(saved, lines))
    }

    @Test
    fun lyricsToggleIsRemembered() {
        val model = AppModel(database(), FakePlayer(), FakePlatform(), scope)
        assertEquals(false, model.settings.await("settings") { it != null }!!.showLyrics)
        model.setShowLyrics(true)
        model.settings.await("shown") { it?.showLyrics == true }
    }

    @Test
    fun playForEditingStartsTheSong() = runBlocking {
        val player = FakePlayer()
        val (model, song) = libraryWithQueue(FakePlatform(), player)
        val other = model.tracks.value.first { it.id != song.id }
        model.playForEditing(other)
        assertEquals(other.id, player.state.value.current?.id)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun malformedSettingsFallBackToDefaults() {
        val s = AppSettings.from(
            mapOf(
                "accent" to "Plaid",
                "customAccent" to "zz",
                "setupDone" to "yes",
                "musicFolder" to " ",
                "theme" to "Sepia",
                AppSettings.SHUFFLE to "yes",
                AppSettings.REPEAT_MODE to "Loop",
            ),
        )
        assertEquals(AppSettings(), s)
        assertEquals(0xFFABCDEF, AppSettings.from(mapOf("accent" to "Custom", "customAccent" to "abcdef")).accentArgb)
    }

    @Test
    fun playbackModesAreRestoredAndPersisted() = runBlocking {
        val db = database()
        db.settings().put(SettingEntity(AppSettings.SHUFFLE, "true"))
        db.settings().put(SettingEntity(AppSettings.REPEAT_MODE, RepeatMode.All.name))
        val player = FakePlayer()
        val model = AppModel(db, player, FakePlatform(), scope)

        model.settings.await("saved playback modes") { it?.shuffle == true && it.repeatMode == RepeatMode.All }
        assertTrue(player.state.value.shuffle)
        assertEquals(RepeatMode.All, player.state.value.repeatMode)

        model.setShuffle(false)
        model.setRepeatMode(RepeatMode.One)
        model.settings.await("updated playback modes") { it?.shuffle == false && it.repeatMode == RepeatMode.One }
        assertFalse(player.state.value.shuffle)
        assertEquals(RepeatMode.One, player.state.value.repeatMode)
    }

    @Test
    fun playlistsSupportUniqueNamesSongsOrderingCoversAndPlayback() = runBlocking {
        val player = FakePlayer()
        val db = database()
        val platform = FakePlatform()
        val model = readyModel(db, player, platform)
        val songs = model.tracks.value
        val playlist = model.createPlaylist(" Road Trip ")
        val secondPlaylist = model.createPlaylist("road trip")

        assertEquals("Road Trip", playlist.name)
        assertEquals("road trip (2)", secondPlaylist.name)
        assertTrue(model.addToPlaylist(playlist.id, songs[0].id))
        assertTrue(model.addToPlaylist(playlist.id, songs[1].id))
        assertTrue(model.addToPlaylist(playlist.id, songs[2].id))
        assertFalse(model.addToPlaylist(playlist.id, songs[0].id))
        assertTrue(model.movePlaylistTrack(playlist.id, songs[2].id, -1))
        assertFalse(model.movePlaylistTrack(playlist.id, songs[0].id, -1))

        var view = model.playlists.await("playlist songs") { it.firstOrNull { p -> p.id == playlist.id }?.songs?.size == 3 }
            .first { it.id == playlist.id }
        assertEquals(listOf(songs[0].id, songs[2].id, songs[1].id), view.songs.map { it.id })
        assertEquals(600_000L, view.durationMs)
        assertEquals(songs[0].id, view.fallbackCover?.id)

        model.setPlaylistCover(playlist.id, "cover.png", byteArrayOf(1, 2, 3))
        view = model.playlists.await("custom cover") { it.firstOrNull { p -> p.id == playlist.id }?.coverPath != null }
            .first { it.id == playlist.id }
        assertEquals("/private/playlist-covers/${playlist.id}.png", view.coverPath)
        assertTrue(platform.playlistCovers.containsKey(view.coverPath))
        model.clearPlaylistCover(playlist.id)
        model.playlists.await("cover cleared") { it.firstOrNull { p -> p.id == playlist.id }?.coverPath == null }
        assertTrue(platform.playlistCovers.isEmpty())

        model.playPlaylist(playlist.id)
        assertEquals(listOf(songs[0].id, songs[2].id, songs[1].id), player.queues.last().first.map { it.id })
        model.playPlaylist(playlist.id, shuffle = true)
        model.settings.await("playlist shuffle preference saved") { it?.shuffle == true }
        assertTrue(player.shuffleOn)
        model.playPlaylistSong(view.songs, 1)
        assertEquals(listOf(songs[0].id, songs[2].id, songs[1].id), player.queues.last().first.map { it.id })
        assertEquals(1, player.queues.last().second)
        assertTrue(model.removeFromPlaylist(playlist.id, songs[1].id))
        assertFalse(model.removeFromPlaylist(playlist.id, songs[1].id))

        model.deletePlaylist(playlist.id)
        model.playlists.await("playlist deleted") { list -> list.none { it.id == playlist.id } }
        assertTrue(db.playlists().itemsFor(playlist.id).isEmpty(), "playlist items cascade on delete")
    }

    @Test
    fun playlistsKeepMissingTrackRowsButHideThemFromTheView() = runBlocking {
        val platform = FakePlatform()
        val db = database(File(tmp.root, "missing-playlist-track.db"))
        val model = readyModel(db, FakePlayer(), platform)
        val playlist = model.createPlaylist("Keep order")
        val originalSongs = model.tracks.value
        model.addToPlaylist(playlist.id, originalSongs[0].id)
        model.addToPlaylist(playlist.id, originalSongs[1].id)
        platform.folders["/music"] = platform.folders.getValue("/music").drop(1)

        model.rescanNow()
        withTimeout(5_000) {
            model.playlists.first { views -> views.firstOrNull { it.id == playlist.id }?.songs?.size == 1 }
        }

        assertEquals(2, db.playlists().itemsFor(playlist.id).size)
    }

    @Test
    fun dragReorderMovesASongToAnyPositionAndRenumbers() = runBlocking {
        val platform = FakePlatform()
        val db = database(File(tmp.root, "drag-reorder.db"))
        val model = readyModel(db, FakePlayer(), platform)
        val songs = model.tracks.value
        val playlist = model.createPlaylist("Reorder")
        for (song in songs.take(3)) model.addToPlaylist(playlist.id, song.id)
        suspend fun order() = db.playlists().itemsFor(playlist.id).map { it.trackId }

        assertTrue(model.movePlaylistTrackTo(playlist.id, songs[0].id, 2), "first to last")
        assertEquals(listOf(songs[1].id, songs[2].id, songs[0].id), order())
        assertTrue(model.movePlaylistTrackTo(playlist.id, songs[0].id, 0), "last to first")
        assertEquals(listOf(songs[0].id, songs[1].id, songs[2].id), order())
        assertFalse(model.movePlaylistTrackTo(playlist.id, songs[1].id, 1), "same place")
        assertFalse(model.movePlaylistTrackTo(playlist.id, songs[2].id, 99), "past the end is the end, already there")
        assertEquals(listOf(0, 1, 2), db.playlists().itemsFor(playlist.id).map { it.position }, "no gaps")

        // A song that left the library keeps its row; indexes count only the songs shown.
        platform.folders["/music"] = platform.folders.getValue("/music").drop(1)
        model.rescanNow()
        withTimeout(5_000) { model.playlists.first { v -> v.firstOrNull { it.id == playlist.id }?.songs?.size == 2 } }
        assertTrue(model.movePlaylistTrackTo(playlist.id, songs[2].id, 0))
        assertEquals(listOf(songs[0].id, songs[2].id, songs[1].id), order())
        val shown = model.playlists.await("reordered view") { v -> v.firstOrNull { it.id == playlist.id }?.songs?.firstOrNull()?.id == songs[2].id }
        assertEquals(listOf(songs[2].id, songs[1].id), shown.first { it.id == playlist.id }.songs.map { it.id })
    }

    @Test
    fun backgroundBundleExportRetainsProgressAndCompletionInAppState() {
        val platform = object : FakePlatform() {
            override suspend fun writePlaylistBundle(
                destination: String,
                name: String,
                songs: List<TrackEntity>,
                customCoverPath: String?,
                fallbackCover: TrackEntity?,
                includeLyrics: Boolean,
                onProgress: (PlaylistBundleExportProgress) -> Unit,
            ) {
                onProgress(PlaylistBundleExportProgress(PlaylistBundleExportStage.Packaging, 4, 8))
            }
        }
        val model = setUp(File(tmp.root, "background-export.db"), platform = platform)
        val playlist = runBlocking { model.createPlaylist("Background mix") }

        assertTrue(model.startPlaylistBundleExport(playlist.id, playlist.name, "picked.rdplaylist", includeLyrics = true))
        val completed = model.playlistBundleExportTask.await("bundle export completion") {
            it?.status == PlaylistBundleExportStatus.Completed
        }!!
        assertEquals(playlist.name, completed.playlistName)
        assertEquals(0.5f, completed.progress?.fraction)
    }

    @Test
    fun cancellingBackgroundBundleExportIsRetainedAsATerminalTask() {
        val exportStarted = CompletableDeferred<Unit>()
        val platform = object : FakePlatform() {
            override suspend fun writePlaylistBundle(
                destination: String,
                name: String,
                songs: List<TrackEntity>,
                customCoverPath: String?,
                fallbackCover: TrackEntity?,
                includeLyrics: Boolean,
                onProgress: (PlaylistBundleExportProgress) -> Unit,
            ) {
                exportStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val model = setUp(File(tmp.root, "cancel-background-export.db"), platform = platform)
        val playlist = runBlocking { model.createPlaylist("Cancelable mix") }

        assertTrue(model.startPlaylistBundleExport(playlist.id, playlist.name, "picked.rdplaylist", includeLyrics = true))
        runBlocking { withTimeout(5_000) { exportStarted.await() } }
        model.cancelPlaylistBundleExport()

        val cancelled = model.playlistBundleExportTask.await("bundle export cancellation") {
            it?.status == PlaylistBundleExportStatus.Cancelled
        }!!
        assertEquals(playlist.name, cancelled.playlistName)
    }

    @Test
    fun m3uImportMatchesTagsAndExportUsesRelativePaths() = runBlocking {
        val platform = FakePlatform().apply {
            playlistFile = PlaylistTextFile(
                "picked.m3u8",
                "#EXTM3U\n#PLAYLIST:Mix Tape\n#EXTINF:200,Neon Coast - Midnight Drive\nnot-found.flac\nunmatched.flac\n",
            )
        }
        val model = readyModel(database(), FakePlayer(), platform)

        val preview = model.previewM3uImport("/picked.m3u8")
        assertEquals("Mix Tape", preview.name)
        assertEquals(1, preview.matchedCount)
        assertEquals(1, preview.notFoundCount)
        assertEquals("Midnight Drive", preview.items.first().track?.title)

        val imported = model.importM3u(preview)
        val view = model.playlists.await("M3U playlist") { playlists ->
            playlists.firstOrNull { playlist -> playlist.id == imported.id }?.songs?.size == 1
        }
            .first { it.id == imported.id }
        assertEquals(listOf("Midnight Drive"), view.songs.map { it.title })

        model.exportM3u(imported.id, "/export.m3u8", M3uPathMode.Relative)
        val exported = platform.exports.single()
        assertEquals("/export.m3u8", exported.first)
        val document = M3u.parse(exported.second.decodeToString())
        assertEquals("Mix Tape", document.playlistName)
        assertEquals(listOf("Midnight Drive.flac"), document.entries.map { it.path })
    }

    @Test
    fun bundleImportCreatesPlaylistSavesCoverAndCleansPreviewFiles() = runBlocking {
        val platform = FakePlatform()
        val model = readyModel(database(), FakePlayer(), platform)
        val track = model.tracks.value.first()
        val previewDirectory = File(tmp.root, "bundle-preview").apply { mkdirs() }
        val preview = PlaylistBundlePreview(
            name = "Imported Mix",
            songs = listOf(
                PlaylistBundlePreviewSong(
                    sourcePath = File(previewDirectory, "song.flac").absolutePath,
                    fileName = "song.flac",
                    lyricsPath = null,
                    tags = TrackTags(title = track.title, artist = track.artist, durationMs = track.durationMs),
                    existingTrack = track,
                    warning = null,
                ),
            ),
            coverBytes = byteArrayOf(1, 2, 3),
            coverExtension = "png",
            temporaryDirectory = previewDirectory.absolutePath,
        )

        val imported = model.importPlaylistBundle(preview)

        val view = model.playlists.await("imported bundle playlist") { playlists ->
            playlists.firstOrNull { it.id == imported.id }?.songs?.size == 1
        }
            .first { it.id == imported.id }
        val coverPath = requireNotNull(imported.coverPath)
        assertEquals(listOf(track.id), view.songs.map { it.id })
        assertEquals("/private/playlist-covers/${imported.id}.png", coverPath)
        assertContentEquals(byteArrayOf(1, 2, 3), requireNotNull(platform.playlistCovers[coverPath]))
        assertEquals(listOf(previewDirectory.absolutePath), platform.cleanedBundleDirectories)
        assertFalse(previewDirectory.exists())

        model.deletePlaylist(imported.id)
        model.playlists.await("imported playlist deleted") { playlists -> playlists.none { it.id == imported.id } }
        assertTrue(platform.playlistCovers.isEmpty())
    }

    @Test
    fun bundleImportCopiesNewSongsRescansAndAddsThemToThePlaylist() = runBlocking {
        val platform = FakePlatform()
        val model = readyModel(database(), FakePlayer(), platform)
        model.createPlaylist("Renamed Mix")
        val previewDirectory = File(tmp.root, "new-song-preview").apply { mkdirs() }
        val audio = File(previewDirectory, "New Song.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val lyrics = File(previewDirectory, "New Song.lrc").apply { writeText("[00:01.00]Line\n") }
        platform.folders["/music"] = platform.folders.getValue("/music") + Triple("New Song", "New Artist", "New Album")
        val preview = PlaylistBundlePreview(
            name = "New Mix",
            songs = listOf(
                PlaylistBundlePreviewSong(
                    sourcePath = audio.absolutePath,
                    fileName = "New Song.flac",
                    lyricsPath = lyrics.absolutePath,
                    tags = TrackTags(title = "New Song", artist = "New Artist", durationMs = 200_000),
                    existingTrack = null,
                    warning = null,
                ),
            ),
            coverBytes = null,
            coverExtension = null,
            temporaryDirectory = previewDirectory.absolutePath,
        )

        val imported = model.importPlaylistBundle(preview, "Renamed Mix")

        val view = model.playlists.await("new-song bundle playlist") { playlists ->
            playlists.firstOrNull { it.id == imported.id }?.songs?.size == 1
        }
            .first { it.id == imported.id }
        assertEquals(listOf("New Song"), view.songs.map { it.title })
        assertEquals("Renamed Mix (2)", imported.name)
        assertEquals(listOf("Renamed Mix (2)"), platform.importedPlaylistNames)
        assertEquals(listOf("/music"), platform.importedPlaylistFolders)
        assertEquals(
            listOf(PlaylistImportFile(audio.absolutePath, "New Song.flac", lyrics.absolutePath)),
            platform.importedPlaylistRequests.single(),
        )
        assertFalse(previewDirectory.exists())
    }

    @Test
    fun version3DatabasesUpgradeWithoutLosingTracksOrSettings() {
        val file = File(tmp.root, "v3.db")
        BundledSQLiteDriver().open(file.absolutePath).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `location` TEXT NOT NULL, " +
                    "`fileName` TEXT NOT NULL, `format` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `modifiedMs` INTEGER NOT NULL, " +
                    "`title` TEXT, `artist` TEXT, `album` TEXT, `albumArtist` TEXT, `year` INTEGER, `trackNumber` INTEGER, " +
                    "`discNumber` INTEGER, `genre` TEXT, `durationMs` INTEGER, `hasArtwork` INTEGER NOT NULL, `tagsReadable` INTEGER NOT NULL, " +
                    "`folder` TEXT, `lyricsLocation` TEXT)",
            )
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_location` ON `tracks` (`location`)")
            execSQL("CREATE TABLE IF NOT EXISTS `settings` (`name` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`name`))")
            execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'acc33e04fe05248e488f96ec13ab777a')")
            execSQL(
                "INSERT INTO tracks (location, fileName, format, sizeBytes, modifiedMs, title, hasArtwork, tagsReadable) " +
                    "VALUES ('/music/a.flac', 'a.flac', 'flac', 1, 1, 'Kept', 0, 1)",
            )
            execSQL("INSERT INTO settings (name, value) VALUES ('accent', 'Rose')")
            execSQL("PRAGMA user_version = 3")
            close()
        }

        val db = database(file)
        runBlocking {
            assertEquals(listOf("Kept"), db.tracks().observeAll().first().map { it.title })
            assertEquals(listOf("Rose"), db.settings().observeAll().first().map { it.value })
            assertTrue(db.playlists().observeAll().first().isEmpty())
            val id = db.playlists().insert(PlaylistEntity(name = "Imported", createdMs = 1))
            assertEquals("Imported", db.playlists().get(id)!!.name)
        }
    }

    /** Databases made by part 1 (version 1, no settings table) open and keep their tracks. */
    @Test
    fun version1DatabasesAreUpgraded() {
        val file = File(tmp.root, "v1.db")
        BundledSQLiteDriver().open(file.absolutePath).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `location` TEXT NOT NULL, " +
                    "`fileName` TEXT NOT NULL, `format` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `modifiedMs` INTEGER NOT NULL, " +
                    "`title` TEXT, `artist` TEXT, `album` TEXT, `albumArtist` TEXT, `year` INTEGER, `trackNumber` INTEGER, " +
                    "`discNumber` INTEGER, `genre` TEXT, `durationMs` INTEGER, `hasArtwork` INTEGER NOT NULL, `tagsReadable` INTEGER NOT NULL)",
            )
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_location` ON `tracks` (`location`)")
            execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'c8ebdbe02bca7617d2c034c557627379')")
            execSQL(
                "INSERT INTO tracks (location, fileName, format, sizeBytes, modifiedMs, title, hasArtwork, tagsReadable) " +
                    "VALUES ('/music/a.flac', 'a.flac', 'flac', 1, 1, 'Kept', 0, 1)",
            )
            execSQL("PRAGMA user_version = 1")
            close()
        }
        val db = database(file)
        runBlocking {
            assertEquals(listOf("Kept"), db.tracks().observeAll().first().map { it.title })
            db.settings().put(SettingEntity("accent", "Rose"))
            assertTrue(db.settings().observeAll().first().any { it.value == "Rose" })
        }
    }
}
