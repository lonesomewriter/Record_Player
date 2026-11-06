package dev.recorddisc.player.convert

import kotlinx.coroutines.async
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Convert to Audio on desktop: three songs at different rates and layouts (44.1 kHz
 * 24-bit stereo FLAC, 48 kHz Opus, 44.1 kHz mono AAC) joined with a 1-second gap.
 * Skipped if GStreamer is missing.
 */
class GstConverterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File =
        File(tmp.root, name).also { out -> javaClass.getResourceAsStream("/audio/$name")!!.use { it.copyTo(out.outputStream()) } }

    private fun converter(): GstConverter {
        val c = runCatching { GstConverter() }.getOrNull()
        assumeTrue("GStreamer is not installed", c != null)
        return c!!
    }

    private val songs by lazy {
        listOf(
            ConvertInput(fixture("tone.flac").path, "Midnight Drive"),
            ConvertInput(fixture("tone.opus").path, "Paper Lanterns"),
            ConvertInput(fixture("tone.m4a").path, "Static Bloom — ünïcode"),
        )
    }

    private fun request(format: ConvertFormat, gap: Int = 1) =
        ConvertRequest(songs, format, gap, File(tmp.root, "Road Trip.${format.extension}").path, "Road Trip")

    /** Decodes [file] back to plain 16-bit stereo with the converter itself; its length in frames. */
    private fun decodedFrames(file: File, rate: Int): Long {
        val wav = File(tmp.root, "check-${file.name}.wav")
        converter().convert(ConvertRequest(listOf(ConvertInput(file.path, "x")), ConvertFormat.Wav, 0, wav.path, "x"))
        assertEquals(44_100, rate, "the WAV check runs at CD rate")
        return (wav.length() - 44) / 4 // 44-byte WAV header, 4 bytes per stereo 16-bit frame
    }

    @Test
    fun joinsSongsIntoOneFlacWithACueSheetAndTags() {
        val progress = mutableListOf<Float>()
        val result = converter().convert(request(ConvertFormat.Flac), onProgress = { progress += it.fraction })
        val out = File(request(ConvertFormat.Flac).output)

        assertEquals(44_100, result.sampleRate)
        // Each ~2 s song plus a 1 s gap between them: about 8 s in all.
        assertTrue(abs(result.totalSamples - 8 * 44_100L) < 44_100 / 10, "total ${result.totalSamples}")
        assertEquals(result.totalSamples, decodedFrames(out, 44_100), "FLAC is lossless: every sample comes back")
        assertEquals(result.songStarts, FlacMarks.readTrackOffsets(out), "cue sheet marks each song's first sample")
        assertEquals(0L, result.songStarts[0])
        assertTrue(FlacMarks.readTags(out).containsAll(listOf("TITLE=Road Trip", "ALBUM=Road Trip")))
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a } && progress.last() == 1f, "progress only moves forward to 100%")
    }

    @Test
    fun joinsSongsIntoOneM4aWithChapters() {
        assumeTrue("GStreamer's libav plugin (AAC) isn't installed", runCatching { converter().convert(request(ConvertFormat.M4a)) }.isSuccess)
        val result = converter().convert(request(ConvertFormat.M4a))
        val out = File(request(ConvertFormat.M4a).output)

        // AAC adds a short encoder lead-in (about 1024 samples); players skip it.
        val back = decodedFrames(out, 44_100)
        assertTrue(back - result.totalSamples in 0..2_048, "decoded $back vs written ${result.totalSamples}")
        val chapters = Mp4Chapters.read(out)
        assertEquals(songs.map { it.title }, chapters.map { it.second })
        chapters.zip(result.songStarts).forEach { (chapter, start) -> assertEquals(start * 1000 / 44_100, chapter.first) }
    }

    @Test
    fun everyFormatGStreamerHasProducesAPlayableFile() {
        for (format in ConvertFormat.entries) {
            val out = File(request(format).output)
            val made = runCatching { converter().convert(request(format, gap = 0)) }
            if (made.isFailure && format == ConvertFormat.M4a) continue // AAC needs gstreamer1.0-libav
            made.getOrThrow()
            assertTrue(out.length() > 10_000, "$format written")
            assertTrue(decodedFrames(out, 44_100) > 5 * 44_100, "$format decodes back to ~6 s")
        }
    }

    @Test
    fun cancellingStopsAndDeletesThePartialFile() {
        var calls = 0
        assertFailsWith<ConvertCancelled> {
            converter().convert(request(ConvertFormat.Flac), onProgress = { calls++ }, cancelled = { calls > 3 })
        }
        assertFalse(File(request(ConvertFormat.Flac).output).exists())
    }

    @Test
    fun aMissingSongFailsWithItsNameAndLeavesNoFile() {
        val bad = request(ConvertFormat.Flac).copy(inputs = songs + ConvertInput(File(tmp.root, "gone.flac").path, "Gone"))
        val failure = assertFailsWith<IllegalStateException> { converter().convert(bad) }
        assertTrue("gone.flac" in failure.message.orEmpty(), failure.message)
        assertFalse(File(bad.output).exists())
    }

    @Test
    fun oggAndOpusCarryChapterTagsAndStillPlayInFull() {
        for (format in listOf(ConvertFormat.Ogg, ConvertFormat.Opus)) {
            val result = converter().convert(request(format))
            val out = File(request(format).output)
            val comments = OggChapters.readComments(out)
            assertTrue(comments.containsAll(listOf("TITLE=Road Trip", "ALBUM=Road Trip")), "$format tags: $comments")
            songs.forEachIndexed { i, song ->
                val n = (i + 1).toString().padStart(3, '0')
                assertTrue("CHAPTER$n=${OggChapters.timestamp(result.songStarts[i], format.sampleRate)}" in comments, "$format chapter $n: $comments")
                assertTrue("CHAPTER${n}NAME=${song.title}" in comments, "$format chapter $n name")
            }
            assertEquals("00:00:00.000", OggChapters.timestamp(result.songStarts[0], format.sampleRate))
            // Rebuilt header pages and renumbered audio pages: a damaged page would be dropped on playback.
            val expected = result.totalSamples * 44_100 / format.sampleRate
            val back = decodedFrames(out, 44_100)
            assertTrue(abs(back - expected) < 44_100 / 20, "$format decodes back to $back frames, expected about $expected")
        }
    }

    /** A 1-second 440 Hz stereo WAV at [amplitude] (0–1 of full scale). */
    private fun toneWav(name: String, amplitude: Double): File {
        val rate = 44_100
        val pcm = java.nio.ByteBuffer.allocate(rate * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(rate) { i ->
            val v = (kotlin.math.sin(2 * Math.PI * 440 * i / rate) * amplitude * Short.MAX_VALUE).toInt().toShort()
            pcm.putShort(v).putShort(v)
        }
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + rate * 4); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(rate * 4)
        }
        return File(tmp.root, name).apply { writeBytes(header.array() + pcm.array()) }
    }

    @Test
    fun evenOutVolumeBringsQuietAndLoudSongsToOneLevel() {
        val quiet = ConvertInput(toneWav("quiet.wav", 0.05).path, "Quiet")
        val loud = ConvertInput(toneWav("loud.wav", 0.6).path, "Loud")
        val out = File(tmp.root, "even.wav")
        val steps = mutableListOf<ConvertProgress>()
        converter().convert(ConvertRequest(listOf(quiet, loud), ConvertFormat.Wav, 0, out.path, "Even", evenOutVolume = true), onProgress = { steps += it })
        val samples = java.nio.ByteBuffer.wrap(out.readBytes(), 44, out.length().toInt() - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        fun rms(fromFrame: Int, frames: Int): Double {
            var sum = 0.0
            for (f in fromFrame until fromFrame + frames) { val v = samples.get(f * 2) / 32768.0; sum += v * v }
            return kotlin.math.sqrt(sum / frames)
        }
        // Skip the edges of each song (resampler and fade-free joins); measure the middle half.
        val quietRms = rms(11_025, 22_050)
        val loudRms = rms(44_100 + 11_025, 22_050)
        assertTrue(abs(quietRms - Loudness.TARGET_RMS) < 0.0125, "quiet song raised to the target: $quietRms")
        assertTrue(abs(loudRms - Loudness.TARGET_RMS) < 0.0125, "loud song lowered to the target: $loudRms")
        assertTrue(steps.first().measuring && !steps.last().measuring, "measuring pass first")
        assertTrue(steps.filter { it.measuring }.all { it.fraction <= 0.5f }, "measuring is the first half")
        assertTrue(steps.map { it.fraction }.zipWithNext().all { (a, b) -> b >= a } && steps.last().fraction == 1f)
    }

    @Test
    fun desktopSaveGoesThroughAPartFileAndCancelLeavesNothing() = kotlinx.coroutines.runBlocking {
        val target = File(tmp.root, "out/Road Trip.flac")
        fun job(name: String) = ConvertJob(songs, ConvertFormat.Flac, 1, false, "Road Trip", name, File(tmp.root, "out/$name").path, bytesNeeded = 1)
        val saved = DesktopConvert.convert(job("Road Trip.flac")) {}
        assertEquals(target.absolutePath, saved.location)
        assertTrue(target.length() > 10_000)
        assertEquals(listOf("Road Trip.flac"), target.parentFile.list()!!.toList(), "no .part left behind")

        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = async(kotlinx.coroutines.Dispatchers.Default) { DesktopConvert.convert(job("Cancelled.flac")) { started.complete(Unit) } }
        started.await()
        cancelled.cancel()
        runCatching { cancelled.await() }
        assertEquals(listOf("Road Trip.flac"), target.parentFile.list()!!.toList(), "a cancelled run leaves no file")

        val tooBig = runCatching { DesktopConvert.convert(job("Huge.flac").copy(bytesNeeded = Long.MAX_VALUE)) {} }
        assertTrue(tooBig.exceptionOrNull()?.message.orEmpty().startsWith("Not enough free space"), tooBig.toString())
    }

    @Test
    fun aTakenNameGetsANumber() {
        File(tmp.root, "Mix.flac").writeText("x")
        File(tmp.root, "Mix (2).flac").writeText("x")
        assertEquals("Mix (3).flac", DesktopConvert.uniqueFile(tmp.root, "Mix.flac").name)
        assertEquals("New.flac", DesktopConvert.uniqueFile(tmp.root, "New.flac").name)
    }

    @Test
    fun songStartsAddLengthsAndGaps() {
        assertEquals(listOf(0L, 150L, 400L), songStarts(listOf(100L, 200L, 300L), gapSamples = 50))
        assertEquals(listOf(0L, 100L), songStarts(listOf(100L, 200L), gapSamples = 0))
    }
}
