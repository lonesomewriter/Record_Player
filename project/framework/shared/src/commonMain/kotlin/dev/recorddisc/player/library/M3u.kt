package dev.recorddisc.player.library

import kotlin.math.abs

enum class M3uPathMode { Relative, Full }

data class M3uTrack(
    val fullPath: String,
    val relativePath: String,
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
)

data class M3uEntry(
    val path: String,
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
)

data class M3uDocument(val playlistName: String?, val entries: List<M3uEntry>)

data class M3uMatchedEntry(val entry: M3uEntry, val track: TrackEntity)

data class M3uMatchResult(val matched: List<M3uMatchedEntry>, val notFound: List<M3uEntry>)

object M3u {
    const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    fun format(
        entries: List<M3uTrack>,
        paths: M3uPathMode = M3uPathMode.Relative,
        trackInfo: Boolean = true,
        playlistName: String? = null,
    ): String = buildString {
        appendLine("#EXTM3U")
        playlistName?.let { appendLine("#PLAYLIST:${safeLine(it)}") }
        entries.forEach { entry ->
            val path = when (paths) {
                M3uPathMode.Relative -> entry.relativePath
                M3uPathMode.Full -> entry.fullPath
            }
            require(path.isNotBlank()) { "M3U paths can't be empty" }
            require('\n' !in path && '\r' !in path) { "M3U paths can't contain line breaks" }
            if (trackInfo) {
                val durationSeconds = entry.durationMs?.takeIf { it >= 0 }?.div(1_000) ?: -1
                val details = listOfNotNull(
                    entry.artist?.takeIf { it.isNotBlank() }?.let(::safeLine),
                    entry.title?.takeIf { it.isNotBlank() }?.let(::safeLine),
                ).joinToString(" - ")
                appendLine("#EXTINF:$durationSeconds,$details")
            }
            appendLine(path)
        }
    }

    fun parse(text: String): M3uDocument {
        val entries = ArrayList<M3uEntry>()
        var playlistName: String? = null
        var pendingTitle: String? = null
        var pendingArtist: String? = null
        var pendingDuration: Long? = null

        text.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) ->
                    playlistName = line.substringAfter(':').trim().takeIf(String::isNotEmpty)
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val content = line.substringAfter(':')
                    val comma = content.indexOf(',')
                    val durationText = if (comma >= 0) content.substring(0, comma).trim() else content.trim()
                    pendingDuration = durationText.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it >= 0 }
                        ?.let { (it * 1_000).toLong() }
                    val display = if (comma >= 0) content.substring(comma + 1).trim() else ""
                    val separator = display.indexOf(" - ")
                    if (separator >= 0) {
                        pendingArtist = display.substring(0, separator).trim().takeIf(String::isNotEmpty)
                        pendingTitle = display.substring(separator + 3).trim().takeIf(String::isNotEmpty)
                    } else {
                        pendingArtist = null
                        pendingTitle = display.takeIf(String::isNotEmpty)
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    entries += M3uEntry(line, pendingTitle, pendingArtist, pendingDuration)
                    pendingTitle = null
                    pendingArtist = null
                    pendingDuration = null
                }
            }
        }
        return M3uDocument(playlistName, entries)
    }

    /**
     * Resolves each entry by exact path first, then by title/artist and (when present) duration.
     * [resolvePath] should interpret a relative entry against its source playlist or music folder.
     */
    fun match(
        entries: List<M3uEntry>,
        library: List<TrackEntity>,
        resolvePath: (String) -> String? = { it },
    ): M3uMatchResult {
        val byLocation = library.associateBy { normalizePath(it.location) }
        val matched = ArrayList<M3uMatchedEntry>()
        val notFound = ArrayList<M3uEntry>()

        entries.forEach { entry ->
            val resolved = resolvePath(entry.path)
            val byPath = resolved?.let { byLocation[normalizePath(it)] }
            val track = byPath ?: entry.matchByTags(library)
            if (track == null) notFound += entry else matched += M3uMatchedEntry(entry, track)
        }
        return M3uMatchResult(matched, notFound)
    }

    private fun M3uEntry.matchByTags(library: List<TrackEntity>): TrackEntity? {
        return matchTrackByTags(title, artist, durationMs, library)
    }

    private fun safeLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ').trim()

    private fun normalizePath(path: String): String = path.replace('\\', '/').removeSuffix("/")

}

fun matchTrackByTags(
    title: String?,
    artist: String?,
    durationMs: Long?,
    library: List<TrackEntity>,
): TrackEntity? {
    val wantedTitle = title.normalized() ?: return null
    val wantedArtist = artist.normalized() ?: return null
    return library.firstOrNull { track ->
        track.title.normalized() == wantedTitle &&
            track.artist.normalized() == wantedArtist &&
            (durationMs == null || track.durationMs == null || abs(durationMs - track.durationMs) <= DURATION_TOLERANCE_MS)
    }
}

private fun String?.normalized(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.lowercase()?.split(Regex("\\s+"))?.joinToString(" ")

private const val DURATION_TOLERANCE_MS = 2_000L
