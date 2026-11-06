package dev.recorddisc.player.app

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Desktop "Open with": a second start hands its files to the open window and exits (2026-11-04). */
class SingleInstanceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aSecondStartHandsItsFilesToTheFirst() {
        val socket = File(tmp.root, "rp.sock")
        val first = assertNotNull(SingleInstance.claim(emptyList(), socket), "the first start becomes the window")
        val received = LinkedBlockingQueue<List<String>>()
        first.listen { received.put(it) }
        try {
            val paths = listOf("/home/me/Music/Ünïcode song.flac", "/tmp/second file.mp3")
            assertNull(SingleInstance.claim(paths, socket), "a second start hands over and exits")
            assertEquals(paths, received.poll(5, TimeUnit.SECONDS))
            // Started again without files (e.g. from the menu): the window is just brought forward.
            assertNull(SingleInstance.claim(emptyList(), socket))
            assertEquals(emptyList(), received.poll(5, TimeUnit.SECONDS))
        } finally {
            first.close()
        }
        assertEquals(false, socket.exists(), "the socket file is removed on exit")
    }

    @Test
    fun aStaleSocketFromACrashIsReplaced() {
        val socket = File(tmp.root, "rp.sock").apply { writeText("left behind") }
        val instance = assertNotNull(SingleInstance.claim(listOf("/x.flac"), socket), "nothing listens on it, so this start runs")
        instance.close()
    }

    @Test
    fun commandLineArgumentsBecomeExistingFilePaths() {
        val song = File(tmp.root, "My Song.flac").apply { writeText("x") }
        val args = listOf(song.path, song.toURI().toString(), "--some-flag", File(tmp.root, "missing.mp3").path, tmp.root.path)
        assertEquals(listOf(song.absolutePath, song.absolutePath), openArgumentsToPaths(args))
    }
}
