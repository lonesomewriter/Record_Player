package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.SettingEntity
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.playback.MusicPlayer
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.QueueItem
import dev.recorddisc.player.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    fun malformedSettingsFallBackToDefaults() {
        val s = AppSettings.from(mapOf("accent" to "Plaid", "customAccent" to "zz", "setupDone" to "yes", "musicFolder" to " "))
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
