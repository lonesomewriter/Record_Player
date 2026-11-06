package dev.recorddisc.player.library

import dev.recorddisc.player.tags.TagReader
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopTagSaverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(ext: String, dir: File = tmp.newFolder()): File =
        File(dir, "tone.$ext").also { out -> javaClass.getResourceAsStream("/audio/tone.$ext")!!.use { it.copyTo(out.outputStream()) } }

    private val edit = TagEdit("New Title", "New Artist", "New Album", null, 2025, 3, "Ambient")

    @Test
    fun savesAtomicallyAndLeavesNoTempFiles() {
        for (ext in listOf("flac", "mp3", "opus")) {
            val file = fixture(ext)
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r-----"))
            assertEquals(EditSupport.Editable, DesktopTagSaver.support(file))
            val saved = DesktopTagSaver.save(file, edit)
            assertEquals("New Title", saved.tags.title, ext)
            assertEquals(file.length(), saved.sizeBytes, ext)
            assertEquals("New Title", TagReader.read(file).title, ext)
            assertEquals(listOf("tone.$ext"), file.parentFile.list()!!.toList(), "$ext: only the song is left")
            assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())), "$ext permissions kept")
        }
    }

    @Test
    fun readOnlyFileIsRefusedAndUnchanged() {
        val dir = tmp.newFolder()
        val file = fixture("flac", dir)
        val original = file.readBytes()
        file.setWritable(false)
        assumeTrue("running as a user who can write anyway (root)", !file.canWrite())
        assertIs<EditSupport.ReadOnly>(DesktopTagSaver.support(file))
        file.setWritable(true)
        dir.setWritable(false)
        try {
            assertIs<EditSupport.ReadOnly>(DesktopTagSaver.support(file), "folder without write access")
            assertFailsWith<Exception> { DesktopTagSaver.save(file, edit) }
            assertContentEquals(original, file.readBytes())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun failedEditLeavesTheOriginalByteForByte() {
        val file = fixture("flac")
        val original = file.readBytes()
        // Garbage with a song's name: jaudiotagger can't read it, so the edit fails before the swap.
        val broken = File(file.parentFile, "broken.flac").apply { writeBytes(ByteArray(4096) { 7 }) }
        val brokenBytes = broken.readBytes()
        assertFailsWith<Exception> { DesktopTagSaver.save(broken, edit) }
        assertContentEquals(brokenBytes, broken.readBytes())
        assertContentEquals(original, file.readBytes())
        assertTrue(file.parentFile.list()!!.none { it.startsWith(".rd-edit-") }, "temp file removed")
    }
}
