package dev.recorddisc.player.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.FakePlayer
import dev.recorddisc.player.app.LibraryPlatform
import dev.recorddisc.player.app.ScanState
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.toQueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the real desktop UI off-screen with the mockups' sample songs and saves PNGs to
 * build/screenshots/desktop, for the side-by-side comparison with the mockups. Also a smoke test:
 * each screen must compose and draw without errors.
 */
class DesktopScreenshotTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db: LibraryDatabase = Room.inMemoryDatabaseBuilder<LibraryDatabase>()
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private val out = File(System.getProperty("screenshotDir") ?: "build/screenshots/desktop").apply { mkdirs() }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private data class Song(val title: String, val artist: String, val album: String, val year: Int, val genre: String, val seconds: Int, val colors: IntArray)

    /** The four songs of the mockups, with cover art in their gradient colours. */
    private val songs = listOf(
        Song("Midnight Drive", "Neon Coast", "After Hours", 2024, "Synthwave", 222, intArrayOf(0xFFE0A458.toInt(), 0xFF8A4B2A.toInt(), 0xFF2B1A14.toInt())),
        Song("Paper Lanterns", "Mira Vale", "Slow Light", 2023, "Indie", 245, intArrayOf(0xFF7FB7A3.toInt(), 0xFF2D5A4F.toInt(), 0xFF122622.toInt())),
        Song("Static Bloom", "The Quiet Hours", "Signal", 2025, "Electronic", 178, intArrayOf(0xFF9A8CFF.toInt(), 0xFF4530A8.toInt(), 0xFF1B1240.toInt())),
        Song("Copper Sky", "Lumen Field", "Northbound", 2022, "Folk", 312, intArrayOf(0xFFFF9F6E.toInt(), 0xFFB3402B.toInt(), 0xFF3A130D.toInt())),
    )

    /** Two more albums, as in the Albums/Artists mockups, so artists can have more than one. */
    private val moreSongs = songs + listOf(
        Song("Low Tide", "Neon Coast", "Night Swim", 2021, "Synthwave", 201, intArrayOf(0xFF6FB3E0.toInt(), 0xFF285A7D.toInt(), 0xFF0F2233.toInt())),
        Song("Paper Moons", "Lumen Field", "Paper Moons", 2019, "Folk", 233, intArrayOf(0xFFF0C674.toInt(), 0xFFB0703A.toInt(), 0xFF3A2410.toInt())),
    )

    /** Holds the scan after [pauseAfter] files until [resume] completes. */
    private inner class MockupPlatform(val pauseAfter: Int = Int.MAX_VALUE, val songs: List<Song> = this@DesktopScreenshotTest.songs) : LibraryPlatform {
        val resume = CompletableDeferred<Unit>()
        override val suggestedFolder = "/home/you/Music"
        override fun folderLabel(folder: String) = folder.replace("/home/you", "~")
        override suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap? =
            songs.firstOrNull { location.endsWith("${it.title}.flac") }?.let { gradientArt(it.colors, maxSizePx) }
        override fun sourceFor(folder: String) = object : LibrarySource {
            var read = 0
            override suspend fun listFiles() = songs.map { FileEntry("$folder/${it.artist}/${it.title}.flac", "${it.title}.flac", 100, 1) }
            override suspend fun readTags(entry: FileEntry): TrackTags {
                if (++read > pauseAfter) resume.await()
                val s = songs.first { entry.fileName == "${it.title}.flac" }
                return TrackTags(s.title, s.artist, s.album, null, s.year, null, null, s.genre, s.seconds * 1000L, hasArtwork = true)
            }
        }
    }

    private fun gradientArt(colors: IntArray, size: Int): ImageBitmap = ImageBitmap(size, size).also { bmp ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(size.toFloat(), size.toFloat())) {
            drawRect(Brush.linearGradient(0f to Color(colors[0]), 0.55f to Color(colors[1]), 1f to Color(colors[2]), end = Offset(size.toFloat(), size.toFloat())))
        }
    }

    private fun <T> StateFlow<T>.await(check: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { first(check) } }

    /** Draws a few seconds of frames (so the disc is mid-spin), then saves the last one. */
    private fun shoot(model: AppModel, name: String, width: Int = 1440, height: Int = 852, view: DesktopView = DesktopView.Home, library: LibraryNav? = null) {
        val scene = ImageComposeScene(width, height, Density(1f)) { DesktopApp(model, chooseFolder = { null }, startView = view, startLibrary = library) }
        try {
            var image = scene.render(0)
            for (frame in 1..90) {
                Thread.sleep(8)
                image = scene.render(frame * 33_000_000L)
            }
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
            assertTrue(png.size > 10_000, "$name rendered")
        } finally {
            scene.close()
        }
    }

    private fun model(platform: LibraryPlatform, player: FakePlayer = FakePlayer()) = AppModel(db, player, platform, scope).also {
        it.settings.await { s -> s != null }
    }

    @Test
    fun firstRun() {
        val platform = MockupPlatform(pauseAfter = 2)
        val model = model(platform)
        shoot(model, "01-firstrun-welcome")
        model.scan(platform.suggestedFolder)
        model.scan.await { it is ScanState.Running && it.checked >= 3 }
        shoot(model, "02-firstrun-scanning")
        platform.resume.complete(Unit)
        model.scan.await { it is ScanState.Done }
        shoot(model, "03-firstrun-done")
    }

    @Test
    fun home() {
        val platform = MockupPlatform()
        val player = FakePlayer()
        val model = model(platform, player)
        model.scan(platform.suggestedFolder)
        model.scan.await { it is ScanState.Done }
        model.finishSetup()
        model.settings.await { it?.setupDone == true }
        val tracks = model.tracks.await { it.size == 4 }
        val queue = tracks.sortedBy { t -> songs.indexOfFirst { it.title == t.title } }.map { it.toQueueItem() }
        player.state.value = PlaybackState(queue, index = 0, isPlaying = true, positionMs = 42_000, durationMs = 222_000, volume = 0.72f)
        shoot(model, "04-home")
        shoot(model, "05-home-1100", width = 1100, height = 800)
        shoot(model, "06-home-narrow", width = 860, height = 1000)
        shoot(model, "07-library", view = DesktopView.Library)
        shoot(model, "08-library-narrow", width = 860, height = 1000, view = DesktopView.Library)
        model.setLibraryQuery("lumen")
        model.librarySongs.await { it.size == 1 }
        shoot(model, "09-library-search", view = DesktopView.Library)
    }

    @Test
    fun albumsAndArtists() {
        val platform = MockupPlatform(songs = moreSongs)
        val player = FakePlayer()
        val model = model(platform, player)
        model.scan(platform.suggestedFolder)
        model.scan.await { it is ScanState.Done }
        model.finishSetup()
        model.settings.await { it?.setupDone == true }
        val tracks = model.tracks.await { it.size == 6 }
        player.state.value = PlaybackState(tracks.map { it.toQueueItem() }, index = tracks.indexOfFirst { it.title == "Midnight Drive" }, durationMs = 222_000)
        val albums = model.albums.await { it.size == 6 }
        model.artists.await { it.size == 4 }
        val afterHours = albums.first { it.name == "After Hours" }.key
        shoot(model, "10-library-albums", view = DesktopView.Library, library = LibraryNav(LibraryTab.Albums))
        shoot(model, "11-album", view = DesktopView.Library, library = LibraryNav(LibraryTab.Albums, LibraryPage.AlbumPage(afterHours)))
        shoot(model, "12-library-artists", view = DesktopView.Library, library = LibraryNav(LibraryTab.Artists))
        shoot(model, "13-artist", view = DesktopView.Library, library = LibraryNav(LibraryTab.Artists, LibraryPage.ArtistPage("Neon Coast")))
        shoot(model, "14-library-albums-narrow", width = 860, height = 1000, view = DesktopView.Library, library = LibraryNav(LibraryTab.Albums))
    }
}
