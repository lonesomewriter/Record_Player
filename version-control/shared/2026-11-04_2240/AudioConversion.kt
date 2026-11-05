package dev.recorddisc.player.convert

/*
 * "To audio file" (prototype, 2026-11-04): turn a playlist into one audio file on the device.
 * Each platform decodes the songs one by one into plain samples and feeds them, with any silent
 * gaps, into a single encoder; song starts are then marked in the file where the format allows.
 * See project-notes/convert-to-audio-plan.md.
 */

/** Output formats. [marks]: whether the file can say where each song starts. */
enum class ConvertFormat(val extension: String, val marks: Boolean) {
    Flac("flac", marks = true),
    Mp3("mp3", marks = false),
    M4a("m4a", marks = true),
    Ogg("ogg", marks = true),
    Opus("opus", marks = true),
    Wav("wav", marks = false),
    ;

    /** Opus only works at 48 kHz; everything else is written at CD rate. */
    val sampleRate: Int get() = if (this == Opus) 48_000 else 44_100
}

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
) {
    init {
        require(gapSeconds >= 0) { "The gap can't be negative" }
        require(inputs.isNotEmpty()) { "Nothing to convert" }
    }
}

/** How far along: the song being converted (0-based) and the fraction of the whole job. */
data class ConvertProgress(val song: Int, val songs: Int, val fraction: Float)

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
