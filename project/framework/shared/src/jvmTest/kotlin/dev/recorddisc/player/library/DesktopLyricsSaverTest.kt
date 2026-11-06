package dev.recorddisc.player.library

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLyricsSaverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun song(dir: File = tmp.newFolder()): TrackEntity {
        val file = File(dir, "Midnight Drive.flac").also { out -> javaClass.getResourceAsStream("/audio/tone.flac")!!.use { it.copyTo(out.outputStream()) } }
        return TrackEntity(location = file.absolutePath, fileName = file.name, format = "flac", sizeBytes = file.length(), modifiedMs = 0,
            title = null, artist = null, album = null, albumArtist = null, year = null, trackNumber = null, discNumber = null,
            genre = null, durationMs = null, hasArtwork = false, tagsReadable = true, folder = dir.absolutePath)
    }

    @Test
    fun createsOverwritesAndDeletesNextToTheSong() {
        val track = song()
        assertEquals(EditSupport.Editable, DesktopLyricsSaver.support(track))
        val path = DesktopLyricsSaver.save(track, "[00:01.00]Headlights\n")
        assertEquals(File(File(track.location).parentFile, "Midnight Drive.lrc").absolutePath, path)
        assertEquals("[00:01.00]Headlights\n", File(path!!).readText())

        val saved = track.copy(lyricsLocation = path)
        DesktopLyricsSaver.save(saved, "[00:02.00]Neon hums\n")
        assertEquals("[00:02.00]Neon hums\n", File(path).readText())
        assertEquals(listOf("Midnight Drive.flac", "Midnight Drive.lrc"), File(path).parentFile.list()!!.sorted(), "no temp files left")
        assertEquals("Neon hums", DesktopLyricsSaver.read(saved)!!.lines.single().text)

        assertNull(DesktopLyricsSaver.save(saved, null))
        assertFalse(File(path).exists())
    }

    @Test
    fun readOnlyFolderIsRefused() {
        val dir = tmp.newFolder()
        val track = song(dir)
        dir.setWritable(false)
        try {
            assumeTrue("running as a user who can write anyway (root)", !dir.canWrite())
            assertIs<EditSupport.ReadOnly>(DesktopLyricsSaver.support(track))
            assertFailsWith<TagSaveException> { DesktopLyricsSaver.save(track, "[00:01.00]x\n") }
            assertTrue(dir.list()!!.none { it.endsWith(".lrc") || it.startsWith(".rd-lyrics-") })
        } finally {
            dir.setWritable(true)
        }
    }
}
