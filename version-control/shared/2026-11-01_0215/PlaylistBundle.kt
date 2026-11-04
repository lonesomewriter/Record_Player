package dev.recorddisc.player.tags

import dev.recorddisc.player.library.AudioFormats
import dev.recorddisc.player.library.M3u
import dev.recorddisc.player.library.M3uPathMode
import dev.recorddisc.player.library.M3uTrack
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.library.matchTrackByTags
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PlaylistBundleSong(
    val file: File,
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
    val lyricsFile: File? = null,
    val archiveFileName: String = file.name,
)

data class ExtractedPlaylistSong(
    val archivePath: String,
    val file: File,
    val lyricsFile: File?,
)

data class PlaylistBundleContents(
    val name: String,
    val songs: List<ExtractedPlaylistSong>,
    val coverFile: File?,
    val temporaryDirectory: File,
)

data class PlaylistBundleSongInspection(
    val song: ExtractedPlaylistSong,
    val tags: TrackTags?,
    val existingTrack: TrackEntity?,
    val warning: String?,
)

object PlaylistBundle {
    private const val PLAYLIST_FILE = "playlist.m3u8"
    const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_PLAYLIST_BYTES = 8L * 1024 * 1024

    fun write(
        output: OutputStream,
        name: String,
        songs: List<PlaylistBundleSong>,
        coverFile: File? = null,
        includeLyrics: Boolean = true,
    ) {
        val archiveSongs = archiveSongs(songs, includeLyrics)
        if (coverFile != null) {
            require(coverFile.isFile && coverFile.extension.lowercase() in IMAGE_EXTENSIONS) {
                "Playlist cover must be a JPEG or PNG image"
            }
            require(coverFile.length() <= TagRules.MAX_COVER_BYTES) { "Playlist cover is too large" }
        }
        val playlistBytes = buildPlaylistBytes(name, songs, includeLyrics)

        val zip = ZipOutputStream(NonClosingOutputStream(output))
        var total = 0L
        total += playlistBytes.size
        require(total <= MAX_TOTAL_BYTES) { "Playlist bundle is too large" }
        putBytes(zip, PLAYLIST_FILE, playlistBytes)
        archiveSongs.forEach { archive ->
            total += archive.song.file.length()
            checkTotal(total)
            putFile(zip, archive.path, archive.song.file)
            archive.song.lyricsFile?.takeIf { includeLyrics }?.let { lyrics ->
                total += lyrics.length()
                checkTotal(total)
                putFile(zip, requireNotNull(archive.lyricsPath), lyrics)
            }
        }
        coverFile?.let { cover ->
            total += cover.length()
            checkTotal(total)
            putFile(zip, "cover.${cover.extension.lowercase()}", cover)
        }
        zip.finish()
        zip.flush()
    }

    fun read(input: InputStream, temporaryRoot: File): PlaylistBundleContents =
        read(input, temporaryRoot, Limits(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES, MAX_PLAYLIST_BYTES))

    fun inspect(
        contents: PlaylistBundleContents,
        library: List<TrackEntity>,
        readTags: (File) -> TrackTags = TagReader::read,
    ): List<PlaylistBundleSongInspection> = contents.songs.map { song ->
        try {
            val tags = readTags(song.file)
            PlaylistBundleSongInspection(
                song = song,
                tags = tags,
                existingTrack = matchTrackByTags(tags.title, tags.artist, tags.durationMs, library),
                warning = null,
            )
        } catch (error: Exception) {
            PlaylistBundleSongInspection(
                song = song,
                tags = null,
                existingTrack = null,
                warning = "Tags couldn't be read; duplicate detection was skipped.",
            )
        }
    }

    internal fun read(input: InputStream, temporaryRoot: File, limits: Limits): PlaylistBundleContents {
        require(temporaryRoot.isDirectory || temporaryRoot.mkdirs()) { "Couldn't prepare temporary storage" }
        val directory = Files.createTempDirectory(temporaryRoot.toPath(), "rdplaylist-").toFile()
        val files = LinkedHashMap<String, File>()
        try {
            val zip = ZipInputStream(NonClosingInputStream(input))
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validateEntryName(name, entry.isDirectory)
                require(name !in files) { "Duplicate file in playlist bundle" }
                val isPlaylist = name == PLAYLIST_FILE
                val maxSize = when {
                    isPlaylist -> limits.maxPlaylistBytes
                    name.startsWith("cover.") -> minOf(limits.maxEntryBytes, TagRules.MAX_COVER_BYTES.toLong())
                    else -> limits.maxEntryBytes
                }
                require(entry.size < 0 || entry.size <= maxSize) { "File in playlist bundle is too large" }
                val destination = File(directory, name)
                val expectedParent = if ('/' in name) File(directory, name.substringBefore('/')) else directory
                require(destination.canonicalFile.parentFile == expectedParent.canonicalFile) {
                    "Invalid file path in playlist bundle"
                }
                val parent = destination.parentFile
                check(parent != null && (parent.isDirectory || parent.mkdirs())) { "Couldn't prepare extracted files" }
                var copied = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                destination.outputStream().use { output ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        copied += count
                        total += count
                        require(copied <= maxSize) { "File in playlist bundle is too large" }
                        checkTotal(total, limits.maxTotalBytes)
                        output.write(buffer, 0, count)
                    }
                }
                if (entry.size >= 0) require(copied == entry.size) { "Incomplete file in playlist bundle" }
                files[name] = destination
                zip.closeEntry()
            }
            val playlistFile = files[PLAYLIST_FILE] ?: throw IllegalArgumentException("Playlist bundle has no playlist.m3u8")
            val playlistText = playlistFile.readText(Charsets.UTF_8)
            require(playlistText.lineSequence().firstOrNull()?.trim() == "#EXTM3U") {
                "Playlist bundle has an invalid playlist.m3u8"
            }
            val document = M3u.parse(playlistText)
            val name = document.playlistName?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Playlist bundle has no playlist name")
            val audioPaths = document.entries.map { entry ->
                require(entry.path.startsWith("songs/")) { "Invalid song path in playlist bundle" }
                validateEntryName(entry.path, isDirectory = false)
                entry.path
            }
            require(audioPaths.distinct().size == audioPaths.size) { "Playlist bundle repeats a song file" }
            val includedAudio = files.keys.filter { it.startsWith("songs/") }.toSet()
            require(includedAudio == audioPaths.toSet()) { "Playlist bundle doesn't match its playlist.m3u8" }
            val lyrics = files.keys.filter { it.startsWith("lyrics/") }.associateBy { it.substringAfter('/') }
            val extractedSongs = audioPaths.map { path ->
                val audioName = path.substringAfter('/')
                val lyricsName = audioName.substringBeforeLast('.') + ".lrc"
                ExtractedPlaylistSong(path, requireNotNull(files[path]), lyrics[lyricsName]?.let { files[it] })
            }
            val expectedLyrics = extractedSongs.mapNotNull { song -> song.lyricsFile?.let { "lyrics/${it.name}" } }.toSet()
            require(files.keys.filter { it.startsWith("lyrics/") }.toSet() == expectedLyrics) {
                "Playlist bundle contains unmatched lyrics"
            }
            val covers = files.keys.filter { it.startsWith("cover.") }
            require(covers.size <= 1) { "Playlist bundle contains more than one cover" }
            return PlaylistBundleContents(
                name = name,
                songs = extractedSongs,
                coverFile = covers.singleOrNull()?.let { files[it] },
                temporaryDirectory = directory,
            )
        } catch (error: Exception) {
            if (directory.exists() && !directory.deleteRecursively()) {
                error.addSuppressed(java.io.IOException("Couldn't remove temporary playlist extraction files"))
            }
            throw error
        }
    }

    internal data class Limits(val maxEntryBytes: Long, val maxTotalBytes: Long, val maxPlaylistBytes: Long)

    private data class ArchiveSong(val song: PlaylistBundleSong, val path: String, val lyricsPath: String?)

    private fun archiveSongs(songs: List<PlaylistBundleSong>, includeLyrics: Boolean): List<ArchiveSong> =
        songs.mapIndexed { index, song ->
            require(song.file.isFile && AudioFormats.isSupported(song.file.name) && AudioFormats.isSupported(song.archiveFileName)) {
                "Unsupported audio file: ${song.archiveFileName}"
            }
            val storedName = "${(index + 1).toString().padStart(2, '0')} - ${safeFileName(song.archiveFileName)}"
            val lyrics = song.lyricsFile?.takeIf { includeLyrics }
            if (lyrics != null) require(lyrics.isFile && lyrics.extension.equals("lrc", ignoreCase = true)) {
                "Lyrics file can't be read"
            }
            ArchiveSong(song, "songs/$storedName", lyrics?.let { "lyrics/${storedName.substringBeforeLast('.')}.lrc" })
        }

    private fun buildPlaylistBytes(name: String, songs: List<PlaylistBundleSong>, includeLyrics: Boolean): ByteArray {
        require(name.isNotBlank()) { "Playlist name can't be empty" }
        val entries = archiveSongs(songs, includeLyrics)
        return M3u.format(
            entries.map { M3uTrack(it.path, it.path, it.song.title, it.song.artist, it.song.durationMs) },
            paths = M3uPathMode.Relative,
            playlistName = name,
        ).encodeToByteArray().also { require(it.size <= MAX_PLAYLIST_BYTES) { "Playlist list is too large" } }
    }

    private fun safeFileName(name: String): String {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .replace("..", "_")
            .trim()
        require(safe.isNotEmpty() && safe != "." && safe != "..") { "Invalid file name" }
        return safe
    }

    private fun validateEntryName(name: String, isDirectory: Boolean) {
        require(!isDirectory && name.isNotBlank() && '\\' !in name && !name.startsWith('/') && '\u0000' !in name) {
            "Invalid file path in playlist bundle"
        }
        require(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid file path in playlist bundle" }
        when {
            name == PLAYLIST_FILE -> Unit
            name.startsWith("songs/") -> require(
                name.count { it == '/' } == 1 && AudioFormats.isSupported(name.substringAfter('/')),
            ) { "Unsupported song file in playlist bundle" }
            name.startsWith("lyrics/") -> require(
                name.count { it == '/' } == 1 && name.substringAfterLast('.').equals("lrc", ignoreCase = true),
            ) { "Unsupported lyrics file in playlist bundle" }
            name.startsWith("cover.") -> require(
                name.count { it == '/' } == 0 && name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS,
            ) { "Unsupported cover file in playlist bundle" }
            else -> throw IllegalArgumentException("Unexpected file in playlist bundle")
        }
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        val size = file.length()
        require(size <= MAX_ENTRY_BYTES) { "File in playlist bundle is too large" }
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc.value
        })
        val writtenCrc = CRC32()
        var written = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                require(written <= size) { "File changed while creating playlist bundle" }
                writtenCrc.update(buffer, 0, count)
                zip.write(buffer, 0, count)
            }
        }
        require(written == size && writtenCrc.value == crc.value) { "File changed while creating playlist bundle" }
        zip.closeEntry()
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun checkTotal(total: Long, maximum: Long = MAX_TOTAL_BYTES) {
        require(total <= maximum) { "Playlist bundle is too large" }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
}
