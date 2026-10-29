package dev.recorddisc.player.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One audio file in the library. */
@Entity(tableName = "tracks", indices = [Index(value = ["location"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Absolute path on desktop; document URI on Android. */
    val location: String,
    val fileName: String,
    /** Lower-case file extension, e.g. "flac". */
    val format: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val durationMs: Long?,
    val hasArtwork: Boolean,
    /** False when the file is listed but its tags could not be read. */
    val tagsReadable: Boolean,
)

/** Title to show: the tag title, or the file name without extension. */
val TrackEntity.displayTitle: String
    get() = title ?: fileName.substringBeforeLast('.')

data class AlbumSummary(val name: String, val artist: String?, val year: Int?, val trackCount: Int)

data class ArtistSummary(val name: String, val trackCount: Int)

/** What a rescan needs to decide whether a file changed. */
data class ScanKey(val id: Long, val location: String, val sizeBytes: Long, val modifiedMs: Long)
