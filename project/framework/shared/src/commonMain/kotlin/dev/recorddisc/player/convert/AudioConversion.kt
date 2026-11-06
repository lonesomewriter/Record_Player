package dev.recorddisc.player.convert

/*
 * Convert to Audio (2026-11-04): turn a playlist into one audio file on the device. Each platform
 * decodes the songs one by one into plain samples and feeds them, with any silent gaps, into a
 * single encoder; song starts are then marked in the file where the format allows.
 * See project-notes/convert-to-audio-plan.md.
 */

/**
 * Output formats. [marks]: whether the file can say where each song starts. [label] is what the
 * format list shows; [mbPerMinute] is a rough size for the estimate (the mockup's figures).
 */
enum class ConvertFormat(val extension: String, val marks: Boolean, val label: String, val mbPerMinute: Double) {
    Flac("flac", marks = true, "FLAC · lossless", 6.5),
    Mp3("mp3", marks = false, "MP3 · 320 kbps", 2.4),
    M4a("m4a", marks = true, "M4A · AAC 256 kbps", 1.9),
    Ogg("ogg", marks = true, "OGG Vorbis · q6", 1.4),
    Opus("opus", marks = true, "Opus · 160 kbps", 1.2),
    Wav("wav", marks = false, "WAV · uncompressed", 10.1),
    ;

    /** Opus only works at 48 kHz; everything else is written at CD rate. */
    val sampleRate: Int get() = if (this == Opus) 48_000 else 44_100

    /** The note under the format: whether players will be able to skip between songs. */
    val marksNote: String
        get() = when (this) {
            Flac -> "Song starts are marked (cue sheet), so players that support it can skip between songs."
            M4a, Ogg, Opus -> "Song starts are marked as chapters, so players that support them can skip between songs."
            Mp3 -> "MP3 can't mark where each song starts; it's one continuous track."
            Wav -> "WAV can't mark where each song starts; it's one continuous track."
        }
}

/** A format as this device offers it: greyed out with [note] when it can't be used here. */
data class ConvertFormatOption(val format: ConvertFormat, val available: Boolean = true, val note: String? = null)

/** One song to join: where it is (a file path, or an Android content URI) and its title for the marks. */
data class ConvertInput(val location: String, val title: String)

data class ConvertRequest(
    val inputs: List<ConvertInput>,
    val format: ConvertFormat,
    /** Silence between songs, in whole seconds; never below 0. */
    val gapSeconds: Int,
    /** Where to write (a file path, or an Android content URI). */
    val output: String,
    /** Tags for the file: the playlist's name becomes its title and album. */
    val title: String,
    /** Even out volume: measure each song first, then bring it to a common level. */
    val evenOutVolume: Boolean = false,
) {
    init {
        require(gapSeconds >= 0) { "The gap can't be negative" }
        require(inputs.isNotEmpty()) { "Nothing to convert" }
    }
}

/**
 * How far along: the song being worked on (0-based), the fraction of the whole job, and whether
 * this is Even out volume's measuring pass.
 */
data class ConvertProgress(val song: Int, val songs: Int, val fraction: Float, val measuring: Boolean = false)

/** What was written: length in samples per channel, the rate, and where each song starts. */
data class ConvertResult(val totalSamples: Long, val sampleRate: Int, val songStarts: List<Long>)

/** Thrown when the user cancels; the partial file has already been deleted. */
class ConvertCancelled : Exception("Conversion cancelled")

/**
 * Works out where each song starts from the songs' decoded lengths (samples per channel) and the
 * gap: song 1 starts at 0, song N after the previous songs and N-1 gaps.
 */
fun songStarts(lengths: List<Long>, gapSamples: Long): List<Long> {
    var position = 0L
    return lengths.mapIndexed { i, length ->
        position.also { position += length + if (i < lengths.lastIndex) gapSamples else 0 }
    }
}

/** Progress across both passes when Even out volume is on (measuring is the first half). */
fun overallFraction(song: Int, songs: Int, withinSong: Float, measuring: Boolean, evenOut: Boolean): Float {
    val base = (song + withinSong.coerceIn(0f, 1f)) / songs
    return when {
        !evenOut -> base
        measuring -> base / 2
        else -> 0.5f + base / 2
    }
}

object ConvertRules {
    /**
     * The gap field: whole seconds, never below 0 (decision 6). Blank, negative or unreadable
     * input becomes 0; fractions round.
     */
    fun parseGap(text: String): Int {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return 0
        if (value.isNaN() || value <= 0) return 0
        return minOf(kotlin.math.round(value), Int.MAX_VALUE.toDouble()).toInt()
    }

    /** Total length in milliseconds: the songs plus a gap between each pair. */
    fun totalMs(songMs: List<Long?>, gapSeconds: Int): Long =
        songMs.sumOf { it ?: 0L } + gapSeconds.toLong() * 1000 * (songMs.size - 1).coerceAtLeast(0)

    /** "≈ 312 MB · 48 min": a rough size for [format] and the length. */
    fun estimate(format: ConvertFormat, totalMs: Long): String {
        val minutes = totalMs / 60_000.0
        val mb = format.mbPerMinute * minutes
        val size = when {
            mb >= 1024 -> "${kotlin.math.round(mb / 102.4) / 10.0} GB"
            mb >= 1 -> "${kotlin.math.round(mb).toLong()} MB"
            else -> "under 1 MB"
        }
        val length = when {
            minutes >= 60 -> "${(minutes / 60).toInt()} hr ${(minutes % 60).toInt()} min"
            minutes >= 1 -> "${kotlin.math.round(minutes).toLong()} min"
            else -> "${totalMs / 1000} s"
        }
        return "≈ $size · $length"
    }

    /** Bytes the finished file may need, with room to spare, for the free-space check. */
    fun bytesNeeded(format: ConvertFormat, totalMs: Long): Long =
        (format.mbPerMinute * (totalMs / 60_000.0) * 1024 * 1024 * 1.2).toLong() + 8L * 1024 * 1024

    /**
     * A file name from the playlist's name: characters file systems reject become "_", and an
     * empty result becomes "Playlist". The extension is added by the caller.
     */
    fun fileName(playlistName: String): String {
        val cleaned = playlistName.map { c -> if (c in "/\\:*?\"<>|" || c.code < 32) '_' else c }.joinToString("").trim().trim('.')
        return cleaned.ifEmpty { "Playlist" }.take(120)
    }

    /** [name] with [format]'s extension, replacing any audio extension already typed. */
    fun withExtension(name: String, format: ConvertFormat): String {
        val base = name.trim().let { n ->
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ConvertFormat.entries.any { it.extension == ext }) n.substringBeforeLast('.') else n
        }
        return "${fileName(base)}.${format.extension}"
    }
}

/** What the Convert to Audio dialog asks for. [destination] null means the default Exports folder. */
data class ConvertOptions(
    val format: ConvertFormat,
    val gapSeconds: Int,
    val evenOutVolume: Boolean,
    val fileName: String,
    /** A file chosen with Change / Choose… (a path on desktop, a document URI on Android), or null for the default folder. */
    val destination: String? = null,
    /** How [destination] is shown ("Downloads/Road Trip.flac"); ignored when it's null. */
    val destinationLabel: String? = null,
)

/** Everything a platform needs to make the file. */
data class ConvertJob(
    val inputs: List<ConvertInput>,
    val format: ConvertFormat,
    val gapSeconds: Int,
    val evenOutVolume: Boolean,
    /** The playlist's name: the file's title and album tags. */
    val title: String,
    /** The file name, extension included. */
    val fileName: String,
    val destination: String?,
    /** Rough size of the finished file plus room to spare, for the free-space check. */
    val bytesNeeded: Long,
)

/** Where the finished file went: how to show it, and its location and folder (for Show in folder). */
data class ConvertSaved(val shownAs: String, val location: String, val folder: String?)
