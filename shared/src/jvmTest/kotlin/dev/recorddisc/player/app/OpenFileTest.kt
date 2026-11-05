package dev.recorddisc.player.app

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.OUTSIDE_TRACK_ID
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.library.isOutside
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The saved queue for Resume on start (AppModel.RESUME_QUEUE, private there). */
private const val RESUME_QUEUE_SETTING = "resumeQueue"

/** "Open with" from a file manager (2026-11-04): library songs play as library songs; other files play alone. */
class OpenFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val databases = ArrayList<LibraryDatabase>()

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        databases.forEach { it.close() }
    }

    private fun database(): LibraryDatabase =
        Room.databaseBuilder<LibraryDatabase>(File(tmp.root, "library.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { databases += it }

    private fun <T> StateFlow<T>.await(what: String, check: (T) -> Boolean): T = runBlocking {
        runCatching { withTimeout(5_000) { first(check) } }.getOrElse { throw AssertionError("Timed out waiting for $what (last: $value)") }
    }

    private fun track(id: Long, location: String, name: String, size: Long) = TrackEntity(
        id = id, location = location, fileName = name, format = "flac", sizeBytes = size, modifiedMs = 1,
        title = null, artist = null, album = null, albumArtist = null, year = null, trackNumber = null, discNumber = null,
        genre = null, durationMs = null, hasArtwork = false, tagsReadable = true,
    )

    @Test
    fun matchingByPathThenByNameAndSize() {
        val library = listOf(
            track(1, "/music/a/Song.flac", "Song.flac", 500),
            track(2, "/music/b/Other.flac", "Other.flac", 700),
            track(3, "/music/c/Twin.flac", "Twin.flac", 900),
            track(4, "/music/d/Twin.flac", "Twin.flac", 900),
        )
        assertEquals(1L, findLibraryMatch(OpenedFile("/music/a/Song.flac", "Song.flac", null), library)?.id, "same path")
        // Android: the file manager's own address, so name and size decide (name case doesn't matter).
        assertEquals(2L, findLibraryMatch(OpenedFile("content://files/77", "other.FLAC", 700), library)?.id, "same name and size")
        assertNull(findLibraryMatch(OpenedFile("content://files/78", "Other.flac", 701), library), "a different size isn't the same file")
        assertNull(findLibraryMatch(OpenedFile("content://files/79", "Twin.flac", 900), library), "two candidates: can't tell which")
        assertNull(findLibraryMatch(OpenedFile("content://files/80", "Song.flac", null), library), "no size: only the path can match")
    }

    private fun readyModel(player: FakePlayer, platform: FakePlatform = FakePlatform()): AppModel {
        val model = AppModel(database(), player, platform, scope)
        model.scan("/music")
        model.scan.await("scan") { it is ScanState.Done }
        model.finishSetup()
        model.settings.await("setup") { it?.setupDone == true }
        model.tracks.await("library") { it.size == 3 }
        return model
    }

    @Test
    fun aLibrarySongPlaysWithTheLibraryAsItsQueue() {
        val player = FakePlayer()
        val model = readyModel(player)
        val opened = runBlocking { model.openFile(OpenedFile("content://downloads/5", "Paper Lanterns.flac", 100)) }
        assertEquals(OpenedAs.LibrarySong, opened)
        val (queue, index, play) = player.queues.last()
        assertEquals(listOf("Midnight Drive", "Paper Lanterns", "Static Bloom"), queue.map { it.title })
        assertEquals(1, index)
        assertTrue(play)
        assertNull(model.outsideTrack.value)
    }

    @Test
    fun anOutsideFilePlaysAloneIsNeverSavedAndIsForgottenAfterwards() {
        val player = FakePlayer()
        val platform = object : FakePlatform() {
            override suspend fun readFileTags(location: String) = TrackTags(title = "Demo Take 3", artist = "Hollow Pines", durationMs = 151_000)
        }
        val model = readyModel(player, platform)
        val db = databases.single()
        val savedBefore = runBlocking {
            withTimeout(5_000) {
                while (db.settings().observeAll().first().none { it.name == RESUME_QUEUE_SETTING }) kotlinx.coroutines.delay(20)
            }
            db.settings().observeAll().first().first { it.name == RESUME_QUEUE_SETTING }.value
        }

        val opened = runBlocking { model.openFile(OpenedFile("/home/me/Downloads/Demo Take 3.m4a", "Demo Take 3.m4a", 4_000)) }
        assertEquals(OpenedAs.OutsideFile, opened)
        val (queue, index, play) = player.queues.last()
        assertEquals(listOf("Demo Take 3"), queue.map { it.title }, "plays on its own")
        assertEquals(OUTSIDE_TRACK_ID, queue.single().id)
        assertEquals(0, index)
        assertTrue(play)
        val outside = assertNotNull(model.outsideTrack.value)
        assertTrue(outside.isOutside)
        assertEquals("Hollow Pines", outside.artist)
        assertEquals("m4a", outside.format)
        assertEquals(3, model.tracks.value.size, "not added to the library")

        Thread.sleep(300)
        val savedAfter = runBlocking { db.settings().observeAll().first().first { it.name == RESUME_QUEUE_SETTING }.value }
        assertEquals(savedBefore, savedAfter, "Resume on start keeps the queue from before")

        model.play(model.tracks.value, 0)
        model.outsideTrack.await("forgotten") { it == null }
    }

    @Test
    fun unreadableTagsStillPlayUnderTheFileName() {
        val player = FakePlayer()
        val platform = object : FakePlatform() {
            override suspend fun readFileTags(location: String): TrackTags = error("not an audio file the reader knows")
        }
        val model = readyModel(player, platform)
        runBlocking { model.openFile(OpenedFile("/tmp/voice note.ogg", "voice note.ogg", 10)) }
        assertEquals("voice note", player.queues.last().first.single().title)
        assertEquals(false, model.outsideTrack.value?.tagsReadable)
    }
}
