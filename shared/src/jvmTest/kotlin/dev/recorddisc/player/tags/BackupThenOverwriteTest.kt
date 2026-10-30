package dev.recorddisc.player.tags

import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagSaveException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android save strategy, with a fake document store that can "crash" part-way through. */
class BackupThenOverwriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** One document; [crashAfterBytes] makes the next overwrite stop part-way, like the app being killed. */
    private class FakeStore(val doc: File) : DocumentStore {
        var crashAfterBytes: Int? = null
        var overwrites = 0
        override fun size(uri: String) = doc.length()
        override fun openRead(uri: String): InputStream = doc.inputStream()
        override fun overwrite(uri: String, source: File) {
            overwrites++
            val bytes = source.readBytes()
            val limit = crashAfterBytes
            if (limit != null) {
                crashAfterBytes = null
                doc.writeBytes(bytes.copyOf(minOf(limit, bytes.size)))
                throw IOException("killed")
            }
            doc.writeBytes(bytes)
        }
    }

    /** Stands in for the app process being killed: not an Exception, so nothing catches it. */
    private class Killed : Error()

    private val uri = "content://test/tree/music/document/tone.flac"
    private val edit = TagEdit("Saved Title", "Saved Artist", null, null, 2023, 1, null)

    private fun song(): File = File(tmp.newFolder(), "tone.flac").also { out ->
        javaClass.getResourceAsStream("/audio/tone.flac")!!.use { it.copyTo(out.outputStream()) }
    }

    @Test
    fun savesAndCleansUp() {
        val store = FakeStore(song())
        val dir = tmp.newFolder()
        val tags = BackupThenOverwrite(store, dir).save(uri, "tone.flac", edit)
        assertEquals("Saved Title", tags.title)
        assertEquals("Saved Title", TagReader.read(store.doc).title)
        assertFalse(File(dir, "pending.properties").exists())
        assertTrue(File(dir, "backup").list()!!.isEmpty() && File(dir, "work").list()!!.isEmpty(), "nothing left behind")
    }

    @Test
    fun firstSaveWorksBeforeItsFolderExists() {
        val store = FakeStore(song())
        // As on a phone before the first save: the app's tag-edit folder isn't there yet.
        val dir = File(tmp.newFolder(), "tag-edit")
        BackupThenOverwrite(store, dir).save(uri, "tone.flac", edit)
        assertEquals("Saved Title", TagReader.read(store.doc).title)
    }

    @Test
    fun failedOverwriteIsUndoneStraightAway() {
        val store = FakeStore(song())
        val original = store.doc.readBytes()
        store.crashAfterBytes = 1000
        assertFailsWith<TagSaveException> { BackupThenOverwrite(store, tmp.newFolder()).save(uri, "tone.flac", edit) }
        assertContentEquals(original, store.doc.readBytes())
    }

    @Test
    fun appKilledMidOverwriteIsRestoredOnNextLaunch() {
        val store = FakeStore(song())
        val original = store.doc.readBytes()
        val dir = tmp.newFolder()
        // Killed during the overwrite: the file is half written and the restore never ran.
        val killing = object : DocumentStore by store {
            override fun overwrite(uri: String, source: File) {
                store.doc.writeBytes(source.readBytes().copyOf(2000))
                throw Killed()
            }
        }
        assertFailsWith<Killed> { BackupThenOverwrite(killing, dir).save(uri, "tone.flac", edit) }
        assertTrue(File(dir, "pending.properties").exists(), "note left for next launch")
        val notice = BackupThenOverwrite(store, dir).recoverPending()
        assertNotNull(notice)
        assertContentEquals(original, store.doc.readBytes())
        assertFalse(File(dir, "pending.properties").exists())
        assertNull(BackupThenOverwrite(store, dir).recoverPending(), "nothing left to do")
    }

    @Test
    fun appKilledAfterACompleteOverwriteKeepsTheNewFile() {
        val store = FakeStore(song())
        val dir = tmp.newFolder()
        val killing = object : DocumentStore by store {
            override fun overwrite(uri: String, source: File) {
                store.doc.writeBytes(source.readBytes())
                throw Killed()
            }
        }
        assertFailsWith<Killed> { BackupThenOverwrite(killing, dir).save(uri, "tone.flac", edit) }
        assertNull(BackupThenOverwrite(store, dir).recoverPending(), "a finished save needs no notice")
        assertEquals("Saved Title", TagReader.read(store.doc).title)
        assertFalse(File(dir, "pending.properties").exists())
    }

    @Test
    fun tooLittleSpaceIsRefusedBeforeAnythingChanges() {
        val store = FakeStore(song())
        val original = store.doc.readBytes()
        val e = assertFailsWith<TagSaveException> { BackupThenOverwrite(store, tmp.newFolder(), freeBytes = { 1000 }).save(uri, "tone.flac", edit) }
        assertTrue(e.message!!.contains("free space"))
        assertEquals(0, store.overwrites)
        assertContentEquals(original, store.doc.readBytes())
    }
}
