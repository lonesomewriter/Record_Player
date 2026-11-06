package dev.recorddisc.player.ui

import dev.recorddisc.player.playback.SpectrumAnalyzer
import kotlin.math.exp
import kotlin.math.max

/**
 * What the record's shine shows, frame by frame (2026-11-05 mockups): [beat] (0..1) flares on each
 * beat of the music and fades, and [levels] (0..1) is how far each spectrum line reaches, one per
 * band from bass to treble. Fed with the player's spectrum frames; plain Kotlin, so it is unit-tested.
 */
class ShineVisualizer(val lines: Int = SpectrumAnalyzer.BANDS) {
    var beat = 0f
        private set
    val levels = FloatArray(lines)

    private var bassAverage = 0f
    private var sinceBeatMs = 10_000f

    companion object {
        /** A beat's flare fades with this time constant (the mockup's 170 ms). */
        const val BEAT_FADE_MS = 170f
        /** Beats closer together than this are one beat (240 BPM at most). */
        const val MIN_BEAT_GAP_MS = 250f
        /** The bass must rise this far above its recent average to count as a beat. */
        const val BEAT_RISE = 1.25f
        /** ...and be at least this loud. */
        const val BEAT_FLOOR = 0.2f
        private const val AVERAGE_MS = 900f
    }

    /**
     * Advances by [dtMs]. [bands] is the newest spectrum frame (null when nothing is playing or the
     * visualizer is off), [quiet] stops everything (Battery Saver, reduced motion).
     */
    fun update(dtMs: Float, bands: FloatArray?, quiet: Boolean) {
        sinceBeatMs += dtMs
        beat *= exp(-dtMs / BEAT_FADE_MS)
        if (beat < 0.002f) beat = 0f
        if (quiet || bands == null) {
            for (i in levels.indices) levels[i] = decay(levels[i], i, dtMs)
            if (quiet) { beat = 0f; levels.fill(0f) }
            return
        }
        // Beat: the bass (lowest quarter of the bands) jumping above its recent average.
        val bassCount = max(1, bands.size / 4)
        var bass = 0f
        for (i in 0 until bassCount) bass += bands[i]
        bass /= bassCount
        val k = 1f - exp(-dtMs / AVERAGE_MS)
        if (bass > BEAT_FLOOR && bass > bassAverage * BEAT_RISE && sinceBeatMs >= MIN_BEAT_GAP_MS) {
            beat = max(beat, (0.5f + (bass - bassAverage) * 2.5f).coerceIn(0.5f, 1f))
            sinceBeatMs = 0f
        }
        bassAverage += (bass - bassAverage) * k
        // Lines: jump straight up to the band's level, fall back slowly (treble faster to fall less far).
        for (i in levels.indices) {
            val target = bands.getOrElse(i * bands.size / lines) { 0f }
            levels[i] = if (target > levels[i]) target else decay(levels[i], i, dtMs)
        }
    }

    private fun decay(level: Float, line: Int, dtMs: Float): Float {
        val band = line.toFloat() / max(1, lines - 1)
        val next = level * exp(-dtMs / (140f + band * 120f))
        return if (next < 0.01f) 0f else next
    }
}
