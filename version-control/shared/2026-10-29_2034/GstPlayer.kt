package dev.recorddisc.player.playback

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
 * Minimal audio player over the system GStreamer (playbin), called through Java's FFM API.
 * Only the handful of C functions an audio player needs; no third-party binding library.
 *
 * [audioSink] null uses GStreamer's automatic output; tests pass "fakesink" (real-time clock, no sound card).
 */
class GstPlayer(audioSink: String? = null) : AutoCloseable {
    private companion object {
        val linker: Linker = Linker.nativeLinker()
        val gst: SymbolLookup = SymbolLookup.libraryLookup("libgstreamer-1.0.so.0", Arena.global())
        val gobject: SymbolLookup = SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global())
        val glib: SymbolLookup = SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global())

        fun fn(lib: SymbolLookup, name: String, desc: FunctionDescriptor, vararg opts: Linker.Option): MethodHandle =
            linker.downcallHandle(lib.find(name).orElseThrow { UnsatisfiedLinkError(name) }, desc, *opts)

        val gstInit = fn(gst, "gst_init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
        val factoryMake = fn(gst, "gst_element_factory_make", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val setState = fn(gst, "gst_element_set_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val getState = fn(gst, "gst_element_get_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
        val queryPosition = fn(gst, "gst_element_query_position", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
        val queryDuration = fn(gst, "gst_element_query_duration", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
        val seekSimple = fn(gst, "gst_element_seek_simple", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG))
        val getBus = fn(gst, "gst_element_get_bus", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val busPop = fn(gst, "gst_bus_timed_pop_filtered", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT))
        val parseError = fn(gst, "gst_message_parse_error", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS))
        val miniObjectUnref = fn(gst, "gst_mini_object_unref", FunctionDescriptor.ofVoid(ADDRESS))
        val objectUnref = fn(gst, "gst_object_unref", FunctionDescriptor.ofVoid(ADDRESS))
        val filenameToUri = fn(gst, "gst_filename_to_uri", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val gFree = fn(glib, "g_free", FunctionDescriptor.ofVoid(ADDRESS))
        val gErrorFree = fn(glib, "g_error_free", FunctionDescriptor.ofVoid(ADDRESS))
        // g_object_set is variadic: (object, name, value, NULL)
        val setPtrProp = fn(gobject, "g_object_set", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS), Linker.Option.firstVariadicArg(2))
        val setIntProp = fn(gobject, "g_object_set", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS), Linker.Option.firstVariadicArg(2))

        const val FORMAT_TIME = 3
        const val STATE_NULL = 1
        const val STATE_PAUSED = 3
        const val STATE_PLAYING = 4
        const val SEEK_FLUSH_ACCURATE = 0x1 or 0x2
        const val MSG_EOS = 1
        const val MSG_ERROR = 2
        const val PLAY_FLAG_AUDIO_SOFTVOL = 0x02 or 0x10 // audio only, software volume

        init {
            gstInit.invoke(MemorySegment.NULL, MemorySegment.NULL)
        }
    }

    private val arena = Arena.ofShared()
    private val playbin: MemorySegment = (factoryMake.invoke(str("playbin"), str("player")) as MemorySegment)
        .also { require(it != MemorySegment.NULL) { "GStreamer playbin is not available" } }
    private val bus: MemorySegment = getBus.invoke(playbin) as MemorySegment

    init {
        setIntProp.invoke(playbin, str("flags"), PLAY_FLAG_AUDIO_SOFTVOL, MemorySegment.NULL)
        if (audioSink != null) {
            val sink = factoryMake.invoke(str(audioSink), MemorySegment.NULL) as MemorySegment
            setIntProp.invoke(sink, str("sync"), 1, MemorySegment.NULL)
            setPtrProp.invoke(playbin, str("audio-sink"), sink, MemorySegment.NULL)
        }
    }

    /** Loads a local file and waits until it is ready (paused at the start). */
    fun load(path: String) {
        setState.invoke(playbin, STATE_NULL) as Int
        ended = false
        pendingError = null
        Arena.ofConfined().use { a ->
            val err = a.allocate(ADDRESS)
            val uri = filenameToUri.invoke(a.allocateFrom(path), err) as MemorySegment
            check(uri != MemorySegment.NULL) { "Invalid path: $path" }
            setPtrProp.invoke(playbin, str("uri"), uri, MemorySegment.NULL)
            gFree.invoke(uri)
        }
        setState.invoke(playbin, STATE_PAUSED) as Int
        waitForState()
    }

    fun play() { setState.invoke(playbin, STATE_PLAYING) as Int }
    fun pause() { setState.invoke(playbin, STATE_PAUSED) as Int; waitForState() }

    fun positionMs(): Long? = query(queryPosition)
    fun durationMs(): Long? = query(queryDuration)

    fun seekMs(ms: Long): Boolean {
        ended = false
        val ok = seekSimple.invoke(playbin, FORMAT_TIME, SEEK_FLUSH_ACCURATE, ms * 1_000_000) as Int != 0
        waitForState()
        return ok
    }

    private var pendingError: String? = null
    private var ended = false

    /** Non-blocking: returns an error message if GStreamer reported one. */
    fun pollError(): String? {
        while (nextMessage(0)) Unit
        return pendingError.also { pendingError = null }
    }

    /** Waits up to [timeoutMs] for the end of the stream or an error; errors are kept for [pollError]. */
    fun awaitEnd(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!ended && pendingError == null) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return false
            nextMessage(left)
        }
        ended = false
        return true
    }

    /**
     * Takes one end-of-stream or error message off the bus, waiting up to [timeoutNs].
     * Both types must be read with one filter: the bus discards messages that don't match it.
     */
    private fun nextMessage(timeoutNs: Long): Boolean {
        val msg = busPop.invoke(bus, timeoutNs, MSG_EOS or MSG_ERROR) as MemorySegment
        if (msg == MemorySegment.NULL) return false
        // GstMessage { GstMiniObject (64 bytes); GstMessageType type; ... }
        if (msg.reinterpret(72).get(JAVA_INT, 64) == MSG_ERROR) {
            pendingError = Arena.ofConfined().use { a ->
                val errOut = a.allocate(ADDRESS)
                parseError.invoke(msg, errOut, MemorySegment.NULL)
                val gerror = errOut.get(ADDRESS, 0).reinterpret(16)
                // GError { GQuark domain; gint code; gchar *message; }
                val text = gerror.get(ADDRESS, 8).reinterpret(Long.MAX_VALUE).getString(0)
                gErrorFree.invoke(gerror)
                text
            }
        } else {
            ended = true
        }
        miniObjectUnref.invoke(msg)
        return true
    }

    override fun close() {
        setState.invoke(playbin, STATE_NULL) as Int
        objectUnref.invoke(bus)
        objectUnref.invoke(playbin)
        arena.close()
    }

    private fun waitForState() {
        getState.invoke(playbin, MemorySegment.NULL, MemorySegment.NULL, 5_000_000_000L) as Int
    }

    private fun query(handle: MethodHandle): Long? = Arena.ofConfined().use { a ->
        val out = a.allocate(JAVA_LONG)
        if (handle.invoke(playbin, FORMAT_TIME, out) as Int == 0) null else out.get(JAVA_LONG, 0) / 1_000_000
    }

    private fun str(s: String): MemorySegment = arena.allocateFrom(s)
}
