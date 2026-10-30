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
 * "Pause on headphone unplug" for Linux: calls [onOutputRemoved] when the default audio output
 * disappears, such as Bluetooth or USB headphones disconnecting, using GStreamer's device
 * monitor (PipeWire/PulseAudio). A wired headphone jack usually switches ports on the same
 * output instead of removing it, which this can't see.
 *
 * [available] is false when the system offers no device monitoring; then nothing is watched.
 */
class OutputMonitor(private val onOutputRemoved: () -> Unit) : AutoCloseable {
    private companion object {
        val linker: Linker = Linker.nativeLinker()
        val gst: SymbolLookup = SymbolLookup.libraryLookup("libgstreamer-1.0.so.0", Arena.global())

        fun fn(name: String, desc: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(gst.find(name).orElseThrow { UnsatisfiedLinkError(name) }, desc)

        val gstInit = fn("gst_init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
        val monitorNew = fn("gst_device_monitor_new", FunctionDescriptor.of(ADDRESS))
        val addFilter = fn("gst_device_monitor_add_filter", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        val monitorBus = fn("gst_device_monitor_get_bus", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val monitorStart = fn("gst_device_monitor_start", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val monitorStop = fn("gst_device_monitor_stop", FunctionDescriptor.ofVoid(ADDRESS))
        val busPop = fn("gst_bus_timed_pop_filtered", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT))
        val parseRemoved = fn("gst_message_parse_device_removed", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
        val deviceProperties = fn("gst_device_get_properties", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val structureGetBoolean = fn("gst_structure_get_boolean", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        val structureFree = fn("gst_structure_free", FunctionDescriptor.ofVoid(ADDRESS))
        val miniObjectUnref = fn("gst_mini_object_unref", FunctionDescriptor.ofVoid(ADDRESS))
        val objectUnref = fn("gst_object_unref", FunctionDescriptor.ofVoid(ADDRESS))

        /** GST_MESSAGE_EXTENDED + 2; extended types share the top bit, so the exact type is checked too. */
        const val MSG_DEVICE_REMOVED = (1 shl 31) + 2
        const val POLL_NS = 500_000_000L

        init {
            gstInit.invoke(MemorySegment.NULL, MemorySegment.NULL)
        }
    }

    private val arena = Arena.ofShared()
    private val monitor = monitorNew.invoke() as MemorySegment
    private val bus = monitorBus.invoke(monitor) as MemorySegment
    @Volatile
    private var running = true
    private val thread: Thread?

    val available: Boolean

    init {
        addFilter.invoke(monitor, arena.allocateFrom("Audio/Sink"), MemorySegment.NULL) as Int
        available = monitorStart.invoke(monitor) as Int != 0
        thread = if (available) Thread(::run, "output-monitor").apply { isDaemon = true; start() } else null
    }

    private fun run() {
        while (running) {
            val msg = busPop.invoke(bus, POLL_NS, MSG_DEVICE_REMOVED) as MemorySegment
            if (msg == MemorySegment.NULL) continue
            val removedDefault = msg.reinterpret(72).get(JAVA_INT, 64) == MSG_DEVICE_REMOVED && wasDefault(msg)
            miniObjectUnref.invoke(msg)
            if (removedDefault && running) {
                try {
                    onOutputRemoved()
                } catch (e: Exception) {
                    // Keep watching.
                }
            }
        }
    }

    /** PipeWire/PulseAudio mark the default output "is-default"; without that property, assume it was. */
    private fun wasDefault(msg: MemorySegment): Boolean = Arena.ofConfined().use { a ->
        val out = a.allocate(ADDRESS)
        parseRemoved.invoke(msg, out)
        val device = out.get(ADDRESS, 0)
        if (device == MemorySegment.NULL) return@use true
        val props = deviceProperties.invoke(device) as MemorySegment
        val isDefault = if (props == MemorySegment.NULL) {
            true
        } else {
            val flag = a.allocate(JAVA_INT)
            val found = structureGetBoolean.invoke(props, a.allocateFrom("is-default"), flag) as Int != 0
            structureFree.invoke(props)
            !found || flag.get(JAVA_INT, 0) != 0
        }
        objectUnref.invoke(device)
        isDefault
    }

    override fun close() {
        running = false
        thread?.join(2_000)
        if (available) monitorStop.invoke(monitor)
        objectUnref.invoke(bus)
        objectUnref.invoke(monitor)
        arena.close()
    }
}
