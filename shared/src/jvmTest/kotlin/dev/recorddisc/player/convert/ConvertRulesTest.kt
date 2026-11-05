package dev.recorddisc.player.convert

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Convert to Audio's dialog rules (gap field, names, estimate) and Even out volume's gain maths. */
class ConvertRulesTest {
    @Test
    fun theGapIsWholeSecondsAndNeverBelowZero() {
        assertEquals(0, ConvertRules.parseGap(""))
        assertEquals(0, ConvertRules.parseGap("-4"))
        assertEquals(0, ConvertRules.parseGap("abc"))
        assertEquals(3, ConvertRules.parseGap(" 3 "))
        assertEquals(3, ConvertRules.parseGap("2.6"), "fractions round")
        assertEquals(2, ConvertRules.parseGap("2,4"))
        assertEquals(600, ConvertRules.parseGap("600"), "no upper limit")
    }

    @Test
    fun fileNamesAreSafeAndFollowTheFormat() {
        assertEquals("Road Trip", ConvertRules.fileName("Road Trip"))
        assertEquals("AC_DC_ Best of", ConvertRules.fileName("AC/DC: Best of"))
        assertEquals("Playlist", ConvertRules.fileName("  ..  "))
        assertEquals("Road Trip.m4a", ConvertRules.withExtension("Road Trip.flac", ConvertFormat.M4a), "an audio extension is swapped")
        assertEquals("Live v1.2.opus", ConvertRules.withExtension("Live v1.2", ConvertFormat.Opus), "other dots stay")
    }

    @Test
    fun lengthAndSizeEstimate() {
        assertEquals(2_000 + 3_000 + 2 * 1_000, ConvertRules.totalMs(listOf(2_000, null, 3_000), gapSeconds = 1))
        assertEquals("≈ 312 MB · 48 min", ConvertRules.estimate(ConvertFormat.Flac, 48 * 60_000L))
        assertEquals("≈ 1.3 GB · 2 hr 15 min", ConvertRules.estimate(ConvertFormat.Wav, 135 * 60_000L))
        assertTrue(ConvertRules.bytesNeeded(ConvertFormat.Wav, 60_000) > 10L * 1024 * 1024)
    }

    @Test
    fun progressCoversBothPassesWithEvenOut() {
        assertEquals(0.5f, overallFraction(1, 2, 0f, measuring = false, evenOut = false))
        assertEquals(0.25f, overallFraction(1, 2, 0f, measuring = true, evenOut = true))
        assertEquals(0.75f, overallFraction(1, 2, 0f, measuring = false, evenOut = true))
        assertEquals(1f, overallFraction(1, 2, 1f, measuring = false, evenOut = true))
    }

    @Test
    fun evenOutGainsNeverClip() {
        assertEquals(1f, Loudness.gainFor(0.0, 0.0), "silence stays as it is")
        assertEquals(0.5f, Loudness.gainFor(0.25, 0.9), "loud songs are turned down")
        assertEquals(4f, Loudness.gainFor(0.01, 0.02), "quiet songs are raised at most +12 dB")
        // Raising this one fully would push its peak past the ceiling, so it stops there.
        val g = Loudness.gainFor(0.05, 0.5)
        assertEquals(Loudness.PEAK_CEILING / 0.5, g.toDouble(), 1e-6)
        assertEquals(1f, Loudness.gainFor(0.05, 0.99), "a song already at full scale isn't raised")
    }

    @Test
    fun applyingAGainClampsToSixteenBits() {
        val pcm = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(1000).putShort(30000).putShort(-30000).flip() as ByteBuffer
        val out = Loudness.apply(pcm, 2f).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(2000, Short.MAX_VALUE, Short.MIN_VALUE), List(3) { out.short })
        assertEquals(0, pcm.remaining(), "the input is used up")
    }

    @Test
    fun theMeterReadsRmsAndPeak() {
        val meter = LoudnessMeter()
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { pcm.putShort(if (it % 2 == 0) 4096 else -4096) }
        pcm.flip()
        meter.add(pcm)
        assertEquals(8, pcm.remaining(), "measuring doesn't consume the samples")
        // RMS 0.125 of full scale = the target already: gain 1.
        assertEquals(1f, meter.gain(), 1e-3f)
    }
}
