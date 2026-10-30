package dev.recorddisc.player.library

/** New tag values from the song details editor. A null value removes that tag. */
data class TagEdit(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val genre: String?,
    val cover: CoverChange = CoverChange.Keep,
) {
    companion object {
        /**
         * Builds an edit from the editor's text fields, where a blank field removes that tag.
         * Returns null if the year or track isn't valid (see [TagRules]).
         */
        fun fromText(
            title: String, artist: String, album: String, albumArtist: String,
            year: String, track: String, genre: String, cover: CoverChange = CoverChange.Keep,
        ): TagEdit? {
            if (!TagRules.yearValid(year) || !TagRules.trackValid(track)) return null
            fun text(value: String) = value.trim().takeIf { it.isNotEmpty() }
            return TagEdit(
                title = text(title),
                artist = text(artist),
                album = text(album),
                albumArtist = text(albumArtist),
                year = text(year)?.toInt(),
                trackNumber = text(track)?.toInt(),
                genre = text(genre),
                cover = cover,
            )
        }
    }
}

sealed interface CoverChange {
    data object Keep : CoverChange
    data object Remove : CoverChange

    /** A JPEG or PNG image to embed as the front cover. */
    class Replace(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) : CoverChange
}

/** What the editor accepts. */
object TagRules {
    const val MAX_COVER_BYTES = 10 * 1024 * 1024
    val COVER_TYPES = setOf("image/jpeg", "image/png")

    /** Blank (no year) or four digits. */
    fun yearValid(text: String): Boolean = text.isBlank() || text.trim().let { it.length == 4 && it.all(Char::isDigit) && it.toInt() >= 1 }

    /** Blank (no track number) or 1–999. */
    fun trackValid(text: String): Boolean = text.isBlank() || text.trim().let { it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 1..999 }
}

/** Whether a song's file can be changed. */
sealed interface EditSupport {
    data object Editable : EditSupport

    /** Android: the music folder was granted for reading only. */
    data object NeedsWriteAccess : EditSupport

    data class ReadOnly(val reason: String) : EditSupport
}

/** A save that was refused or didn't check out; [message] is written for the user. The file is unchanged. */
class TagSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A song file after its tags were saved: its new size and time, and the tags read back from it. */
data class SavedFile(val sizeBytes: Long, val modifiedMs: Long, val tags: TrackTags)
