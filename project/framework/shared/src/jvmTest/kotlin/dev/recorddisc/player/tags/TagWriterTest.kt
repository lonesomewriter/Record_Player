package dev.recorddisc.player.tags

import dev.recorddisc.player.library.CoverChange
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.playback.GstPlayer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TagWriter] on every format the library plays, with the generated 2-second test tones. */
class TagWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val formats = listOf("mp3", "flac", "ogg", "opus", "m4a", "wav")

    private fun fixture(ext: String): File =
        File(tmp.newFolder(), "tone.$ext").also { out -> javaClass.getResourceAsStream("/audio/tone.$ext")!!.use { it.copyTo(out.outputStream()) } }

    private fun png(size: Int): CoverChange.Replace {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) for (y in 0 until size) image.setRGB(x, y, (x * 255 / size shl 16) or (y * 255 / size shl 8) or 0x40)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return CoverChange.Replace(bytes, "image/png", size, size)
    }

    private val full = TagEdit(
        title = "Midnight Drive", artist = "Neon Coast", album = "After Hours", albumArtist = "Various Artists",
        year = 2024, trackNumber = 7, genre = "Synthwave",
    )

    @Test
    fun writesEveryFieldAndCoverInEveryFormat() {
        for (ext in formats) {
            val file = fixture(ext)
            val before = TagReader.read(file).durationMs!!
            val cover = png(64)
            TagWriter.apply(file, full.copy(cover = cover))
            val read = TagReader.read(file)
            assertEquals("Midnight Drive", read.title, ext)
            assertEquals("Neon Coast", read.artist, ext)
            assertEquals("After Hours", read.album, ext)
            assertEquals("Various Artists", read.albumArtist, ext)
            assertEquals(2024, read.year, ext)
            assertEquals(7, read.trackNumber, ext)
            assertEquals("Synthwave", read.genre, ext)
            assertTrue(read.hasArtwork, "$ext cover")
            assertContentEquals(cover.bytes, TagReader.readArtwork(file), "$ext cover bytes")
            assertTrue(abs(read.durationMs!! - before) <= 30, "$ext length kept: $before → ${read.durationMs}")
        }
    }

    @Test
    fun blankFieldsRemoveTagsAndCoverCanBeRemoved() {
        for (ext in formats) {
            val file = fixture(ext)
            TagWriter.apply(file, full.copy(cover = png(32)))
            TagWriter.apply(
                file,
                TagEdit(title = "Only Title", artist = null, album = null, albumArtist = null, year = null, trackNumber = null, genre = null, cover = CoverChange.Remove),
            )
            val read = TagReader.read(file)
            assertEquals("Only Title", read.title, ext)
            assertNull(read.artist, "$ext artist")
            assertNull(read.album, "$ext album")
            assertNull(read.albumArtist, "$ext album artist")
            assertNull(read.year, "$ext year")
            assertNull(read.trackNumber, "$ext track")
            assertNull(read.genre, "$ext genre")
            assertFalse(read.hasArtwork, "$ext cover removed")
        }
    }

    @Test
    fun keepingTheCoverAndOtherTagsLeavesThemAlone() {
        for (ext in formats.filter { it != "opus" }) {
            val file = fixture(ext)
            val cover = png(48)
            TagWriter.apply(file, full.copy(cover = cover))
            // A tag the editor doesn't show must survive an edit.
            AudioFileIO.read(file).apply { tag.setField(FieldKey.COMPOSER, "Ada Lane"); commit() }
            TagWriter.apply(file, full.copy(title = "Renamed"))
            assertEquals("Renamed", TagReader.read(file).title, ext)
            assertEquals("Ada Lane", AudioFileIO.read(file).tag.getFirst(FieldKey.COMPOSER), "$ext composer kept")
            assertContentEquals(cover.bytes, TagReader.readArtwork(file), "$ext cover kept")
        }
        val opus = fixture("opus")
        TagWriter.apply(opus, full.copy(cover = png(48)))
        OpusTags.write(opus, OpusTags.read(opus).apply { setField(FieldKey.COMPOSER, "Ada Lane") })
        TagWriter.apply(opus, full.copy(title = "Renamed"))
        assertEquals("Ada Lane", OpusTags.read(opus).getFirst(FieldKey.COMPOSER), "opus composer kept")
        assertTrue(TagReader.read(opus).hasArtwork, "opus cover kept")
    }

    @Test
    fun editedFilesStillPlay() {
        val player = runCatching { GstPlayer(audioSink = "fakesink") }.getOrNull()
        assumeTrue("GStreamer is not installed", player != null)
        player!!.use { p ->
            for (ext in formats) {
                val file = fixture(ext)
                TagWriter.apply(file, full.copy(cover = png(64)))
                p.load(file.absolutePath)
                p.play()
                Thread.sleep(250)
                assertNull(p.pollError(), "$ext plays after editing")
                val duration = p.durationMs()
                assertTrue(duration != null && abs(duration - 2_000) <= 100, "$ext length after editing: $duration")
                p.pause()
            }
        }
    }
}
