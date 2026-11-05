package dev.recorddisc.player.convert

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android "To audio file" (prototype): joins songs into one file with Android's own decoders and
 * encoders (MediaCodec), no extra library. Like the desktop GstConverter, each song is decoded on
 * its own to 16-bit stereo at the format's rate (Media3's SonicAudioProcessor resamples when the
 * song's rate differs), and everything, gaps included, goes into one encoder as a single stream;
 * the frame counts give the exact sample where each song starts, for the song marks.
 * Prototype formats: FLAC, M4A and WAV.
 */
class MediaCodecConverter(private val context: Context) {
    private companion object {
        const val TIMEOUT_US = 10_000L
        /** A short pause, used only when a codec has nothing ready. */
        const val WAIT_US = 2_000L
        /** No progress for this long and a codec counts as stuck. */
        const val STALL_NS = 30_000_000_000L
        const val BYTES_PER_FRAME = 4 // 16-bit stereo
        const val GAP_CHUNK_FRAMES = 4_096
    }

    fun convert(request: ConvertRequest, onProgress: (ConvertProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): ConvertResult {
        val rate = request.format.sampleRate
        val output = File(request.output)
        val sink = when (request.format) {
            ConvertFormat.Flac -> CodecSink(output, rate, MediaFormat.MIMETYPE_AUDIO_FLAC, muxed = false)
            ConvertFormat.M4a -> CodecSink(output, rate, MediaFormat.MIMETYPE_AUDIO_AAC, muxed = true)
            ConvertFormat.Wav -> WavSink(output, rate)
            else -> error("${request.format} isn't in the Android prototype")
        }
        val lengths = mutableListOf<Long>()
        val gapFrames = request.gapSeconds.toLong() * rate
        try {
            request.inputs.forEachIndexed { index, input ->
                if (index > 0 && gapFrames > 0) writeSilence(sink, gapFrames, cancelled)
                lengths += decode(input.location, rate, cancelled, { song -> onProgress(ConvertProgress(index, request.inputs.size, (index + song) / request.inputs.size)) }) {
                    sink.write(it)
                }
            }
            sink.finish()
        } catch (failure: Throwable) {
            sink.abort()
            output.delete()
            throw failure
        }
        val starts = songStarts(lengths, gapFrames)
        val marks = request.inputs.zip(starts) { input, start -> SongMark(input.title, start) }
        val total = sink.frames
        when (request.format) {
            ConvertFormat.Flac -> FlacMarks.write(output, total, marks, mapOf("TITLE" to request.title, "ALBUM" to request.title))
            ConvertFormat.M4a -> Mp4Chapters.write(output, rate, marks)
            else -> Unit
        }
        onProgress(ConvertProgress(request.inputs.lastIndex, request.inputs.size, 1f))
        return ConvertResult(total, rate, starts)
    }

    /** Decodes [location] to 16-bit stereo at [rate] and counts the frames: for the self-check. */
    fun decodedFrames(location: String, rate: Int): Long = decode(location, rate, { false }, {}) {}

    private fun writeSilence(sink: PcmSink, frames: Long, cancelled: () -> Boolean) {
        val chunk = ByteBuffer.allocateDirect(GAP_CHUNK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        var left = frames
        while (left > 0) {
            if (cancelled()) throw ConvertCancelled()
            val n = minOf(left, GAP_CHUNK_FRAMES.toLong()).toInt()
            chunk.clear(); repeat(n * BYTES_PER_FRAME) { chunk.put(0) }; chunk.flip()
            sink.write(chunk)
            left -= n
        }
    }

    /**
     * Decodes one song, hands 16-bit stereo PCM at [rate] to [out], and returns its length in
     * frames. [progress] gets 0–1 through the song.
     */
    @OptIn(UnstableApi::class)
    private fun decode(location: String, rate: Int, cancelled: () -> Boolean, progress: (Float) -> Unit, out: (ByteBuffer) -> Unit): Long {
        val name = location.substringAfterLast('/').substringAfterLast("%2F")
        val extractor = MediaExtractor()
        try {
            if (location.startsWith("content:")) extractor.setDataSource(context, Uri.parse(location), null) else extractor.setDataSource(location)
        } catch (e: Exception) {
            extractor.release()
            error("$name: can't open (${e.message})")
        }
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { extractor.release(); error("$name: no audio") }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        var frames = 0L
        var sonic: SonicAudioProcessor? = null
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var configured = false
        fun emit(stereo16: ByteBuffer) {
            val s = sonic
            if (s == null) {
                frames += stereo16.remaining() / BYTES_PER_FRAME
                out(stereo16)
            } else {
                s.queueInput(stereo16)
                drain(s) { frames += it.remaining() / BYTES_PER_FRAME; out(it) }
            }
        }
        fun setUp(format: MediaFormat) {
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            val songRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sonic = if (songRate == rate) null else SonicAudioProcessor().apply {
                setOutputSampleRateHz(rate)
                configure(AudioProcessor.AudioFormat(songRate, 2, C.ENCODING_PCM_16BIT))
                flush(AudioProcessor.StreamMetadata.DEFAULT)
            }
            configured = true
        }
        try {
            decoder.configure(inFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            // Feed every free input and take every ready output without waiting; only pause briefly
            // when the decoder has nothing ready. (Waiting on each call capped speed near real time.)
            val expectedFrames = if (durationUs > 0) durationUs * rate / 1_000_000 else 0L
            var lastProgress = System.nanoTime()
            var done = false
            while (!done) {
                if (cancelled()) throw ConvertCancelled()
                var progressed = false
                while (!inputDone) {
                    val i = decoder.dequeueInputBuffer(0)
                    if (i < 0) break
                    progressed = true
                    val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                while (true) {
                    val o = decoder.dequeueOutputBuffer(info, if (progressed) 0 else WAIT_US)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { setUp(decoder.outputFormat); continue }
                    if (o < 0) continue
                    if (!configured) setUp(decoder.outputFormat)
                    if (info.size > 0) {
                        val pcm = decoder.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        emit(toStereo16(pcm.order(ByteOrder.LITTLE_ENDIAN), channels, floatPcm))
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { done = true; break }
                }
                if (expectedFrames > 0) progress((frames.toFloat() / expectedFrames).coerceIn(0f, 1f))
                val now = System.nanoTime()
                if (progressed) lastProgress = now
                check(now - lastProgress < STALL_NS) { "the decoder stopped responding" }
            }
            sonic?.let { s ->
                s.queueEndOfStream()
                drain(s) { frames += it.remaining() / BYTES_PER_FRAME; out(it) }
            }
        } catch (e: ConvertCancelled) {
            throw e
        } catch (e: Exception) {
            error("$name: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
            sonic?.reset()
        }
        return frames
    }

    @OptIn(UnstableApi::class)
    private fun drain(sonic: SonicAudioProcessor, out: (ByteBuffer) -> Unit) {
        while (true) {
            val chunk = sonic.output
            if (!chunk.hasRemaining()) return
            out(chunk)
        }
    }

    /** Any channel count, 16-bit or float, to 16-bit stereo: mono is doubled, more than two keep left and right. */
    private fun toStereo16(pcm: ByteBuffer, channels: Int, float: Boolean): ByteBuffer {
        val bytesPerSample = if (float) 4 else 2
        val frameCount = pcm.remaining() / (bytesPerSample * channels)
        val out = ByteBuffer.allocateDirect(frameCount * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        fun sample(index: Int): Short {
            val at = pcm.position() + index * bytesPerSample
            return if (float) (pcm.getFloat(at).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() else pcm.getShort(at)
        }
        for (f in 0 until frameCount) {
            val left = sample(f * channels)
            val right = if (channels == 1) left else sample(f * channels + 1)
            out.putShort(left); out.putShort(right)
        }
        out.flip()
        return out
    }

    private interface PcmSink {
        val frames: Long
        fun write(pcm: ByteBuffer)
        fun finish()
        fun abort()
    }

    /** WAV: a 44-byte header (sizes filled in at the end) and the samples. */
    private class WavSink(private val file: File, private val rate: Int) : PcmSink {
        private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        private val scratch = ByteArray(1 shl 16)
        override var frames = 0L
            private set

        override fun write(pcm: ByteBuffer) {
            while (pcm.hasRemaining()) {
                val n = minOf(pcm.remaining(), scratch.size)
                pcm.get(scratch, 0, n)
                raf.write(scratch, 0, n)
                frames += n / BYTES_PER_FRAME
            }
        }

        override fun finish() {
            val data = frames * BYTES_PER_FRAME
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt((36 + data).toInt()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * BYTES_PER_FRAME); putShort(4); putShort(16)
                put("data".toByteArray()); putInt(data.toInt())
            }
            raf.seek(0); raf.write(header.array()); raf.close()
        }

        override fun abort() = runCatching { raf.close() }.let { }
    }

    /** FLAC (written straight to the file) or AAC (in an MP4 through MediaMuxer). */
    private class CodecSink(private val file: File, private val rate: Int, mime: String, private val muxed: Boolean) : PcmSink {
        private val encoder: MediaCodec
        private val stream: FileOutputStream? = if (muxed) null else FileOutputStream(file)
        private val muxer: MediaMuxer? = if (muxed) MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) else null
        private var track = -1
        private var wroteMagic = false
        private val info = MediaCodec.BufferInfo()
        private val scratch = ByteArray(1 shl 16)
        override var frames = 0L
            private set

        init {
            val format = MediaFormat.createAudioFormat(mime, rate, 2).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                if (muxed) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                } else {
                    setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                }
            }
            encoder = MediaCodec.createEncoderByType(mime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        }

        override fun write(pcm: ByteBuffer) {
            var lastProgress = System.nanoTime()
            while (pcm.hasRemaining()) {
                var i = encoder.dequeueInputBuffer(0)
                if (i < 0) {
                    // No free input: move finished output along, then wait briefly.
                    drainOutput(endOfStream = false)
                    i = encoder.dequeueInputBuffer(WAIT_US)
                }
                if (i >= 0) lastProgress = System.nanoTime()
                check(System.nanoTime() - lastProgress < STALL_NS) { "the encoder stopped taking audio" }
                if (i >= 0) {
                    val buffer = encoder.getInputBuffer(i)!!
                    buffer.clear()
                    val n = minOf(pcm.remaining(), buffer.capacity()) / BYTES_PER_FRAME * BYTES_PER_FRAME
                    check(n > 0) { "the encoder's input buffer is too small (${buffer.capacity()} bytes)" }
                    val slice = pcm.duplicate().apply { limit(position() + n) }
                    buffer.put(slice)
                    pcm.position(pcm.position() + n)
                    encoder.queueInputBuffer(i, 0, n, frames * 1_000_000L / rate, 0)
                    frames += n / BYTES_PER_FRAME
                }
                drainOutput(endOfStream = false)
            }
        }

        override fun finish() {
            val began = System.nanoTime()
            while (true) {
                check(System.nanoTime() - began < STALL_NS) { "the encoder wouldn't take the end of the audio" }
                val i = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    encoder.queueInputBuffer(i, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drainOutput(endOfStream = false)
            }
            drainOutput(endOfStream = true)
            close()
        }

        override fun abort() = runCatching { close() }.let { }

        private fun close() {
            runCatching { encoder.stop() }
            encoder.release()
            stream?.close()
            if (track >= 0) runCatching { muxer?.stop() }
            muxer?.release()
        }

        /** Moves encoded output to the file; with [endOfStream], waits for the last of it. */
        private fun drainOutput(endOfStream: Boolean) {
            var lastProgress = System.nanoTime()
            while (true) {
                // Mid-stream, take only what's ready; at the end, wait for the last of it.
                val o = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                if (o != MediaCodec.INFO_TRY_AGAIN_LATER) lastProgress = System.nanoTime()
                check(System.nanoTime() - lastProgress < STALL_NS) { "the encoder never finished" }
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer?.let { track = it.addTrack(encoder.outputFormat); it.start() }
                    o >= 0 -> {
                        val data = encoder.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        when {
                            muxer != null -> if (!config && info.size > 0) muxer.writeSampleData(track, data, info)
                            info.size > 0 -> writeFlac(data, config)
                        }
                        encoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        /** The FLAC header comes first as codec config; make sure the file starts with "fLaC". */
        private fun writeFlac(data: ByteBuffer, config: Boolean) {
            val out = stream!!
            if (!wroteMagic) {
                val startsWithMagic = data.remaining() >= 4 && data.get(data.position()) == 'f'.code.toByte() &&
                    data.get(data.position() + 1) == 'L'.code.toByte() && data.get(data.position() + 2) == 'a'.code.toByte() && data.get(data.position() + 3) == 'C'.code.toByte()
                check(config || startsWithMagic) { "FLAC encoder sent audio before its header" }
                if (!startsWithMagic) out.write("fLaC".toByteArray())
                wroteMagic = true
            }
            while (data.hasRemaining()) {
                val n = minOf(data.remaining(), scratch.size)
                data.get(scratch, 0, n)
                out.write(scratch, 0, n)
            }
        }
    }
}
