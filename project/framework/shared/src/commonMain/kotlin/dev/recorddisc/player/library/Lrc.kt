package dev.recorddisc.player.library

/** One line of lyrics; [timeMs] is null for a line without a timestamp. */
data class LyricLine(val timeMs: Long?, val text: String)

/**
 * A song's lyrics. [tags] are the file's header lines such as `[ar:Neon Coast]`, kept as they
 * were when the lyrics are saved again. [embedded] is true when they came from inside the song
 * file rather than an `.lrc` next to it.
 */
data class Lyrics(val lines: List<LyricLine>, val tags: List<String> = emptyList(), val embedded: Boolean = false) {
    /** True when at least one line has a timestamp, so lines can follow playback. */
    val synced: Boolean get() = lines.any { it.timeMs != null }
}

/** Reading and writing `.lrc` lyrics files. */
object Lrc {
    private val timestamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val tag = Regex("""^\[([A-Za-z#]+):(.*)]\s*$""")
    private val wordTiming = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** Text from a file's bytes: UTF-8 (a byte-order mark is skipped), or Windows-1252 for older files that aren't valid UTF-8. */
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            body.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            buildString(body.size) { body.forEach { append(windows1252(it.toInt() and 0xFF)) } }
        }
    }

    /**
     * Parses lyrics. Understood: `[mm:ss]`, `[mm:ss.xx]` and `[mm:ss.xxx]` timestamps, several
     * timestamps on one line, `[offset:±ms]` (applied to the times), and word-level `<mm:ss.xx>`
     * timing (removed). Lines without a timestamp are kept as untimed lines.
     */
    fun parse(text: String, embedded: Boolean = false): Lyrics {
        val lines = ArrayList<LyricLine>()
        val tags = ArrayList<String>()
        var offsetMs = 0L
        for (raw in text.split('\n')) {
            var line = raw.trimEnd('\r').trim()
            val times = ArrayList<Long>()
            while (true) {
                val m = timestamp.find(line) ?: break
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.toLong()
                }
                times += min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs
                line = line.substring(m.range.last + 1)
            }
            if (times.isNotEmpty()) {
                val words = wordTiming.replace(line, "").replace(Regex("\\s+"), " ").trim()
                times.forEach { lines += LyricLine(it, words) }
                continue
            }
            val t = tag.find(line)
            if (t != null) {
                if (t.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = t.groupValues[2].trim().toLongOrNull() ?: 0L
                } else {
                    tags += line
                }
                continue
            }
            if (line.isNotEmpty()) lines += LyricLine(null, wordTiming.replace(line, "").trim())
        }
        // A positive offset makes lyrics appear earlier.
        val shifted = if (offsetMs == 0L) lines else lines.map { l -> l.copy(timeMs = l.timeMs?.let { maxOf(0L, it - offsetMs) }) }
        return Lyrics(sortForSave(shifted), tags, embedded)
    }

    /** Writes lyrics as `.lrc` text: header tags, then `[mm:ss.xx]` lines; untimed lines as plain text in their place. */
    fun format(lyrics: Lyrics): String = buildString {
        lyrics.tags.forEach { append(it).append('\n') }
        lyrics.lines.forEach { l ->
            if (l.timeMs != null) append('[').append(formatTime(l.timeMs)).append(']')
            append(l.text).append('\n')
        }
    }

    /**
     * Orders lines by time. A line without a timestamp keeps its place by borrowing the time of
     * the line before it (or 0 at the start); lines with equal times keep their order.
     */
    fun sortForSave(lines: List<LyricLine>): List<LyricLine> {
        var last = 0L
        return lines.mapIndexed { i, l ->
            if (l.timeMs != null) last = l.timeMs
            Triple(last, i, l)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    /** The index of the line playing at [positionMs], or -1 before the first timed line. */
    fun activeLine(lines: List<LyricLine>, positionMs: Long): Int {
        var active = -1
        lines.forEachIndexed { i, l -> if (l.timeMs != null && l.timeMs <= positionMs) active = i }
        return active
    }

    /** `mm:ss.xx` (hundredths), or `--:--.--` for no time. */
    fun formatTime(ms: Long?): String {
        if (ms == null) return "--:--.--"
        val cs = ms / 10
        val min = cs / 6000
        val sec = (cs / 100) % 60
        val hundredths = cs % 100
        return "${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.${hundredths.toString().padStart(2, '0')}"
    }

    /** Windows-1252: like Latin-1, except 0x80–0x9F hold typographic characters. */
    private fun windows1252(b: Int): Char =
        if (b in 0x80..0x9F) CP1252_HIGH[b - 0x80] else b.toChar()

    private const val CP1252_HIGH =
        "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F" +
            "\u0090‘’“”•–—˜™š›œ\u009DžŸ"
}
