package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.LyricLine
import dev.recorddisc.player.library.SettingEntity
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.playback.MusicPlayer
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.QueueItem
import dev.recorddisc.player.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val databases = ArrayList<LibraryDatabase>()

    @After
    fun tearDown() {
        scope.cancel()
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
        val model = AppModel(database(file), player, platform, scope)
        model.scan("/music")
        model.scan.await("scan done") { it is ScanState.Done }
        model.finishSetup()
        model.settings.await("setup done") { it?.setupDone == true }
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
        model.scan("/music")
        val album = model.albums.await("albums") { it.size == 2 }.first { it.name == "After Hours" }
        model.playAll(album.tracks, shuffle = true)
        assertTrue(player.shuffleOn)
        assertEquals(album.tracks.map { it.id }, player.queues.last().first.map { it.id })
        assertTrue(player.queues.last().third)
        model.playAll(album.tracks, shuffle = false)
        assertEquals(false, player.shuffleOn)
        assertEquals(0, player.queues.last().second)
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
        val s = AppSettings.from(mapOf("accent" to "Plaid", "customAccent" to "zz", "setupDone" to "yes", "musicFolder" to " ", "theme" to "Sepia"))
        assertEquals(AppSettings(), s)
        assertEquals(0xFFABCDEF, AppSettings.from(mapOf("accent" to "Custom", "customAccent" to "abcdef")).accentArgb)
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
