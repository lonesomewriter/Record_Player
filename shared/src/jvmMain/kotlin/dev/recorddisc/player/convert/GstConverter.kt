package dev.recorddisc.player.convert

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Desktop Convert to Audio: joins songs into one file with the system GStreamer,
 * called through Java's FFM API like [dev.recorddisc.player.playback.GstPlayer] (no binding
 * library). Each song is decoded on its own to 16-bit stereo at the format's rate and pulled out
 * with appsink; the samples (plus silence for gaps) are pushed into one encoding pipeline with
 * appsrc. One continuous stream keeps encoders happy (GStreamer's concat breaks flacenc) and
 * gives the exact sample where each song starts, for the song marks.
 */
class GstConverter {
    private companion object {
        val linker: Linker = Linker.nativeLinker()
        val gst: SymbolLookup = SymbolLookup.libraryLookup("libgstreamer-1.0.so.0", Arena.global())
        val app: SymbolLookup = SymbolLookup.libraryLookup("libgstapp-1.0.so.0", Arena.global())
        val gobject: SymbolLookup = SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global())
        val glib: SymbolLookup = SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global())

        fun fn(lib: SymbolLookup, name: String, desc: FunctionDescriptor, vararg opts: Linker.Option): MethodHandle =
            linker.downcallHandle(lib.find(name).orElseThrow { UnsatisfiedLinkError(name) }, desc, *opts)

        val gstInit = fn(gst, "gst_init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
        val parseLaunch = fn(gst, "gst_parse_launch", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val binGetByName = fn(gst, "gst_bin_get_by_name", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val setState = fn(gst, "gst_element_set_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val getBus = fn(gst, "gst_element_get_bus", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val busPop = fn(gst, "gst_bus_timed_pop_filtered", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT))
        val parseError = fn(gst, "gst_message_parse_error", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS))
        val miniObjectUnref = fn(gst, "gst_mini_object_unref", FunctionDescriptor.ofVoid(ADDRESS))
        val objectUnref = fn(gst, "gst_object_unref", FunctionDescriptor.ofVoid(ADDRESS))
        val filenameToUri = fn(gst, "gst_filename_to_uri", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val queryPosition = fn(gst, "gst_element_query_position", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
        val queryDuration = fn(gst, "gst_element_query_duration", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
        val sampleGetBuffer = fn(gst, "gst_sample_get_buffer", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val bufferGetSize = fn(gst, "gst_buffer_get_size", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
        val bufferCopyRegion = fn(gst, "gst_buffer_copy_region", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG))
        val bufferNewAllocate = fn(gst, "gst_buffer_new_allocate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
        val bufferMemset = fn(gst, "gst_buffer_memset", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG))
        val bufferExtract = fn(gst, "gst_buffer_extract", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG))
        val factoryFind = fn(gst, "gst_element_factory_find", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val trySample = fn(app, "gst_app_sink_try_pull_sample", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG))
        val sinkIsEos = fn(app, "gst_app_sink_is_eos", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val pushBuffer = fn(app, "gst_app_src_push_buffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val endOfStream = fn(app, "gst_app_src_end_of_stream", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val gFree = fn(glib, "g_free", FunctionDescriptor.ofVoid(ADDRESS))
        val gErrorFree = fn(glib, "g_error_free", FunctionDescriptor.ofVoid(ADDRESS))
        // g_object_set is variadic: (object, name, value, NULL)
        val setStringProp = fn(gobject, "g_object_set", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS), Linker.Option.firstVariadicArg(2))

        const val STATE_NULL = 1
        const val STATE_PLAYING = 4
        const val FORMAT_TIME = 3
        const val MSG_EOS = 1
        const val MSG_ERROR = 2
        const val COPY_ALL = 0x1 or 0x2 or 0x4 or 0x8 // flags, timestamps, meta, memory (shared)
        const val FLOW_OK = 0
        const val BYTES_PER_FRAME = 4 // 16-bit stereo

        // GstBuffer { GstMiniObject (64 bytes); GstBufferPool *pool; pts; dts; duration; ... }
        const val PTS = 72L
        const val DTS = 80L
        const val DURATION = 88L

        init {
            gstInit.invoke(MemorySegment.NULL, MemorySegment.NULL)
        }

        /** The encoding half of the pipeline for each format (after appsrc, before filesink). */
        fun encoder(format: ConvertFormat): String = when (format) {
            ConvertFormat.Flac -> "flacenc"
            ConvertFormat.Wav -> "wavenc"
            ConvertFormat.Mp3 -> "lamemp3enc target=bitrate cbr=true bitrate=320"
            ConvertFormat.M4a -> "avenc_aac bitrate=256000 ! mp4mux"
            ConvertFormat.Ogg -> "vorbisenc quality=0.6 ! oggmux"
            ConvertFormat.Opus -> "opusenc bitrate=160000 ! oggmux"
        }

        /** The GStreamer elements [format] needs (all must be installed for it to be offered). */
        fun elementsFor(format: ConvertFormat): List<String> =
            listOf("appsrc", "appsink", "uridecodebin", "audioconvert", "audioresample", "volume", "filesink") +
                encoder(format).split("!").map { it.trim().substringBefore(' ') }
    }

    /** Whether this machine's GStreamer has everything [format] needs (M4A needs gstreamer1.0-libav). */
    fun supports(format: ConvertFormat): Boolean = Arena.ofConfined().use { a ->
        elementsFor(format).all { name ->
            val factory = factoryFind.invoke(a.allocateFrom(name)) as MemorySegment
            (factory != MemorySegment.NULL).also { if (it) objectUnref.invoke(factory) }
        }
    }

    /**
     * Converts [request] (inputs and output are file paths). Reports [onProgress] as it goes and
     * stops with [ConvertCancelled] (deleting the partial file) as soon as [cancelled] says so.
     */
    fun convert(request: ConvertRequest, onProgress: (ConvertProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): ConvertResult {
        val rate = request.format.sampleRate
        val caps = "audio/x-raw,format=S16LE,layout=interleaved,rate=$rate,channels=2"
        val output = File(request.output)
        val lengths = mutableListOf<Long>()
        val gapFrames = request.gapSeconds.toLong() * rate
        val count = request.inputs.size
        // Even out volume: a first pass measures each song; the second applies its gain.
        val gains = if (!request.evenOutVolume) List(count) { 1f } else request.inputs.mapIndexed { index, input ->
            val meter = LoudnessMeter()
            Arena.ofConfined().use { arena ->
                decode(arena, input, caps, 1f, { within -> onProgress(ConvertProgress(index, count, overallFraction(index, count, within, measuring = true, evenOut = true), measuring = true)) }, cancelled) { buffer, _ ->
                    val bytes = bufferGetSize.invoke(buffer) as Long
                    Arena.ofConfined().use { scratch ->
                        val data = scratch.allocate(bytes)
                        bufferExtract.invoke(buffer, 0L, data, bytes) as Long
                        meter.add(data.asByteBuffer())
                    }
                    miniObjectUnref.invoke(buffer)
                }
            }
            meter.gain()
        }
        Arena.ofConfined().use { arena ->
            val encode = launch(arena, "appsrc name=in format=time caps=$caps max-bytes=4000000 block=true ! audioconvert ! ${encoder(request.format)} ! filesink name=sink")
            val appsrc = binGetByName.invoke(encode, arena.allocateFrom("in")) as MemorySegment
            val sink = binGetByName.invoke(encode, arena.allocateFrom("sink")) as MemorySegment
            setStringProp.invoke(sink, arena.allocateFrom("location"), arena.allocateFrom(output.absolutePath), MemorySegment.NULL)
            objectUnref.invoke(sink)
            val encodeBus = getBus.invoke(encode) as MemorySegment
            var written = 0L
            try {
                setState.invoke(encode, STATE_PLAYING) as Int
                fun push(buffer: MemorySegment, frames: Long) {
                    val b = buffer.reinterpret(112)
                    b.set(JAVA_LONG, PTS, written * 1_000_000_000L / rate)
                    b.set(JAVA_LONG, DTS, -1L)
                    b.set(JAVA_LONG, DURATION, frames * 1_000_000_000L / rate)
                    written += frames
                    check(pushBuffer.invoke(appsrc, buffer) as Int == FLOW_OK) { failure(encodeBus) ?: "The encoder stopped" }
                }
                request.inputs.forEachIndexed { index, input ->
                    if (index > 0 && gapFrames > 0) {
                        val bytes = gapFrames * BYTES_PER_FRAME
                        val silence = bufferNewAllocate.invoke(MemorySegment.NULL, bytes, MemorySegment.NULL) as MemorySegment
                        bufferMemset.invoke(silence, 0L, 0, bytes) as Long
                        push(silence, gapFrames)
                    }
                    val songProgress = { within: Float ->
                        onProgress(ConvertProgress(index, count, overallFraction(index, count, within, measuring = false, evenOut = request.evenOutVolume)))
                    }
                    lengths += decode(arena, input, caps, gains[index], songProgress, cancelled, ::push)
                }
                endOfStream.invoke(appsrc) as Int
                waitForEnd(encodeBus, cancelled)
            } catch (failure: Throwable) {
                setState.invoke(encode, STATE_NULL) as Int
                output.delete()
                throw failure
            } finally {
                setState.invoke(encode, STATE_NULL) as Int
                objectUnref.invoke(appsrc)
                objectUnref.invoke(encodeBus)
                objectUnref.invoke(encode)
            }
            val starts = songStarts(lengths, gapFrames)
            val marks = request.inputs.zip(starts) { input, start -> SongMark(input.title, start) }
            when (request.format) {
                ConvertFormat.Flac -> FlacMarks.write(output, written, marks, mapOf("TITLE" to request.title, "ALBUM" to request.title))
                ConvertFormat.M4a -> Mp4Chapters.write(output, rate, marks)
                ConvertFormat.Ogg, ConvertFormat.Opus -> OggChapters.write(output, rate, marks, mapOf("TITLE" to request.title, "ALBUM" to request.title))
                ConvertFormat.Mp3, ConvertFormat.Wav -> Unit // can't carry marks
            }
            onProgress(ConvertProgress(request.inputs.lastIndex, request.inputs.size, 1f))
            return ConvertResult(written, rate, starts)
        }
    }

    /**
     * Decodes one song (with [gain] applied) and hands each buffer of its samples to [push], which
     * owns it; returns the song's length in frames. [progress] gets 0–1 through the song.
     */
    private fun decode(
        arena: Arena,
        input: ConvertInput,
        caps: String,
        gain: Float,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
        push: (MemorySegment, Long) -> Unit,
    ): Long {
        val uriPtr = filenameToUri.invoke(arena.allocateFrom(File(input.location).absolutePath), MemorySegment.NULL) as MemorySegment
        require(uriPtr != MemorySegment.NULL) { "Can't open ${input.location}" }
        val uri = uriPtr.reinterpret(Long.MAX_VALUE).getString(0).also { gFree.invoke(uriPtr) }
        // GStreamer's volume element clips 16-bit samples at full scale; the gain is chosen so it doesn't need to.
        val volume = if (gain == 1f) "" else "volume volume=${"%.6f".format(java.util.Locale.ROOT, gain)} ! "
        val decode = launch(arena, "uridecodebin uri=\"$uri\" ! audioconvert ! audioresample ! $volume$caps ! appsink name=out sync=false")
        val appsink = binGetByName.invoke(decode, arena.allocateFrom("out")) as MemorySegment
        val bus = getBus.invoke(decode) as MemorySegment
        var frames = 0L
        try {
            setState.invoke(decode, STATE_PLAYING) as Int
            while (true) {
                if (cancelled()) throw ConvertCancelled()
                val sample = trySample.invoke(appsink, 200_000_000L) as MemorySegment
                if (sample == MemorySegment.NULL) {
                    failure(bus)?.let { error("${File(input.location).name}: $it") }
                    if (sinkIsEos.invoke(appsink) as Int != 0) break
                    continue
                }
                val buffer = sampleGetBuffer.invoke(sample) as MemorySegment
                val bytes = bufferGetSize.invoke(buffer) as Long
                // A copy shares the decoded memory but gets its own timestamps.
                val copy = bufferCopyRegion.invoke(buffer, COPY_ALL, 0L, -1L) as MemorySegment
                miniObjectUnref.invoke(sample)
                val n = bytes / BYTES_PER_FRAME
                push(copy, n)
                frames += n
                progress(fractionOf(decode))
            }
        } finally {
            setState.invoke(decode, STATE_NULL) as Int
            objectUnref.invoke(appsink)
            objectUnref.invoke(bus)
            objectUnref.invoke(decode)
        }
        return frames
    }

    private fun launch(arena: Arena, description: String): MemorySegment {
        val errorOut = arena.allocate(ADDRESS)
        val pipeline = parseLaunch.invoke(arena.allocateFrom(description), errorOut) as MemorySegment
        val gerror = errorOut.get(ADDRESS, 0)
        if (gerror != MemorySegment.NULL) {
            val text = gerror.reinterpret(16).get(ADDRESS, 8).reinterpret(Long.MAX_VALUE).getString(0)
            gErrorFree.invoke(gerror.reinterpret(16))
            if (pipeline != MemorySegment.NULL) objectUnref.invoke(pipeline)
            error("GStreamer can't build the converter: $text")
        }
        return pipeline
    }

    /** Waits for the encoder to finish writing (end of stream), checking for cancel. */
    private fun waitForEnd(bus: MemorySegment, cancelled: () -> Boolean) {
        while (true) {
            if (cancelled()) throw ConvertCancelled()
            val msg = busPop.invoke(bus, 200_000_000L, MSG_EOS or MSG_ERROR) as MemorySegment
            if (msg == MemorySegment.NULL) continue
            val type = msg.reinterpret(72).get(JAVA_INT, 64)
            val text = if (type == MSG_ERROR) errorText(msg) else null
            miniObjectUnref.invoke(msg)
            if (text != null) error(text)
            return
        }
    }

    /** An error message waiting on [bus], if any. */
    private fun failure(bus: MemorySegment): String? {
        val msg = busPop.invoke(bus, 0L, MSG_ERROR) as MemorySegment
        if (msg == MemorySegment.NULL) return null
        return errorText(msg).also { miniObjectUnref.invoke(msg) }
    }

    private fun errorText(msg: MemorySegment): String = Arena.ofConfined().use { a ->
        val errOut = a.allocate(ADDRESS)
        parseError.invoke(msg, errOut, MemorySegment.NULL)
        val gerror = errOut.get(ADDRESS, 0).reinterpret(16)
        // GError { GQuark domain; gint code; gchar *message; }
        gerror.get(ADDRESS, 8).reinterpret(Long.MAX_VALUE).getString(0).also { gErrorFree.invoke(gerror) }
    }

    /** How far through the current song the decoder is (0–1), when it knows. */
    private fun fractionOf(pipeline: MemorySegment): Float = Arena.ofConfined().use { a ->
        val pos = a.allocate(JAVA_LONG)
        val dur = a.allocate(JAVA_LONG)
        if (queryPosition.invoke(pipeline, FORMAT_TIME, pos) as Int == 0) return 0f
        if (queryDuration.invoke(pipeline, FORMAT_TIME, dur) as Int == 0) return 0f
        val d = dur.get(JAVA_LONG, 0)
        if (d <= 0) 0f else (pos.get(JAVA_LONG, 0).toFloat() / d).coerceIn(0f, 1f)
    }
}
