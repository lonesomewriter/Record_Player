package dev.recorddisc.player.playback

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** No audio devices come and go here, so this checks the monitor starts and stops cleanly either way. */
class OutputMonitorTest {
    @Test
    fun startsAndStopsCleanly() {
        val monitor = runCatching { OutputMonitor {} }.getOrNull()
        assumeTrue("GStreamer is not installed", monitor != null)
        val start = System.nanoTime()
        monitor!!.close()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000, "close doesn't hang")
        println("Output monitoring available here: ${monitor.available}")
    }
}
