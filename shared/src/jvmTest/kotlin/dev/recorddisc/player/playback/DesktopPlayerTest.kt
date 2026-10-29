package dev.recorddisc.player.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Queue behaviour of the real GStreamer player, silent through fakesink (2-second test tones).
 * Skipped if GStreamer is missing.
 */
class DesktopPlayerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val players = ArrayList<DesktopPlayer>()

    @After
    fun releasePlayers() = players.forEach { it.release() }

    private fun player(): DesktopPlayer {
        val p = runCatching { DesktopPlayer(audioSink = "fakesink") }.getOrNull()
        assumeTrue("GStreamer is not installed", p != null)
        return p!!.also { players += it }
    }

    private var nextId = 1L

    private fun song(file: File) = QueueItem(nextId++, file.absolutePath, file.name, null, null, null)

    private fun tone(ext: String): QueueItem =
        song(File(tmp.root, "tone.$ext").also { out -> javaClass.getResourceAsStream("/audio/tone.$ext")!!.use { it.copyTo(out.outputStream()) } })

    private fun broken(): QueueItem = song(tmp.newFile("broken${nextId}.mp3").apply { writeText("not audio") })

    private fun DesktopPlayer.await(timeoutMs: Long = 8_000, what: String, check: (PlaybackState) -> Boolean): PlaybackState =
        runBlocking {
            runCatching { withTimeout(timeoutMs) { state.first(check) } }
                .getOrElse { throw AssertionError("Timed out waiting for: $what (last state: ${state.value})") }
        }

    @Test
    fun playsTheQueueThroughAndStopsAtTheEnd() {
        val p = player()
        val songs = listOf(tone("opus"), tone("mp3"))
        p.setQueue(songs)
        p.await(what = "first song playing") { it.index == 0 && it.isPlaying && it.positionMs > 300 }
        p.await(what = "second song playing") { it.index == 1 && it.isPlaying && it.positionMs > 300 }
        val end = p.await(what = "stopped at the end") { !it.isPlaying }
        assertEquals(1, end.index, "stays on the last song")
        assertTrue(end.positionMs < 100, "back at the start, was ${end.positionMs}")
        assertEquals(songs, end.queue)
        assertNull(end.error)
    }

    @Test
    fun pauseSeekNextAndPrevious() {
        val p = player()
        p.setQueue(listOf(tone("flac"), tone("ogg"), tone("opus")), startIndex = 1, playWhenReady = false)
        val loaded = p.await(what = "loaded, paused") { it.index == 1 && it.durationMs != null }
        assertFalse(loaded.isPlaying)
        assertTrue(loaded.durationMs!! in 1_900..2_100, "duration ${loaded.durationMs}")

        p.seekTo(1_500)
        p.await(what = "seeked to 1.5 s") { it.positionMs in 1_400..1_600 }
        Thread.sleep(400)
        assertTrue(p.state.value.positionMs in 1_400..1_600, "paused position holds")

        p.next()
        p.await(what = "third song") { it.index == 2 && it.positionMs < 100 }
        // Less than 3 s in: Previous goes back a song.
        p.previous()
        p.await(what = "back to second") { it.index == 1 }
        p.previous()
        p.await(what = "back to first") { it.index == 0 }
        // First song and no repeat: Previous restarts it.
        p.seekTo(1_000)
        p.await(what = "seeked") { it.positionMs >= 900 }
        p.previous()
        p.await(what = "restarted") { it.index == 0 && it.positionMs < 100 }

        p.play()
        p.await(what = "playing") { it.isPlaying && it.positionMs > 200 }
        p.pause()
        p.await(what = "paused") { !it.isPlaying }
    }

    @Test
    fun repeatOneReplaysTheSong() {
        val p = player()
        p.setRepeatMode(RepeatMode.One)
        p.setQueue(listOf(tone("ogg"), tone("flac")))
        p.await(what = "near the end") { it.positionMs > 1_700 }
        p.await(what = "replaying from the start") { it.index == 0 && it.isPlaying && it.positionMs in 100..1_000 }
        assertEquals(RepeatMode.One, p.state.value.repeatMode)
    }

    @Test
    fun repeatAllWrapsToTheFirstSong() {
        val p = player()
        p.setRepeatMode(RepeatMode.All)
        p.setQueue(listOf(tone("ogg"), tone("flac")), startIndex = 1)
        p.await(what = "wrapped to first") { it.index == 0 && it.isPlaying && it.positionMs > 200 }
    }

    @Test
    fun brokenFilesAreSkippedWithAnError() {
        val p = player()
        p.setQueue(listOf(broken(), tone("flac")))
        val s = p.await(what = "second song playing") { it.index == 1 && it.isPlaying && it.positionMs > 200 }
        assertNotNull(s.error, "the skipped file is reported")
    }

    @Test
    fun stopsWhenEverySongFails() {
        val p = player()
        p.setRepeatMode(RepeatMode.All)
        p.setQueue(listOf(broken(), broken()))
        val s = p.await(what = "stopped with error") { it.error != null && !it.isPlaying }
        assertNotNull(s.current)
    }

    @Test
    fun shuffleKeepsTheCurrentSong() {
        val p = player()
        val songs = listOf(tone("flac"), tone("ogg"), tone("opus"), tone("mp3"))
        p.setQueue(songs, startIndex = 2, playWhenReady = false)
        p.await(what = "loaded") { it.index == 2 }
        p.setShuffle(true)
        val s = p.await(what = "shuffled") { it.shuffle }
        assertEquals(0, s.index)
        assertEquals(songs[2], s.current)
        assertEquals(songs.toSet(), s.queue.toSet())
        p.setShuffle(false)
        val back = p.await(what = "unshuffled") { !it.shuffle }
        assertEquals(songs, back.queue)
        assertEquals(songs[2], back.current)
    }
}
