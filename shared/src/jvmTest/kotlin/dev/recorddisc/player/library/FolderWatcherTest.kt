package dev.recorddisc.player.library

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderWatcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun seesFilesAddedChangedAndRemovedAnywhereBelow() {
        val music = tmp.newFolder("Music")
        val album = File(music, "Artist/Album").apply { mkdirs() }
        val changes = Semaphore(0)
        FolderWatcher(music.toPath()) { changes.release() }.use {
            Thread.sleep(300) // let it register the folders
            File(album, "song.flac").writeText("x")
            assertTrue(changes.tryAcquire(5, TimeUnit.SECONDS), "file added in a subfolder")

            val newFolder = File(music, "New Artist").apply { mkdirs() }
            assertTrue(changes.tryAcquire(5, TimeUnit.SECONDS), "folder added")
            Thread.sleep(300)
            changes.drainPermits()
            File(newFolder, "song.mp3").writeText("x")
            assertTrue(changes.tryAcquire(5, TimeUnit.SECONDS), "file added in a folder created after watching began")

            changes.drainPermits()
            File(album, "song.flac").delete()
            assertTrue(changes.tryAcquire(5, TimeUnit.SECONDS), "file removed")
        }
        changes.drainPermits()
        File(music, "after.flac").writeText("x")
        assertFalse(changes.tryAcquire(1, TimeUnit.SECONDS), "nothing reported after close")
    }
}
