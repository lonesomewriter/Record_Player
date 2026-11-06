package dev.recorddisc.player.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPathsTest {
    @Test
    fun readsMusicDirWithHomeVariable() {
        val text = "# comment\nXDG_DESKTOP_DIR=\"\$HOME/Desktop\"\nXDG_MUSIC_DIR=\"\$HOME/Musik\"\n"
        assertEquals("/home/ana/Musik", DesktopPaths.parseMusicDir(text, "/home/ana"))
    }

    @Test
    fun readsAbsoluteMusicDir() {
        assertEquals("/data/audio", DesktopPaths.parseMusicDir("XDG_MUSIC_DIR=\"/data/audio\"", "/home/ana"))
    }

    @Test
    fun musicDirSetToHomeMeansNotConfigured() {
        assertNull(DesktopPaths.parseMusicDir("XDG_MUSIC_DIR=\"\$HOME/\"", "/home/ana"))
    }

    @Test
    fun missingEntryMeansNotConfigured() {
        assertNull(DesktopPaths.parseMusicDir("XDG_DOWNLOAD_DIR=\"\$HOME/Downloads\"", "/home/ana"))
    }
}
