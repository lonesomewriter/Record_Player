package dev.recorddisc.player.app

import dev.recorddisc.player.library.PlaylistImportFile
import dev.recorddisc.player.library.TrackEntity
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaylistFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun exportsAndImportsWithoutOverwritingExistingSongs() = runBlocking {
        val platform = DesktopLibraryPlatform()
        val music = temporary.newFolder("Music")
        val source = temporary.newFolder("bundle")
        val audio = File(source, "song.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val lyrics = File(source, "song.lrc").apply { writeText("[00:01.00]Line\n") }
        val destination = File(temporary.root, "playlist.m3u8")
        val output = "#EXTM3U\nsong.flac\n".encodeToByteArray()

        platform.writeExport(destination.absolutePath, output)
        assertContentEquals(output, destination.readBytes())
        assertEquals(output.decodeToString(), platform.readPlaylistText(destination.absolutePath).text)

        val imported = platform.importSongs(
            "Road Trip",
            music.absolutePath,
            listOf(PlaylistImportFile(audio.absolutePath, "song.flac", lyrics.absolutePath)),
        ).single()
        assertContentEquals(audio.readBytes(), File(imported.location).readBytes())
        assertContentEquals(lyrics.readBytes(), File(imported.lyricsLocation!!).readBytes())
        assertTrue(imported.location.startsWith(File(music, "Imported/Road Trip").canonicalPath))

        val repeated = platform.importSongs(
            "Road Trip",
            music.absolutePath,
            listOf(PlaylistImportFile(audio.absolutePath, "song.flac")),
        ).single()
        assertEquals("song (2).flac", File(repeated.location).name)
        assertContentEquals(audio.readBytes(), File(repeated.location).readBytes())
    }

    @Test
    fun resolvesM3uPathsAgainstItsFolderThenTheMusicFolder() = runBlocking {
        val platform = DesktopLibraryPlatform()
        val music = temporary.newFolder("Music")
        val song = File(music, "Artist/song.flac").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val playlist = File(music, "Playlists/list.m3u8").apply {
            parentFile.mkdirs()
            writeText("#EXTM3U\n")
        }

        assertEquals(song.canonicalPath, platform.resolvePlaylistPath("Artist/song.flac", playlist.absolutePath, music.absolutePath))
        assertEquals(song.canonicalPath, platform.resolvePlaylistPath("../Artist/song.flac", playlist.absolutePath, music.absolutePath))
    }

    @Test
    fun streamsGeneratedBundleFilesToTheSelectedPath() = runBlocking {
        val platform = DesktopLibraryPlatform()
        val source = File(temporary.root, "bundle.rdplaylist").apply { writeBytes(ByteArray(1024 * 1024) { it.toByte() }) }
        val destination = File(temporary.root, "saved.rdplaylist")

        platform.writeExportFile(destination.absolutePath, source.absolutePath)

        assertTrue(Files.mismatch(source.toPath(), destination.toPath()) == -1L)
    }

    @Test
    fun exportsAndPreviewsBundlesInIsolatedStorageAndCleansUp() = runBlocking {
        val dataDirectory = File(temporary.root, "app-data")
        val platform = DesktopLibraryPlatform(dataDirectory)
        val audio = File(temporary.root, "original.flac").also { destination ->
            javaClass.getResourceAsStream("/audio/tone.flac")!!.use { it.copyTo(destination.outputStream()) }
        }
        val cover = File(temporary.root, "cover.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val destination = File(temporary.root, "playlist.rdplaylist")
        val track = TrackEntity(
            id = 1,
            location = audio.absolutePath,
            fileName = audio.name,
            format = "flac",
            sizeBytes = audio.length(),
            modifiedMs = 1,
            title = "Song",
            artist = "Artist",
            album = null,
            albumArtist = null,
            year = null,
            trackNumber = null,
            discNumber = null,
            genre = null,
            durationMs = 2_000,
            hasArtwork = false,
            tagsReadable = true,
        )

        platform.writePlaylistBundle(
            destination.absolutePath,
            "Road Trip",
            listOf(track),
            cover.absolutePath,
            fallbackCover = null,
            includeLyrics = false,
        )
        val preview = platform.readPlaylistBundle(destination.absolutePath, emptyList())
        val temporaryDirectory = File(preview.temporaryDirectory)
        try {
            assertEquals("Road Trip", preview.name)
            assertEquals("original.flac", preview.songs.single().fileName)
            assertContentEquals(audio.readBytes(), File(preview.songs.single().sourcePath).readBytes())
            assertContentEquals(cover.readBytes(), preview.coverBytes)
            assertTrue(temporaryDirectory.isDirectory)
        } finally {
            platform.cleanupPlaylistBundlePreview(preview)
        }
        assertFalse(temporaryDirectory.exists())
    }
}
