package dev.recorddisc.player.tags

import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpusTagsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fields = mapOf(
        FieldKey.TITLE to "Midnight Drive", FieldKey.ARTIST to "Neon Coast", FieldKey.ALBUM to "After Hours",
        FieldKey.YEAR to "2024", FieldKey.GENRE to "Synthwave", FieldKey.TRACK to "1"
    )

    private fun fixture(name: String): File =
        File(tmp.root, name).also { out -> javaClass.getResourceAsStream("/audio/$name")!!.use { it.copyTo(out.outputStream()) } }

    private fun png(size: Int, noise: Boolean): File {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(7)
        for (x in 0 until size) for (y in 0 until size) img.setRGB(x, y, if (noise) rnd.nextInt() else 0xE0A458)
        return tmp.newFile().also { ImageIO.write(img, "png", it) }
    }

    private fun tag(file: File, cover: File?) {
        val tag = OpusTags.read(file)
        fields.forEach { (k, v) -> tag.setField(k, v) }
        tag.deleteArtworkField()
        if (cover != null) tag.setField(ArtworkFactory.createArtworkFromFile(cover))
        OpusTags.write(file, tag)
    }

    private fun assertTagged(file: File, cover: File?) {
        val tag = OpusTags.read(file)
        fields.forEach { (k, v) -> assertEquals(v, tag.getFirst(k), "field $k") }
        if (cover != null) assertContentEquals(cover.readBytes(), tag.firstArtwork.binaryData)
    }

    /** Audio packets and their granule positions, i.e. everything after the two Opus header packets. */
    private fun audioPages(file: File): List<Pair<Long, List<Byte>>> {
        val b = file.readBytes()
        val pages = ArrayList<Pair<Long, List<Byte>>>()
        var pos = 0
        var packetsSeen = 0
        while (pos < b.size) {
            val segments = b[pos + 26].toInt() and 0xFF
            val lacing = (0 until segments).map { b[pos + 27 + it].toInt() and 0xFF }
            var granule = 0L
            for (i in 7 downTo 0) granule = (granule shl 8) or (b[pos + 6 + i].toLong() and 0xFF)
            val bodyStart = pos + 27 + segments
            val body = b.copyOfRange(bodyStart, bodyStart + lacing.sum())
            if (packetsSeen >= 2) pages += granule to body.toList()
            packetsSeen += lacing.count { it < 255 }
            pos = bodyStart + body.size
        }
        return pages
    }

    @Test
    fun rewritingUnchangedTagsReproducesTheFileExactly() {
        val original = fixture("tone.opus").readBytes()
        val file = fixture("tone.opus")
        OpusTags.write(file, OpusTags.read(file))
        assertContentEquals(original, file.readBytes())
    }

    @Test
    fun writesFieldsAndSmallCover() {
        val file = fixture("tone.opus")
        val before = audioPages(file)
        val cover = png(40, noise = false)
        tag(file, cover)
        assertTagged(file, cover)
        assertEquals(before, audioPages(file))
    }

    @Test
    fun writesCoverLargerThanOneOggPage() {
        val file = fixture("tone.opus")
        val before = audioPages(file)
        val cover = png(220, noise = true)
        assertTrue(cover.length() > 65_025, "cover must exceed one Ogg page")
        tag(file, cover)
        assertTagged(file, cover)
        assertEquals(before, audioPages(file))
    }

    @Test
    fun shrinksFromMultiPageBackToSinglePage() {
        val file = fixture("tone.opus")
        val before = audioPages(file)
        tag(file, png(220, noise = true))
        val small = png(40, noise = false)
        tag(file, small)
        assertTagged(file, small)
        assertEquals(before, audioPages(file))
    }

    @Test
    fun refusesDamagedFileAndLeavesItUntouched() {
        val file = fixture("tone.opus")
        val bytes = file.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
        file.writeBytes(bytes)
        assertFailsWith<OpusTags.OpusTagException> { tag(file, null) }
        assertContentEquals(bytes, file.readBytes())
        assertTrue(tmp.root.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty(), "temp file left behind")
    }

    @Test
    fun refusesTruncatedFileAndLeavesItUntouched() {
        val file = fixture("tone.opus")
        val bytes = file.readBytes().let { it.copyOf(it.size * 2 / 3) }
        file.writeBytes(bytes)
        assertFailsWith<java.io.IOException> { tag(file, null) }
        assertContentEquals(bytes, file.readBytes())
    }

    @Test
    fun refusesVorbisFile() {
        val file = fixture("tone.ogg")
        assertFailsWith<OpusTags.OpusTagException> { OpusTags.read(file) }
    }
}
