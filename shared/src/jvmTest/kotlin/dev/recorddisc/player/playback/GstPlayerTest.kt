package dev.recorddisc.player.playback

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-time playback checks against the system GStreamer. Uses fakesink with sync=true:
 * silent, but driven by a real clock, so no sound card is needed. Skipped if GStreamer is missing.
 */
class GstPlayerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File =
        File(tmp.root, name).also { out -> javaClass.getResourceAsStream("/audio/$name")!!.use { it.copyTo(out.outputStream()) } }

    private fun player(): GstPlayer {
        val p = runCatching { GstPlayer(audioSink = "fakesink") }.getOrNull()
        assumeTrue("GStreamer is not installed", p != null)
        return p!!
    }

    private fun assertNear(expected: Long, actual: Long?, tolerance: Long, what: String) {
        assertNotNull(actual, "$what unavailable")
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected ~$expected ms, got $actual ms")
    }

    private fun playsCorrectly(name: String) = player().use { p ->
        p.load(fixture(name).absolutePath)

        p.play()
        val start = System.nanoTime()
        Thread.sleep(500)
        assertNear((System.nanoTime() - start) / 1_000_000, p.positionMs(), 150, "position while playing")
        // MP3 lengths can arrive only once playback starts, so read the duration after playing a little.
        val duration = p.durationMs()
        assertNear(2_000, duration, 80, "duration")

        assertTrue(p.seekMs(1_200), "seek accepted")
        Thread.sleep(200)
        assertNear(1_400, p.positionMs(), 150, "position after seek")

        p.pause()
        val paused = p.positionMs()!!
        Thread.sleep(300)
        assertNear(paused, p.positionMs(), 20, "position while paused")

        p.play()
        val resumed = System.nanoTime()
        assertTrue(p.awaitEnd(5_000), "end of stream reached")
        assertNear(duration!! - paused, (System.nanoTime() - resumed) / 1_000_000, 250, "time to end of stream")
        assertEquals(null, p.pollError())
    }

    @Test fun playsMp3() = playsCorrectly("tone.mp3")
    @Test fun playsFlac() = playsCorrectly("tone.flac")
    @Test fun playsOggVorbis() = playsCorrectly("tone.ogg")
    @Test fun playsOpus() = playsCorrectly("tone.opus")

    @Test
    fun reportsCleanErrorForNonAudioFile() {
        player().use { p ->
            val file = tmp.newFile("notaudio.mp3").apply { writeText("this is not audio") }
            p.load(file.absolutePath)
            p.play()
            assertTrue(p.awaitEnd(8_000), "player should stop instead of hanging")
            assertNotNull(p.pollError(), "expected an error message")
        }
    }
}
