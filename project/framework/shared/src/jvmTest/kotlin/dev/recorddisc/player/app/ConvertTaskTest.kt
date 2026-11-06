package dev.recorddisc.player.app

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.recorddisc.player.convert.ConvertFormat
import dev.recorddisc.player.convert.ConvertJob
import dev.recorddisc.player.convert.ConvertOptions
import dev.recorddisc.player.convert.ConvertProgress
import dev.recorddisc.player.convert.ConvertSaved
import dev.recorddisc.player.library.LibraryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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

/** The Convert to Audio job as the Inbox sees it: running → done / cancelled / failed, one at a time. */
class ConvertTaskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val databases = ArrayList<LibraryDatabase>()

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        databases.forEach { it.close() }
    }

    private fun <T> StateFlow<T>.await(what: String, check: (T) -> Boolean): T = runBlocking {
        runCatching { withTimeout(5_000) { first(check) } }.getOrElse { throw AssertionError("Timed out waiting for $what (last: $value)") }
    }

    /** A platform whose conversion reports one step, then waits for [finish] (or is cancelled). */
    private class ConvertingPlatform : FakePlatform() {
        val finish = CompletableDeferred<ConvertSaved?>()
        var job: ConvertJob? = null
        override val convertDefaultFolder = "Music/Exports"
        override suspend fun convertPlaylist(job: ConvertJob, onProgress: (ConvertProgress) -> Unit): ConvertSaved {
            this.job = job
            onProgress(ConvertProgress(2, job.inputs.size, 0.7f))
            return finish.await() ?: error("The encoder stopped")
        }
    }

    private fun setUp(platform: ConvertingPlatform): Pair<AppModel, Long> {
        val db = Room.databaseBuilder<LibraryDatabase>(File(tmp.root, "library-${databases.size}.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also { databases += it }
        val model = AppModel(db, FakePlayer(), platform, scope)
        model.scan("/music")
        model.scan.await("scan") { it is ScanState.Done }
        model.finishSetup()
        val tracks = model.tracks.await("library") { it.size == 3 }
        val playlist = runBlocking { model.createPlaylist("Road Trip").also { p -> tracks.forEach { model.addToPlaylist(p.id, it.id) } } }
        return model to playlist.id
    }

    private val options = ConvertOptions(ConvertFormat.Opus, gapSeconds = 2, evenOutVolume = true, fileName = "Road Trip.flac")

    @Test
    fun aJobRunsReportsTheSongAndFinishes() {
        val platform = ConvertingPlatform()
        val (model, id) = setUp(platform)
        assertNull(model.startConversion(id, "Road Trip", options))
        val running = model.convertTask.await("progress") { it?.progress != null }!!
        assertEquals(ConvertStatus.Running, running.status)
        assertEquals("Road Trip.opus", running.fileName, "the extension follows the format")
        assertEquals("Static Bloom", running.currentTitle, "song 3 is being converted")
        assertEquals(70, running.percent)
        assertEquals("Music/Exports", running.destinationLabel)
        val job = assertNotNull(platform.job)
        assertEquals(listOf("Midnight Drive", "Paper Lanterns", "Static Bloom"), job.inputs.map { it.title })
        assertEquals(2, job.gapSeconds)
        assertEquals(true, job.evenOutVolume)

        assertEquals("Another conversion is already running", model.startConversion(id, "Road Trip", options), "one at a time")

        platform.finish.complete(ConvertSaved("Music/Exports/Road Trip.opus", "content://x/1", null))
        val done = model.convertTask.await("done") { it?.status == ConvertStatus.Completed }!!
        assertEquals(100, done.percent)
        assertEquals("Music/Exports/Road Trip.opus", done.saved?.shownAs)
        model.dismissConversion()
        assertNull(model.convertTask.value, "Dismiss clears the Inbox card")
    }

    @Test
    fun cancellingAndFailingAreReported() {
        val platform = ConvertingPlatform()
        val (model, id) = setUp(platform)
        model.startConversion(id, "Road Trip", options)
        model.convertTask.await("running") { it?.progress != null }
        model.dismissConversion()
        assertNotNull(model.convertTask.value, "a running job can't be dismissed")
        model.cancelConversion()
        model.convertTask.await("cancelled") { it?.status == ConvertStatus.Cancelled }

        val second = ConvertingPlatform().also { it.finish.complete(null) }
        val (model2, id2) = setUp(second)
        model2.startConversion(id2, "Road Trip", options)
        val failed = model2.convertTask.await("failed") { it?.status == ConvertStatus.Failed }!!
        assertEquals("The encoder stopped", failed.failure)
    }
}
