package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.recorddisc.player.library.DesktopPaths
import dev.recorddisc.player.library.DesktopLyricsSaver
import dev.recorddisc.player.library.DesktopTagSaver
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.AudioFormats
import dev.recorddisc.player.library.FolderLibrarySource
import dev.recorddisc.player.library.FolderWatcher
import dev.recorddisc.player.library.ImportedPlaylistSong
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.Lyrics
import dev.recorddisc.player.library.M3u
import dev.recorddisc.player.library.PlaylistImportFile
import dev.recorddisc.player.library.PlaylistBundlePreview
import dev.recorddisc.player.library.PlaylistBundlePreviewSong
import dev.recorddisc.player.library.PlaylistTextFile
import dev.recorddisc.player.library.SavedFile
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.displayTitle
import dev.recorddisc.player.tags.TagReader
import dev.recorddisc.player.tags.PlaylistBundle
import dev.recorddisc.player.tags.PlaylistBundleSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.math.max
import kotlin.math.roundToInt

/** Linux: folders are plain paths; cover art comes from jaudiotagger and is decoded by Skia. */
class DesktopLibraryPlatform(private val dataDirectory: File = DesktopPaths.dataDir()) : LibraryPlatform {
    private val home = System.getProperty("user.home")

    override val suggestedFolder: String? = DesktopPaths.musicDir().takeIf { it.isDirectory }?.absolutePath
    override fun currentTimeMs(): Long = System.currentTimeMillis()

    override fun sourceFor(folder: String): LibrarySource = FolderLibrarySource(listOf(File(folder)))

    override fun watch(folder: String, onChange: () -> Unit): AutoCloseable = FolderWatcher(File(folder).toPath(), onChange)

    override suspend fun editSupport(track: TrackEntity): EditSupport = withContext(Dispatchers.IO) {
        if (!track.tagsReadable) EditSupport.ReadOnly(UNREADABLE) else DesktopTagSaver.support(File(track.location))
    }

    override suspend fun saveTags(track: TrackEntity, edit: TagEdit): SavedFile = withContext(Dispatchers.IO) {
        DesktopTagSaver.save(File(track.location), edit)
    }

    override suspend fun readLyrics(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) { DesktopLyricsSaver.read(track) }

    override suspend fun lyricsSupport(track: TrackEntity): EditSupport = withContext(Dispatchers.IO) { DesktopLyricsSaver.support(track) }

    override suspend fun saveLyrics(track: TrackEntity, text: String?): String? = withContext(Dispatchers.IO) { DesktopLyricsSaver.save(track, text) }

    override suspend fun writeExport(destination: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        writeAtomically(File(destination)) { output -> bytes.inputStream().use { it.copyTo(output) } }
    }

    override suspend fun writeExportFile(
        destination: String,
        sourcePath: String,
        onProgress: (PlaylistBundleExportProgress) -> Unit,
        checkActive: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        require(source.isFile) { "Export file can't be read" }
        val total = source.length()
        var copied = 0L
        onProgress(PlaylistBundleExportProgress(PlaylistBundleExportStage.Saving, copied, total))
        writeAtomically(File(destination), checkActive = checkActive) { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress(PlaylistBundleExportProgress(PlaylistBundleExportStage.Saving, copied, total))
                }
            }
        }
    }

    override suspend fun readPlaylistText(location: String): PlaylistTextFile = withContext(Dispatchers.IO) {
        val source = File(location)
        require(source.isFile) { "Playlist file can't be read" }
        require(source.length() <= M3u.MAX_IMPORT_BYTES) { "Playlist file is too large" }
        val bytes = source.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= M3u.MAX_IMPORT_BYTES) { "Playlist file is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        PlaylistTextFile(source.name, bytes.decodeToString(throwOnInvalidSequence = true))
    }

    override suspend fun writePlaylistBundle(
        destination: String,
        name: String,
        songs: List<TrackEntity>,
        customCoverPath: String?,
        fallbackCover: TrackEntity?,
        includeLyrics: Boolean,
        onProgress: (PlaylistBundleExportProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val checkActive = { coroutineContext.ensureActive() }
        val transferDirectory = playlistTransferDirectory().apply {
            check(isDirectory || mkdirs()) { "Couldn't prepare playlist export storage" }
        }
        val temporaryDirectory = Files.createTempDirectory(transferDirectory.toPath(), "export-").toFile()
        var exportFailure: Exception? = null
        try {
            val bundleSongs = songs.map { track ->
                val lyrics = if (!includeLyrics) {
                    null
                } else {
                    track.lyricsLocation?.let(::File)?.also { require(it.isFile) { "Lyrics file can't be read" } }
                }
                PlaylistBundleSong(File(track.location), track.displayTitle, track.artist, track.durationMs, lyrics)
            }
            val coverFile = customCoverPath?.let(::File) ?: fallbackCover?.takeIf { it.hasArtwork }?.let { track ->
                val bytes = TagReader.readArtwork(File(track.location)) ?: return@let null
                val extension = imageExtension(bytes) ?: throw java.io.IOException("The first song's cover isn't JPEG or PNG")
                File(temporaryDirectory, "cover.$extension").apply { writeBytes(bytes) }
            }
            if (customCoverPath != null) require(coverFile?.isFile == true) { "Playlist cover can't be read" }
            val bundleFile = File(temporaryDirectory, "playlist.rdplaylist")
            FileOutputStream(bundleFile).use { output ->
                PlaylistBundle.write(
                    output,
                    name,
                    bundleSongs,
                    coverFile,
                    includeLyrics,
                    onProgress = { completed, total ->
                        onProgress(PlaylistBundleExportProgress(PlaylistBundleExportStage.Packaging, completed, total))
                    },
                    checkActive = checkActive,
                )
                output.flush()
                output.fd.sync()
            }
            writeExportFile(destination, bundleFile.absolutePath, onProgress, checkActive)
        } catch (error: Exception) {
            exportFailure = error
            throw error
        } finally {
            if (temporaryDirectory.exists() && !temporaryDirectory.deleteRecursively()) {
                val cleanupError = java.io.IOException("Couldn't remove temporary playlist export files")
                val failure = exportFailure
                if (failure == null) throw cleanupError
                failure.addSuppressed(cleanupError)
            }
        }
    }

    override suspend fun readPlaylistBundle(location: String, library: List<TrackEntity>): PlaylistBundlePreview =
        withContext(Dispatchers.IO) {
            val source = File(location)
            require(source.isFile) { "Playlist bundle can't be read" }
            val root = playlistImportDirectory().apply { check(isDirectory || mkdirs()) { "Couldn't prepare playlist import storage" } }
            val contents = source.inputStream().use { PlaylistBundle.read(it, root) }
            try {
                val inspection = PlaylistBundle.inspect(contents, library)
                val coverBytes = contents.coverFile?.readBytes()
                PlaylistBundlePreview(
                    name = contents.name,
                    songs = inspection.map { item ->
                        PlaylistBundlePreviewSong(
                            sourcePath = item.song.file.absolutePath,
                            fileName = item.song.file.name.substringAfter(" - "),
                            lyricsPath = item.song.lyricsFile?.absolutePath,
                            tags = item.tags,
                            existingTrack = item.existingTrack,
                            warning = item.warning,
                        )
                    },
                    coverBytes = coverBytes,
                    coverExtension = contents.coverFile?.extension?.lowercase(),
                    temporaryDirectory = contents.temporaryDirectory.absolutePath,
                )
            } catch (error: Exception) {
                if (contents.temporaryDirectory.exists() && !contents.temporaryDirectory.deleteRecursively()) {
                    error.addSuppressed(java.io.IOException("Couldn't remove temporary playlist import files"))
                }
                throw error
            }
        }

    override suspend fun cleanupPlaylistBundlePreview(preview: PlaylistBundlePreview) = withContext(Dispatchers.IO) {
        cleanupPlaylistImportDirectory(preview.temporaryDirectory)
    }

    override suspend fun importSongs(
        playlistName: String,
        musicFolder: String,
        files: List<PlaylistImportFile>,
    ): List<ImportedPlaylistSong> = withContext(Dispatchers.IO) {
        require(playlistName.isNotBlank()) { "Playlist name can't be empty" }
        val root = File(musicFolder).canonicalFile
        require(root.isDirectory) { "The music folder isn't available" }
        val imported = File(root, "Imported")
        if (!imported.exists()) check(imported.mkdir()) { "Couldn't create the Imported folder" }
        check(imported.isDirectory && imported.canonicalFile.parentFile == root) { "Invalid Imported folder" }
        val name = safeDirectoryName(playlistName)
        var target = File(imported, name)
        if (target.exists() && !target.isDirectory) {
            var suffix = 2
            while (File(imported, "$name ($suffix)").exists()) suffix++
            target = File(imported, "$name ($suffix)")
        }
        if (!target.exists()) check(target.mkdir()) { "Couldn't create the playlist import folder" }
        check(target.isDirectory && target.canonicalFile.parentFile == imported.canonicalFile) { "Invalid playlist import folder" }

        val createdFiles = ArrayList<File>()
        try {
            val usedNames = target.list().orEmpty().mapTo(HashSet()) { it.lowercase() }
            val importedSongs = ArrayList<ImportedPlaylistSong>(files.size)
            for (file in files) {
                require(AudioFormats.isSupported(file.fileName)) { "Unsupported audio file: ${file.fileName}" }
                val source = File(file.sourcePath)
                require(source.isFile) { "Imported song can't be read: ${file.fileName}" }
                val audioName = uniqueFileName(file.fileName, usedNames)
                val audio = File(target, audioName)
                createdFiles += audio
                source.inputStream().use { input -> FileOutputStream(audio).use { input.copyTo(it) } }
                val lyrics = file.lyricsPath?.let { lyricsPath ->
                    val lyricsSource = File(lyricsPath)
                    require(lyricsSource.isFile && lyricsSource.extension.equals("lrc", ignoreCase = true)) {
                        "Imported lyrics can't be read"
                    }
                    val lyricsName = audioName.substringBeforeLast('.') + ".lrc"
                    val uniqueLyricsName = uniqueFileName(lyricsName, usedNames)
                    val lyricsTarget = File(target, uniqueLyricsName)
                    createdFiles += lyricsTarget
                    lyricsSource.inputStream().use { input -> FileOutputStream(lyricsTarget).use { input.copyTo(it) } }
                    usedNames += uniqueLyricsName.lowercase()
                    lyricsTarget.absolutePath
                }
                usedNames += audioName.lowercase()
                importedSongs += ImportedPlaylistSong(audio.absolutePath, lyrics)
            }
            importedSongs
        } catch (error: Exception) {
            createdFiles.forEach { file ->
                if (file.exists() && !file.delete()) error.addSuppressed(java.io.IOException("Couldn't remove partial import ${file.name}"))
            }
            if (target.exists() && target.listFiles().isNullOrEmpty() && !target.delete()) {
                error.addSuppressed(java.io.IOException("Couldn't remove empty import folder"))
            }
            throw error
        }
    }

    override suspend fun savePlaylistCover(playlistId: Long, extension: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(extension in PLAYLIST_COVER_EXTENSIONS && bytes.isNotEmpty() && bytes.size <= TagRules.MAX_COVER_BYTES) {
            "Playlist cover must be a JPEG or PNG image, 10 MB or smaller"
        }
        val directory = playlistCoverDirectory().apply {
            check(isDirectory || mkdirs()) { "Couldn't create playlist cover storage" }
        }
        val target = File(directory, "$playlistId.$extension")
        val temporary = File(directory, ".$playlistId-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
            target.absolutePath
        } finally {
            temporary.delete()
        }
    }

    override suspend fun deletePlaylistCover(path: String) = withContext(Dispatchers.IO) {
        val file = playlistCoverFile(path)
        if (file.exists() && !file.delete()) throw java.io.IOException("Couldn't remove the playlist cover")
    }

    override suspend fun loadPlaylistCover(path: String, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = playlistCoverFile(path)
        if (!file.isFile) return@withContext null
        Image.makeFromEncoded(file.readBytes()).use { image ->
            val scale = maxSizePx.toFloat() / max(image.width, image.height)
            if (scale >= 1f) return@withContext image.toComposeImageBitmap()
            val w = (image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (image.height * scale).roundToInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(w, h).use { surface ->
                surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }

    override fun folderLabel(folder: String): String =
        if (home != null && (folder == home || folder.startsWith("$home/"))) "~" + folder.removePrefix(home) else folder

    override fun relativePlaylistPath(track: TrackEntity, musicFolder: String): String {
        val root = File(musicFolder).canonicalFile.toPath()
        val location = File(track.location).canonicalFile.toPath()
        return if (location.startsWith(root)) root.relativize(location).toString().replace('\\', '/') else track.location
    }

    override suspend fun resolvePlaylistPath(path: String, playlistLocation: String?, musicFolder: String): String? =
        withContext(Dispatchers.IO) {
            val raw = File(path)
            val candidates = if (raw.isAbsolute) {
                listOf(raw)
            } else {
                listOfNotNull(
                    playlistLocation?.let { File(it).absoluteFile.parentFile?.resolve(path) },
                    File(musicFolder).resolve(path),
                )
            }
            candidates.firstOrNull { it.isFile }?.canonicalPath
        }

    override suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = TagReader.readArtwork(File(location)) ?: return@withContext null
        Image.makeFromEncoded(bytes).use { image ->
            val scale = maxSizePx.toFloat() / max(image.width, image.height)
            if (scale >= 1f) return@withContext image.toComposeImageBitmap()
            val w = (image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (image.height * scale).roundToInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(w, h).use { surface ->
                surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }

    private fun playlistCoverDirectory() = File(dataDirectory, "playlist-covers")

    private fun playlistTransferDirectory() = File(dataDirectory, "playlist-transfer")

    private fun playlistImportDirectory() = File(dataDirectory, "playlist-imports")

    private fun cleanupPlaylistImportDirectory(path: String) {
        val root = playlistImportDirectory().canonicalFile
        val directory = File(path).canonicalFile
        require(directory.parentFile == root && directory.name.startsWith("rdplaylist-")) {
            "Invalid temporary playlist import directory"
        }
        if (directory.exists() && !directory.deleteRecursively()) throw java.io.IOException("Couldn't remove temporary playlist import files")
    }

    private fun imageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
        else -> null
    }

    private fun writeAtomically(target: File, checkActive: () -> Unit = {}, write: (java.io.OutputStream) -> Unit) {
        val parent = target.absoluteFile.parentFile ?: throw java.io.IOException("Export folder isn't available")
        require(parent.isDirectory) { "Export folder isn't available" }
        val temporary = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                write(output)
                checkActive()
                output.flush()
                output.fd.sync()
            }
            checkActive()
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun safeDirectoryName(name: String): String =
        name.replace(Regex("[\\\\/\\u0000-\\u001F]"), "_").replace("..", "_").trim()
            .takeIf(String::isNotEmpty) ?: "Playlist"

    private fun uniqueFileName(name: String, used: Set<String>): String {
        val clean = File(name).name.replace(Regex("[\\\\/\\u0000-\\u001F]"), "_").replace("..", "_")
        require(clean.isNotBlank() && clean != "." && clean != "..") { "Invalid imported file name" }
        if (clean.lowercase() !in used) return clean
        val base = clean.substringBeforeLast('.', clean)
        val extension = clean.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        var suffix = 2
        var candidate: String
        do {
            candidate = "$base ($suffix)$extension"
            suffix++
        } while (candidate.lowercase() in used)
        return candidate
    }

    private fun playlistCoverFile(path: String): File {
        val root = playlistCoverDirectory().canonicalFile
        val file = File(path).canonicalFile
        require(file.parentFile == root && file.name.matches(PLAYLIST_COVER_NAME)) { "Invalid playlist cover path" }
        return file
    }

    private companion object {
        const val UNREADABLE = "Its tags couldn't be read, so saving could damage it"
        val PLAYLIST_COVER_EXTENSIONS = setOf("jpg", "jpeg", "png")
        val PLAYLIST_COVER_NAME = Regex("""\d+\.(jpg|jpeg|png)""")
    }
}
