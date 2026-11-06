package dev.recorddisc.player.playback

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/*
 * The record's shine visualizer (2026-11-05) listens to the music being played: each platform
 * copies the samples it is already playing into a SpectrumAnalyzer (no microphone, no permission),
 * which turns them into loudness per frequency band. Shared by desktop and Android.
 */

/**
 * Turns mono samples (-1..1) into [BANDS] loudness levels (0..1, low to high), about [FRAMES_PER_SECOND]
 * times a second: a 2048-point FFT over the latest samples, grouped into log-spaced bands from
 * 40 Hz to 16 kHz, mapped from -70 dB (0) to -10 dB (1).
 */
class SpectrumAnalyzer(private val sampleRate: Int, private val onFrame: (FloatArray) -> Unit) {
    companion object {
        const val BANDS = 22
        const val FRAMES_PER_SECOND = 30
        private const val SIZE = 2048
        private const val LOW_HZ = 40.0
        private const val HIGH_HZ = 16_000.0
        private const val FLOOR_DB = -70.0
        private const val RANGE_DB = 60.0
    }

    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }
    private val history = FloatArray(SIZE)
    private var filled = 0
    private var sinceFrame = 0
    private val hop = max(1, sampleRate / FRAMES_PER_SECOND)
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    // FFT bin ranges for each band.
    private val bandBins: List<IntRange> = run {
        val binHz = sampleRate.toDouble() / SIZE
        val nyquist = SIZE / 2 - 1
        val ratio = (min(HIGH_HZ, sampleRate / 2.0) / LOW_HZ).pow(1.0 / BANDS)
        (0 until BANDS).map { b ->
            val from = (LOW_HZ * ratio.pow(b) / binHz).toInt().coerceIn(1, nyquist)
            val to = (LOW_HZ * ratio.pow(b + 1) / binHz).toInt().coerceIn(from, nyquist)
            from..to
        }
    }

    /** Adds [count] samples from [samples]; reports a frame each time enough new audio has arrived. */
    fun feed(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            history[filled % SIZE] = samples[i]
            filled++
            if (++sinceFrame >= hop && filled >= SIZE) {
                sinceFrame = 0
                onFrame(analyse())
            }
        }
    }

    /** Forgets earlier audio (after a seek or a new song). */
    fun reset() {
        filled = 0
        sinceFrame = 0
    }

    private fun analyse(): FloatArray {
        val start = filled % SIZE // oldest sample in the ring
        for (i in 0 until SIZE) {
            re[i] = history[(start + i) % SIZE] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val norm = 2f / (SIZE * 0.5f) // Hann window's coherent gain is 0.5
        return FloatArray(BANDS) { b ->
            var peak = 0f
            for (k in bandBins[b]) peak = max(peak, sqrt(re[k] * re[k] + im[k] * im[k]) * norm)
            val db = 20 * log10(max(peak.toDouble(), 1e-9))
            ((db - FLOOR_DB) / RANGE_DB).toFloat().coerceIn(0f, 1f)
        }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * The latest spectrum frames from the player, for the visualizer. Analysis only runs while
 * something on screen [acquire]s it (and not with Battery Saver), so a hidden app does no extra
 * work. [delayMs] lines frames up with what is heard where the samples are copied before the
 * audio output's own buffer (Android). Lock-free: the producer replaces an immutable list.
 */
class SpectrumHub(private val delayMs: Long = 0) {
    private class Frame(val time: TimeMark, val bands: FloatArray)

    @Volatile
    private var frames: List<Frame> = emptyList()

    @Volatile
    private var users = 0

    /** True while the visualizer is showing; producers skip the analysis otherwise. */
    val enabled: Boolean get() = users > 0

    /** From the UI (one thread). */
    fun acquire() { users++ }

    /** From the UI (one thread). */
    fun release() {
        users = max(0, users - 1)
        if (users == 0) frames = emptyList()
    }

    /** From the producer's thread. */
    fun publish(bands: FloatArray) {
        frames = (frames + Frame(TimeSource.Monotonic.markNow(), bands)).takeLast(64)
    }

    /** The newest frame that is at least [delayMs] old (and at most half a second), or null. */
    fun current(): FloatArray? {
        var pick: FloatArray? = null
        for (f in frames) {
            val age = f.time.elapsedNow().inWholeMilliseconds
            if (age >= delayMs && age <= delayMs + 500) pick = f.bands
        }
        return pick
    }

    /** Drops queued frames (pause, seek, new song) so the shine doesn't react to old audio. */
    fun clear() { frames = emptyList() }
}
