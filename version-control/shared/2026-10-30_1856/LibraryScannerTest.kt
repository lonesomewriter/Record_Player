package dev.recorddisc.player.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.recorddisc.player.tags.OpusTags
import dev.recorddisc.player.tags.TagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: LibraryDatabase
    private lateinit var music: File
    private lateinit var source: FolderLibrarySource

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<LibraryDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        music = tmp.newFolder("Music")
        // Nested folders, like a real library: Artist/Album/track.
        tagged("tone.mp3", "Neon Coast/After Hours/01 Midnight Drive.mp3", "Midnight Drive", "Neon Coast", "After Hours", "1")
        tagged("tone.flac", "Neon Coast/After Hours/02 Paper Lanterns.flac", "Paper Lanterns", "Neon Coast", "After Hours", "2")
        tagged("tone.ogg", "Mira Vale/Slow Light/Static Bloom.ogg", "Static Bloom", "Mira Vale", "Slow Light", "1")
        tagged("tone.opus", "Mira Vale/Copper Sky.opus", "Copper Sky", "Mira Vale", null, null)
        File(music, "notes.txt").writeText("not music")
        File(music, ".hidden").mkdirs()
        fixture("tone.mp3").copyTo(File(music, ".hidden/secret.mp3"))
        File(music, "broken.mp3").writeText("this is not audio")
        source = FolderLibrarySource(listOf(music))
    }

    @After
    fun tearDown() = db.close()

    private fun fixture(name: String): File =
        File(tmp.root, "fixture-$name").also { out ->
            if (!out.exists()) javaClass.getResourceAsStream("/audio/$name")!!.use { it.copyTo(out.outputStream()) }
        }

    private fun tagged(fixture: String, path: String, title: String, artist: String, album: String?, track: String?) {
        val file = File(music, path).also { it.parentFile.mkdirs(); fixture(fixture).copyTo(it) }
        TagReader // applies the shared jaudiotagger settings
        if (file.extension == "opus") {
            val tag = OpusTags.read(file)
            tag.setField(FieldKey.TITLE, title); tag.setField(FieldKey.ARTIST, artist)
            OpusTags.write(file, tag)
        } else {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, title); tag.setField(FieldKey.ARTIST, artist)
            album?.let { tag.setField(FieldKey.ALBUM, it) }
            track?.let { tag.setField(FieldKey.TRACK, it) }
            tag.setField(FieldKey.YEAR, "2024")
            audio.commit()
        }
    }

    private fun scanner() = LibraryScanner(db.tracks())

    @Test
    fun initialScanFindsAudioAndSkipsHiddenAndUnsupportedFiles() = runTest {
        val result = scanner().scan(source)
        assertEquals(ScanResult(found = 5, added = 5, updated = 0, removed = 0, unchanged = 0, unreadable = 1), result)
        val names = db.tracks().observeAll().first().map { it.fileName }.toSet()
        assertFalse("secret.mp3" in names, "hidden folder must be skipped")
        assertFalse("notes.txt" in names)
    }

    @Test
    fun readsTagsAndDurationsForEveryFormat() = runTest {
        scanner().scan(source)
        val tracks = db.tracks().observeAll().first().associateBy { it.title }
        assertEquals(setOf("Midnight Drive", "Paper Lanterns", "Static Bloom", "Copper Sky", null), tracks.keys)
        val drive = tracks.getValue("Midnight Drive")
        assertEquals("Neon Coast", drive.artist)
        assertEquals("After Hours", drive.album)
        assertEquals(1, drive.trackNumber)
        assertEquals(2024, drive.year)
        for (title in listOf("Midnight Drive", "Paper Lanterns", "Static Bloom", "Copper Sky")) {
            val track = tracks.getValue(title)
            assertTrue(track.tagsReadable, "$title tags readable")
            val duration = track.durationMs ?: error("$title has no duration")
            // MP3 stores whole 1152-sample frames plus encoder padding, so its length runs a little long.
            assertTrue(abs(duration - 2_000) <= 80, "$title duration ~2000 ms, got $duration")
        }
    }

    @Test
    fun brokenFileIsListedButMarkedUnreadable() = runTest {
        scanner().scan(source)
        val broken = db.tracks().observeAll().first().single { it.fileName == "broken.mp3" }
        assertFalse(broken.tagsReadable)
        assertEquals("broken", broken.displayTitle)
    }

    @Test
    fun rescanWithoutChangesReadsNothing() = runTest {
        scanner().scan(source)
        assertEquals(ScanResult(found = 5, added = 0, updated = 0, removed = 0, unchanged = 5, unreadable = 0), scanner().scan(source))
    }

    @Test
    fun changedFileIsReread() = runTest {
        scanner().scan(source)
        val file = File(music, "Mira Vale/Slow Light/Static Bloom.ogg")
        val audio = AudioFileIO.read(file)
        audio.tag.setField(FieldKey.TITLE, "Static Bloom (Live)")
        audio.commit()
        file.setLastModified(file.lastModified() + 10_000)

        val result = scanner().scan(source)
        assertEquals(1, result.updated)
        assertEquals(4, result.unchanged)
        assertTrue(db.tracks().observeAll().first().any { it.title == "Static Bloom (Live)" })
        assertEquals(5, db.tracks().count())
    }

    @Test
    fun deletedFileIsRemoved() = runTest {
        scanner().scan(source)
        assertTrue(File(music, "Mira Vale/Copper Sky.opus").delete())
        val result = scanner().scan(source)
        assertEquals(1, result.removed)
        assertEquals(4, db.tracks().count())
    }

    @Test
    fun folderWithNoMusicLeavesTheLibraryAlone() = runTest {
        scanner().scan(source)
        val empty = File(music.parentFile, "empty").apply { mkdirs() }
        val result = scanner().scan(FolderLibrarySource(listOf(empty)))
        assertEquals(0, result.found)
        assertEquals(0, result.removed)
        assertEquals(5, db.tracks().count())
    }

    @Test
    fun albumsArtistsAndSearch() = runTest {
        scanner().scan(source)
        val albums = db.tracks().observeAlbums().first()
        assertEquals(listOf(AlbumSummary("After Hours", "Neon Coast", 2024, 2), AlbumSummary("Slow Light", "Mira Vale", 2024, 1)), albums)
        val artists = db.tracks().observeArtists().first()
        assertEquals(listOf(ArtistSummary("Mira Vale", 2), ArtistSummary("Neon Coast", 2)), artists)
        assertEquals(listOf("Midnight Drive"), db.tracks().searchText("midnight").first().map { it.title })
        assertEquals(emptyList(), db.tracks().searchText("%").first(), "wildcards are matched literally")
    }
}
