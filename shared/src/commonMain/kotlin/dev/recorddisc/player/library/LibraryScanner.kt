package dev.recorddisc.player.library

import kotlinx.coroutines.CancellationException

/** A file found by a [LibrarySource]. */
data class FileEntry(val location: String, val fileName: String, val sizeBytes: Long, val modifiedMs: Long)

/** Tags read from one file. Any field may be missing. */
data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
    val hasArtwork: Boolean = false,
)

/** Where music comes from: a desktop folder, or a folder granted through Android's folder picker. */
interface LibrarySource {
    suspend fun listFiles(): List<FileEntry>
    suspend fun readTags(entry: FileEntry): TrackTags
}

object AudioFormats {
    val extensions = setOf("mp3", "flac", "m4a", "ogg", "oga", "opus", "wav")

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun isSupported(fileName: String): Boolean = extensionOf(fileName) in extensions
}

data class ScanResult(val added: Int, val updated: Int, val removed: Int, val unchanged: Int, val unreadable: Int)

/**
 * Brings the database in line with a [LibrarySource]. Only new or changed files (by size and
 * modification time) are read; files that disappeared are removed. Files whose tags can't be
 * read are still listed so they remain playable.
 */
class LibraryScanner(private val dao: TrackDao) {
    /** [onProgress] gets the number of audio files checked so far and the current file name. */
    suspend fun scan(source: LibrarySource, onProgress: (checked: Int, fileName: String) -> Unit = { _, _ -> }): ScanResult {
        val known = dao.scanIndex().associateBy { it.location }
        val seen = HashSet<String>()
        val pending = ArrayList<TrackEntity>()
        var added = 0
        var updated = 0
        var unchanged = 0
        var unreadable = 0

        for (file in source.listFiles()) {
            if (!AudioFormats.isSupported(file.fileName) || !seen.add(file.location)) continue
            onProgress(seen.size, file.fileName)
            val old = known[file.location]
            if (old != null && old.sizeBytes == file.sizeBytes && old.modifiedMs == file.modifiedMs) {
                unchanged++
                continue
            }
            val tags = try {
                source.readTags(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (tags == null) unreadable++
            pending += toEntity(old?.id ?: 0, file, tags)
            if (old == null) added++ else updated++
            if (pending.size >= BATCH) {
                dao.upsert(pending)
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) dao.upsert(pending)

        val gone = known.keys - seen
        // SQLite limits the number of bound parameters, so delete in chunks.
        gone.chunked(BATCH).forEach { dao.deleteByLocations(it) }
        return ScanResult(added, updated, gone.size, unchanged, unreadable)
    }

    private fun toEntity(id: Long, file: FileEntry, tags: TrackTags?) = TrackEntity(
        id = id,
        location = file.location,
        fileName = file.fileName,
        format = AudioFormats.extensionOf(file.fileName),
        sizeBytes = file.sizeBytes,
        modifiedMs = file.modifiedMs,
        title = tags?.title,
        artist = tags?.artist,
        album = tags?.album,
        albumArtist = tags?.albumArtist,
        year = tags?.year,
        trackNumber = tags?.trackNumber,
        discNumber = tags?.discNumber,
        genre = tags?.genre,
        durationMs = tags?.durationMs,
        hasArtwork = tags?.hasArtwork ?: false,
        tagsReadable = tags != null,
    )

    private companion object {
        const val BATCH = 200
    }
}

/** Parses tag numbers such as "3", "03" or "3/12" (track 3 of 12). */
internal fun parseLeadingNumber(value: String?): Int? =
    value?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

/** Parses a year from values such as "2024" or "2024-05-01". */
internal fun parseYear(value: String?): Int? =
    value?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()
