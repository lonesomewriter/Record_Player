package dev.recorddisc.player.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Lyrics stored inside song files, read by [TagReader.readLyrics]. */
class EmbeddedLyricsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(ext: String): File =
        File(tmp.newFolder(), "tone.$ext").also { out -> javaClass.getResourceAsStream("/audio/tone.$ext")!!.use { it.copyTo(out.outputStream()) } }

    private val lyrics = "[00:01.00]Headlights paint the empty road\n[00:02.00]Neon hums a slower code"

    @Test
    fun readsLyricsInsideEveryFormat() {
        for (ext in listOf("mp3", "flac", "ogg", "m4a", "opus")) {
            val file = fixture(ext)
            assertNull(TagReader.readLyrics(file), "$ext has none yet")
            if (ext == "opus") {
                OpusTags.write(file, OpusTags.read(file).apply { setField(FieldKey.LYRICS, lyrics) })
            } else {
                AudioFileIO.read(file).apply { tagOrCreateAndSetDefault.setField(FieldKey.LYRICS, lyrics); commit() }
            }
            assertEquals(lyrics, TagReader.readLyrics(file), ext)
            // As on Android: a path without the extension, with the format given separately.
            val noExt = File(file.parentFile, "song-without-extension").also { file.copyTo(it) }
            assertEquals(lyrics, TagReader.readLyrics(noExt, ext), "$ext read by format")
        }
    }
}
