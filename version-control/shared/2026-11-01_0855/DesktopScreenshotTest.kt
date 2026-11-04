package dev.recorddisc.player.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.AccentPreset
import dev.recorddisc.player.app.ThemeMode
import dev.recorddisc.player.app.FakePlayer
import dev.recorddisc.player.app.LibraryPlatform
import dev.recorddisc.player.app.ScanState
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.Lyrics
import dev.recorddisc.player.library.Lrc
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibraryDatabase
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.PlaylistBundlePreview
import dev.recorddisc.player.library.PlaylistBundlePreviewSong
import dev.recorddisc.player.library.PlaylistTextFile
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

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
        /** What the song details editor is told about the file. */
        var support: EditSupport = EditSupport.Editable
        override suspend fun editSupport(track: TrackEntity) = support
        override suspend fun lyricsSupport(track: TrackEntity) = support
        /** The mockup's lyrics for Midnight Drive. */
        override suspend fun readLyrics(track: TrackEntity): Lyrics? = if (!track.location.contains("Midnight Drive")) null else Lrc.parse(
            "[00:06.20]Headlights paint the empty road\n[00:12.80]Neon hums a slower code\n[00:19.40]Every sign says take it slow\n" +
                "[00:26.00]Every exit, let it go\n[00:34.10]Midnight drive, no need to hide\n[00:40.60]City breathing at my side\n" +
                "[00:47.20]Radio glow, a borrowed light\n[00:53.80]We don't stop until it's bright\n",
        )
        override val suggestedFolder = "/home/you/Music"
        override fun folderLabel(folder: String) = folder.replace("/home/you", "~")
        override suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap? =
            songs.firstOrNull { location.endsWith("${it.title}.flac") }?.let { gradientArt(it.colors, maxSizePx) }
        override suspend fun readPlaylistText(location: String) =
            PlaylistTextFile("Imported Mix.m3u8", "#EXTM3U\nmissing-track.flac")
        override suspend fun readPlaylistBundle(location: String, library: List<TrackEntity>) =
            PlaylistBundlePreview(
                name = "Imported Bundle",
                songs = listOf(
                    PlaylistBundlePreviewSong(
                        sourcePath = "/tmp/mock-bundle/Midnight Drive.flac",
                        fileName = "Midnight Drive.flac",
                        lyricsPath = "/tmp/mock-bundle/Midnight Drive.lrc",
                        tags = TrackTags(title = "Midnight Drive", artist = "Neon Coast", durationMs = 222_000),
                        existingTrack = library.firstOrNull(),
                        warning = null,
                    ),
                ),
                coverBytes = byteArrayOf(1),
                coverExtension = "png",
                temporaryDirectory = "/tmp/mock-bundle",
            )
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

    private fun scene(
        model: AppModel,
        view: DesktopView = DesktopView.Home,
        playlistId: Long? = null,
        playlistActions: PlaylistFileActions = PlaylistFileActions(),
    ) = ImageComposeScene(1440, 852, Density(1f)) {
        DesktopApp(
            model,
            chooseFolder = { null },
            startView = view,
            appVersion = "0.1.0",
            playlistActions = playlistActions,
            startPlaylistId = playlistId,
        )
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = buildList {
        add(this@descendants)
        children.forEach { addAll(it.descendants()) }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }

    private fun nodeWithText(scene: ImageComposeScene, text: String): SemanticsNode? =
        nodes(scene).firstOrNull { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text }
        }

    private fun click(scene: ImageComposeScene, text: String) {
        settle(scene)
        val node = assertNotNull(
            nodes(scene).firstOrNull { candidate ->
                val labels = candidate.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
                val descriptions = candidate.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                val actionLabel = if (candidate.config.contains(SemanticsActions.OnClick)) candidate.config[SemanticsActions.OnClick].label else null
                text in labels || text in descriptions || actionLabel == text
            },
            "No visible semantics node for '$text': " + nodes(scene).mapNotNull { candidate ->
                val actionLabel = if (candidate.config.contains(SemanticsActions.OnClick)) candidate.config[SemanticsActions.OnClick].label else null
                val labels = candidate.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
                (labels + listOfNotNull(actionLabel)).takeIf { it.isNotEmpty() }?.joinToString()
            }.distinct().joinToString(" | "),
        )
        assertTrue(node.config.contains(SemanticsActions.OnClick), "Text '$text' is not clickable")
        val click = node.config[SemanticsActions.OnClick]
        assertNotNull(click.action, "Text '$text' has no click action").invoke()
        settle(scene)
    }

    private fun settle(scene: ImageComposeScene) {
        repeat(8) { frame ->
            Thread.sleep(25)
            scene.render((frame + 1) * 33_000_000L)
        }
    }

    private fun capture(scene: ImageComposeScene, name: String) {
        val image = scene.render(3_000_000_000L)
        val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(out, "$name.png").writeBytes(png)
        assertTrue(png.size > 10_000, "$name rendered")
    }

    /** Draws a few seconds of frames (so the disc is mid-spin), then saves the last one. */
    private fun shoot(
        model: AppModel, name: String, width: Int = 1440, height: Int = 852, view: DesktopView = DesktopView.Home,
        library: LibraryNav? = null, picker: Boolean = false, editor: Boolean = false, lyricsEditor: Boolean = false,
        playlistId: Long? = null,
    ) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            DesktopApp(
                model, chooseFolder = { null }, startView = view, startLibrary = library, appVersion = "0.1.0",
                startAccentPicker = picker, startEditor = editor, startLyricsEditor = lyricsEditor, startPlaylistId = playlistId,
            )
        }
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
    fun lyricsEditorPausesPlaybackAndDoesNotResumeWhenClosed() {
        val platform = MockupPlatform()
        val player = FakePlayer()
        val model = model(platform, player)
        model.scan(platform.suggestedFolder)
        model.scan.await { it is ScanState.Done }
        model.finishSetup()
        model.settings.await { it?.setupDone == true }
        val tracks = model.tracks.await { it.size == 4 }
        player.state.value = PlaybackState(tracks.map { it.toQueueItem() }, index = 0, isPlaying = true)

        val editor = ImageComposeScene(1440, 852, Density(1f)) {
            DesktopApp(model, chooseFolder = { null }, startLyricsEditor = true)
        }
        try {
            settle(editor)
            assertNotNull(nodeWithText(editor, "Edit lyrics"), "lyrics editor is visible")
            assertFalse(player.state.value.isPlaying, "opening the lyrics editor pauses playback")
        } finally {
            editor.close()
        }
        assertFalse(player.state.value.isPlaying, "closing the lyrics editor does not resume playback")
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
        val roadTrip = runBlocking {
            model.createPlaylist("Road Trip").also { playlist ->
                tracks.take(3).forEach { model.addToPlaylist(playlist.id, it.id) }
            }
        }
        model.playlists.await { playlists -> playlists.any { it.id == roadTrip.id && it.songCount == 3 } }
        shoot(model, "34-playlists", view = DesktopView.Playlists)
        shoot(model, "35-playlist-detail", view = DesktopView.Playlists, playlistId = roadTrip.id)
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
        model.setLibraryQuery("")
        shoot(model, "15-settings", view = DesktopView.Settings)
        shoot(model, "16-settings-narrow", width = 860, height = 1000, view = DesktopView.Settings)
        model.setCustomAccent(0xFF3674B3)
        model.settings.await { it?.accent == null }
        shoot(model, "17-accent-picker", view = DesktopView.Settings, picker = true)
        shoot(model, "18-settings-custom-accent", view = DesktopView.Settings)
        // Light theme (Settings → Theme → Light).
        model.setTheme(ThemeMode.Light)
        model.settings.await { it?.theme == ThemeMode.Light }
        shoot(model, "26-light-accent-picker", view = DesktopView.Settings, picker = true)
        shoot(model, "29-light-tag-editor", editor = true)
        model.setTheme(ThemeMode.Dark)
        model.settings.await { it?.theme == ThemeMode.Dark }
        shoot(model, "27-tag-editor", editor = true)
        shoot(model, "28-tag-editor-narrow", width = 860, height = 1000, editor = true)
        platform.support = EditSupport.ReadOnly("You don't have permission to change this file")
        shoot(model, "30-tag-editor-read-only", editor = true)
        platform.support = EditSupport.Editable
        // Lyrics: the panel under Home's buttons (line at 42 s highlighted), and the editor.
        model.setShowLyrics(true)
        model.settings.await { it?.showLyrics == true }
        shoot(model, "31-lyrics-panel")
        shoot(model, "32-lyrics-editor", lyricsEditor = true)
        shoot(model, "33-lyrics-panel-narrow", width = 860, height = 1200)
        model.setShowLyrics(false)
        model.settings.await { it?.showLyrics == false }
        model.setTheme(ThemeMode.Light)
        model.settings.await { it?.theme == ThemeMode.Light }
        model.setAccent(AccentPreset.Gold)
        model.settings.await { it?.accent == AccentPreset.Gold }
        shoot(model, "19-light-home")
        shoot(model, "20-light-home-narrow", width = 860, height = 1000)
        shoot(model, "21-light-library", view = DesktopView.Library)
        shoot(model, "22-light-albums", view = DesktopView.Library, library = LibraryNav(LibraryTab.Albums))
        shoot(model, "23-light-settings", view = DesktopView.Settings)
        shoot(model, "24-light-settings-narrow", width = 860, height = 1000, view = DesktopView.Settings)
        model.setAccent(AccentPreset.Mint)
        model.settings.await { it?.accent == AccentPreset.Mint }
        shoot(model, "25-light-home-mint")
    }

    @Test
    fun playlistDialogs() {
        val platform = MockupPlatform()
        val model = model(platform)
        model.scan(platform.suggestedFolder)
        model.scan.await { it is ScanState.Done }
        model.finishSetup()
        model.settings.await { it?.setupDone == true }
        val tracks = model.tracks.await { it.size == 4 }
        val playlist = runBlocking {
            model.createPlaylist("Road Trip").also { created ->
                tracks.take(2).forEach { model.addToPlaylist(created.id, it.id) }
            }
        }
        model.playlists.await { playlists -> playlists.any { it.id == playlist.id && it.songCount == 2 } }

        val addScene = scene(model, view = DesktopView.Library)
        try {
            click(addScene, "More options")
            click(addScene, "Add to playlist")
            capture(addScene, "36-add-to-playlist")
        } finally {
            addScene.close()
        }

        val exportScene = scene(model, view = DesktopView.Playlists, playlistId = playlist.id)
        try {
            click(exportScene, "Export")
            capture(exportScene, "37-export-m3u8-options")
            click(exportScene, "Record Disc")
            capture(exportScene, "38-export-record-disc-options")
        } finally {
            exportScene.close()
        }

        val importScene = scene(
            model,
            view = DesktopView.Playlists,
            playlistActions = PlaylistFileActions(openM3u = { done -> done("mock.m3u8") }),
        )
        try {
            click(importScene, "Import")
            click(importScene, "M3U8")
            var frames = 0
            while (nodeWithText(importScene, "Review M3U8") == null && frames < 40) {
                settle(importScene)
                frames++
            }
            assertNotNull(nodeWithText(importScene, "Review M3U8"), "M3U8 import review opens")
            capture(importScene, "39-import-m3u8-review")
        } finally {
            importScene.close()
        }

        val bundleImportScene = scene(
            model,
            view = DesktopView.Playlists,
            playlistActions = PlaylistFileActions(openBundle = { done -> done("mock.rdplaylist") }),
        )
        try {
            click(bundleImportScene, "Import")
            click(bundleImportScene, "Record Disc")
            var frames = 0
            while (nodeWithText(bundleImportScene, "Review Record Disc") == null && frames < 40) {
                settle(bundleImportScene)
                frames++
            }
            assertNotNull(nodeWithText(bundleImportScene, "Review Record Disc"), "Record Disc import review opens")
            capture(bundleImportScene, "40-import-record-disc-review")
        } finally {
            bundleImportScene.close()
        }
    }

    @Test
    fun playlistAddActionsOpenLibrary() {
        val platform = MockupPlatform()
        val model = model(platform)
        model.scan(platform.suggestedFolder)
        model.scan.await { it is ScanState.Done }
        model.finishSetup()
        model.settings.await { it?.setupDone == true }
        val emptyPlaylist = runBlocking { model.createPlaylist("Empty Mix") }
        model.playlists.await { playlists -> playlists.any { it.id == emptyPlaylist.id } }

        val emptyStateScene = scene(model, view = DesktopView.Playlists, playlistId = emptyPlaylist.id)
        try {
            click(emptyStateScene, "Add songs to this playlist from your Library ›")
            assertNotNull(
                nodeWithText(emptyStateScene, "LOCAL LIBRARY"),
                "empty-playlist action opens Library",
            )
        } finally {
            emptyStateScene.close()
        }

        val optionsScene = scene(model, view = DesktopView.Playlists, playlistId = emptyPlaylist.id)
        try {
            click(optionsScene, "⋮")
            click(optionsScene, "Add songs to playlist")
            assertNotNull(
                nodeWithText(optionsScene, "LOCAL LIBRARY"),
                "playlist option opens Library",
            )
        } finally {
            optionsScene.close()
        }
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
