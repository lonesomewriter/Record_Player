package dev.recorddisc.player.tags

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.TrackTags
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistBundleTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun roundTripsUnchangedSongsLyricsAndCover() {
        val source = temporary.newFolder("source")
        val audio = File(source, "song.flac").apply { writeBytes(byteArrayOf(0, 1, 2, 0x7f)) }
        val lyrics = File(source, "song.lrc").apply { writeText("[00:01.00]Line\n") }
        val cover = File(source, "cover.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val output = ByteArrayOutputStream()

        PlaylistBundle.write(
            output,
            "Road Trip",
            listOf(PlaylistBundleSong(audio, "Song", "Artist", 123_000, lyrics)),
            cover,
        )
        val bytes = output.toByteArray()
        val extracted = PlaylistBundle.read(ByteArrayInputStream(bytes), temporary.newFolder("extract"))
        try {
            assertEquals("Road Trip", extracted.name)
            assertEquals(1, extracted.songs.size)
            assertContentEquals(audio.readBytes(), extracted.songs.single().file.readBytes())
            assertContentEquals(lyrics.readBytes(), extracted.songs.single().lyricsFile!!.readBytes())
            assertContentEquals(cover.readBytes(), extracted.coverFile!!.readBytes())
        } finally {
            extracted.temporaryDirectory.deleteRecursively()
        }

        val archive = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
        val methods = mutableMapOf<String, Int>()
        while (true) {
            val entry = archive.nextEntry ?: break
            methods[entry.name] = entry.method
        }
        assertTrue(methods.isNotEmpty())
        assertTrue(methods.values.all { it == ZipEntry.STORED })
    }

    @Test
    fun rejectsZipSlipAndCleansTemporaryExtraction() {
        val archive = maliciousArchive("../escaped.flac")
        val destination = temporary.newFolder("extract")

        assertFailsWith<IllegalArgumentException> {
            PlaylistBundle.read(ByteArrayInputStream(archive), destination)
        }

        assertFalse(File(destination, "escaped.flac").exists())
        assertTrue(destination.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsBundlesThatDoNotMatchTheirM3u() {
        val archive = maliciousArchive("songs/actual.flac", playlistPath = "songs/missing.flac")

        assertFailsWith<IllegalArgumentException> {
            PlaylistBundle.read(ByteArrayInputStream(archive), temporary.newFolder("extract"))
        }
    }

    @Test
    fun enforcesPerEntryAndTotalLimitsWhileStreaming() {
        val source = temporary.newFolder("source")
        val audio = File(source, "song.flac").apply { writeBytes(ByteArray(16) { it.toByte() }) }
        val output = ByteArrayOutputStream()
        PlaylistBundle.write(output, "Tiny", listOf(PlaylistBundleSong(audio, null, null, null)), includeLyrics = false)

        assertFailsWith<IllegalArgumentException> {
            PlaylistBundle.read(
                ByteArrayInputStream(output.toByteArray()),
                temporary.newFolder("extract-entry"),
                PlaylistBundle.Limits(maxEntryBytes = 8, maxTotalBytes = 1_000, maxPlaylistBytes = 1_000),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlaylistBundle.read(
                ByteArrayInputStream(output.toByteArray()),
                temporary.newFolder("extract-total"),
                PlaylistBundle.Limits(maxEntryBytes = 1_000, maxTotalBytes = 8, maxPlaylistBytes = 1_000),
            )
        }
    }

    @Test
    fun detectsExistingSongsByTagsAndDuration() {
        val source = temporary.newFolder("source")
        val audio = File(source, "song.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val output = ByteArrayOutputStream()
        PlaylistBundle.write(output, "Tags", listOf(PlaylistBundleSong(audio, null, null, null)))
        val contents = PlaylistBundle.read(ByteArrayInputStream(output.toByteArray()), temporary.newFolder("extract"))
        try {
            val libraryTrack = TrackEntity(
                id = 4,
                location = "/music/song.flac",
                fileName = "song.flac",
                format = "flac",
                sizeBytes = 3,
                modifiedMs = 1,
                title = "Song",
                artist = "Artist",
                album = null,
                albumArtist = null,
                year = null,
                trackNumber = null,
                discNumber = null,
                genre = null,
                durationMs = 120_000,
                hasArtwork = false,
                tagsReadable = true,
            )
            val inspection = PlaylistBundle.inspect(contents, listOf(libraryTrack)) {
                TrackTags(title = " Song ", artist = "artist", durationMs = 122_000)
            }.single()
            assertEquals(4L, inspection.existingTrack?.id)
            assertEquals(null, inspection.warning)

            val tooLong = PlaylistBundle.inspect(contents, listOf(libraryTrack)) {
                TrackTags(title = "Song", artist = "Artist", durationMs = 122_001)
            }.single()
            assertEquals(null, tooLong.existingTrack)

            val unreadable = PlaylistBundle.inspect(contents, listOf(libraryTrack)) {
                throw java.io.IOException("bad tag data")
            }.single()
            assertEquals(null, unreadable.tags)
            assertTrue(unreadable.warning != null)
        } finally {
            contents.temporaryDirectory.deleteRecursively()
        }
    }

    private fun maliciousArchive(songPath: String, playlistPath: String = songPath): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("playlist.m3u8"))
                zip.write("#EXTM3U\n#PLAYLIST:Test\n$playlistPath\n".encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(songPath))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()
}
