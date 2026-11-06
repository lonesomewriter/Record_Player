package dev.recorddisc.player.convert

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Convert to Audio → Even out volume (off by default): each song is decoded once to measure how
 * loud it is, then decoded again for the file with a gain that brings it to a common level. The
 * gain never makes the song's loudest sample clip.
 */

/** Measures one song's loudness from 16-bit little-endian PCM (any channel count). */
class LoudnessMeter {
    private var sumSquares = 0.0
    private var count = 0L
    private var peak = 0

    /** Adds [pcm]'s samples; [pcm]'s position isn't moved. */
    fun add(pcm: ByteBuffer) {
        val b = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= 2) {
            val s = b.short.toInt()
            sumSquares += s.toDouble() * s
            if (abs(s) > peak) peak = abs(s)
            count++
        }
    }

    /** The gain that evens this song out (1 when it was silent or nothing was measured). */
    fun gain(): Float {
        if (count == 0L) return 1f
        val full = 32768.0
        return Loudness.gainFor(sqrt(sumSquares / count) / full, peak / full)
    }
}

object Loudness {
    /** The common level: an average (RMS) of about −18 dBFS, near ReplayGain's reference loudness. */
    const val TARGET_RMS = 0.125

    /** Quiet songs are raised by at most this much (+12 dB), so near-silence isn't blown up. */
    const val MAX_BOOST = 4.0

    /** The loudest sample may reach this much of full scale after the gain, never more. */
    const val PEAK_CEILING = 0.98

    /** Gain for a song with this [rms] and [peak] (both 0–1 of full scale). */
    fun gainFor(rms: Double, peak: Double): Float {
        if (rms < 1e-4 || peak <= 0.0) return 1f
        var gain = minOf(TARGET_RMS / rms, MAX_BOOST)
        // Raising a song must not clip it: stop where its peak reaches the ceiling (but a song is
        // never made louder only to be cut back, and a loud song is still turned down).
        if (gain > 1.0 && peak * gain > PEAK_CEILING) gain = maxOf(1.0, PEAK_CEILING / peak)
        return gain.toFloat()
    }

    /** [pcm] (16-bit little-endian) with [gain] applied, clamped to the 16-bit range; [pcm] itself when the gain is 1. */
    fun apply(pcm: ByteBuffer, gain: Float): ByteBuffer {
        if (gain == 1f) return pcm
        val src = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        while (src.remaining() >= 2) {
            out.putShort((src.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        pcm.position(pcm.limit())
        out.flip()
        return out
    }
}
