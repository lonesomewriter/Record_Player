package dev.recorddisc.player.playback

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.recorddisc.player.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Media3 player on a device or emulator, using the same 2-second tones as the desktop tests.
 *
 * Run with the app's data cleared (`adb shell pm clear dev.recorddisc.player`): the app then opens
 * on its first-run screen, which leaves the player alone and has no endless animation. The disc
 * home animates constantly, and the test framework would wait forever for it to go idle.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var player: AndroidPlayer
    private var nextId = 1L

    @Before
    fun setUp() {
        // Playback services may only start from the foreground, so show the app first.
        activity = ActivityScenario.launch(MainActivity::class.java)
        player = AndroidPlayer(app)
        // The service outlives each test (as it outlives the app's screens), so reset the user's settings.
        player.setRepeatMode(RepeatMode.Off)
        player.setShuffle(false)
        await(what = "settings reset") { it.repeatMode == RepeatMode.Off && !it.shuffle }
    }

    @After
    fun tearDown() {
        player.release()
        activity.close()
    }

    private fun song(file: File) = QueueItem(nextId++, file.absolutePath, file.name, null, null, null)

    private fun tone(ext: String): QueueItem = song(
        File(app.cacheDir, "tone.$ext").also { out ->
            instrumentation.context.assets.open("audio/tone.$ext").use { it.copyTo(out.outputStream()) }
        },
    )

    private fun broken(): QueueItem = song(File(app.cacheDir, "broken$nextId.mp3").apply { writeText("not audio") })

    private fun await(timeoutMs: Long = 20_000, what: String, check: (PlaybackState) -> Boolean): PlaybackState =
        runBlocking {
            runCatching { withTimeout(timeoutMs) { player.state.first(check) } }
                .getOrElse { throw AssertionError("Timed out waiting for: $what (last state: ${player.state.value})") }
        }

    @Test
    fun playsEveryFormatAndStopsAtTheEnd() {
        val songs = listOf(tone("mp3"), tone("flac"), tone("ogg"), tone("opus"))
        player.setQueue(songs)
        for (i in songs.indices) {
            val s = await(what = "song $i playing") { it.index == i && it.isPlaying && it.positionMs > 300 }
            assertTrue(s.durationMs!! in 1_900..2_200, "${songs[i].title} duration ${s.durationMs}")
            assertNull(s.error)
        }
        val end = await(what = "stopped at the end") { !it.isPlaying && it.positionMs < 100 }
        assertEquals(3, end.index)
        assertEquals(songs, end.queue)
    }

    @Test
    fun pauseSeekNextAndPrevious() {
        player.setQueue(listOf(tone("flac"), tone("ogg"), tone("opus")), startIndex = 1, playWhenReady = false)
        val loaded = await(what = "loaded, paused") { it.index == 1 && it.durationMs != null }
        assertFalse(loaded.isPlaying)

        player.seekTo(1_500)
        await(what = "seeked to 1.5 s") { it.positionMs in 1_400..1_600 }
        Thread.sleep(500)
        assertTrue(player.state.value.positionMs in 1_400..1_600, "paused position holds")

        player.next()
        await(what = "third song") { it.index == 2 && it.positionMs < 100 }
        player.previous()
        await(what = "back to second") { it.index == 1 }
        player.previous()
        await(what = "back to first") { it.index == 0 }
        // Sent while the song may still be loading (Media3 would drop it): it must still land.
        player.seekTo(1_000)
        player.play()
        await(what = "playing from 1 s") { it.isPlaying && it.positionMs in 1_100..1_700 }
        player.pause()
        player.previous()
        await(what = "restarted") { it.index == 0 && it.positionMs < 100 }

        player.play()
        await(what = "playing") { it.isPlaying && it.positionMs > 200 }
        player.pause()
        await(what = "paused") { !it.isPlaying }
    }

    @Test
    fun repeatOneReplaysAndRepeatAllWraps() {
        player.setRepeatMode(RepeatMode.One)
        player.setQueue(listOf(tone("ogg"), tone("flac")))
        await(what = "near the end") { it.positionMs > 1_700 }
        await(what = "replaying") { it.index == 0 && it.isPlaying && it.positionMs in 100..1_000 }

        player.setRepeatMode(RepeatMode.All)
        player.next()
        await(what = "second song") { it.index == 1 && it.repeatMode == RepeatMode.All }
        await(what = "wrapped to first") { it.index == 0 && it.isPlaying && it.positionMs > 200 }
    }

    @Test
    fun pauseAtTrackEndStopsQueueProgressionUntilDisabled() {
        val songs = listOf(tone("flac"), tone("ogg"))
        player.setPauseAtTrackEnd(true)
        player.setQueue(songs)

        val stopped = await(what = "first song stopped at its end") {
            it.index == 0 && !it.isPlaying && it.positionMs > 1_700
        }
        assertEquals(songs, stopped.queue)

        player.setPauseAtTrackEnd(false)
        player.next()
        val next = await(what = "manual next still advances") { it.index == 1 }
        assertFalse(next.isPlaying)
        assertTrue(next.positionMs < 100)
    }

    @Test
    fun brokenFilesAreSkippedWithAnError() {
        player.setQueue(listOf(broken(), tone("flac")))
        val s = await(what = "second song playing") { it.index == 1 && it.isPlaying && it.positionMs > 200 }
        assertNotNull(s.error, "the skipped file is reported")
    }

    @Test
    fun shuffleKeepsTheCurrentSong() {
        val songs = listOf(tone("flac"), tone("ogg"), tone("opus"), tone("mp3"))
        player.setQueue(songs, startIndex = 2, playWhenReady = false)
        await(what = "loaded") { it.index == 2 }
        player.setShuffle(true)
        // The controller shows shuffle on at once with a provisional order, and the service's order
        // (current song first) follows; wait until the order has stopped changing.
        await(what = "shuffled") { it.shuffle && it.index == 0 }
        var s = player.state.value
        while (true) {
            Thread.sleep(500)
            if (player.state.value.queue == s.queue) break
            s = player.state.value
        }
        assertEquals(0, s.index)
        assertEquals(songs[2], s.current)
        assertEquals(songs.toSet(), s.queue.toSet())
        // Jump to the last song of the shuffled order.
        val last = s.queue.last()
        player.skipTo(3)
        await(what = "skipped to the last shuffled song, ${last.title} (order then: ${s.queue.map { it.title }})") { it.current == last && it.isPlaying }
        player.pause()
        player.setShuffle(false)
        val back = await(what = "unshuffled") { !it.shuffle }
        assertEquals(songs, back.queue)
        assertEquals(last, back.current)
    }

    /** Saving tags on Android rewrites the playing file in place; the player reopens it and carries on. */
    @Test
    fun reloadAfterARewriteKeepsPlayingAtTheSameSpot() {
        // Not the first song, as in the app (where the walk-through caught the reload being skipped).
        val song = tone("flac")
        player.setQueue(listOf(tone("ogg"), tone("mp3"), song, tone("opus")), startIndex = 2)
        await(what = "playing past 0.6 s") { it.isPlaying && it.index == 2 && it.positionMs >= 600 }
        player.pause()
        val paused = await(what = "paused") { !it.isPlaying }.positionMs
        player.updateItem(song.copy(title = "Renamed"), reload = true, play = true)
        val s = await(what = "playing again with the new title") { it.isPlaying && it.current?.title == "Renamed" }
        assertEquals(2, s.index, "still the same song")
        assertTrue(s.positionMs in (paused - 300)..(paused + 800), "carried on near ${paused} ms, at ${s.positionMs} ms")
    }

    @Test
    fun queueCanStartPartWayIntoASong() {
        player.setQueue(listOf(tone("flac"), tone("ogg")), startIndex = 1, playWhenReady = false, startPositionMs = 1_200)
        val s = await(what = "loaded at 1.2 s") { it.index == 1 && it.positionMs in 1_100..1_300 }
        assertEquals(false, s.isPlaying)
    }
}
