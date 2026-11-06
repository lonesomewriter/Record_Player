package dev.recorddisc.player.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * The desktop half of Settings → Pause when muted: whether the system's default output is muted or
 * turned down to 0. Read with `pactl`, which talks to PulseAudio and to PipeWire (via pipewire-pulse),
 * and kept current by `pactl subscribe`. No library is added; `pactl` comes with the desktop's audio.
 *
 * [available] is false when `pactl` isn't there or can't reach a sound server. [muted] then stays
 * false, and only the app's own volume slider at 0 counts as muted.
 */
class SystemMuteMonitor : AutoCloseable {
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    val available: Boolean
    private val subscription: Process?

    init {
        available = refresh()
        subscription = if (!available) null else runCatching {
            ProcessBuilder("pactl", "subscribe").redirectErrorStream(true).start()
        }.getOrNull()
        subscription?.let { process ->
            Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        // e.g. "Event 'change' on sink #56", or "... on server #-1" when the default output changes.
                        lines.filter { SINK_OR_SERVER.containsMatchIn(it) }.forEach { refresh() }
                    }
                }
            }, "system-mute-monitor").apply { isDaemon = true; start() }
        }
    }

    /** Reads the default output's mute and volume; false when pactl couldn't answer. */
    private fun refresh(): Boolean {
        val mute = run("pactl", "get-sink-mute", "@DEFAULT_SINK@") ?: return false
        val volume = run("pactl", "get-sink-volume", "@DEFAULT_SINK@")
        _muted.value = isMuted(mute) || (volume != null && isVolumeZero(volume))
        return true
    }

    override fun close() {
        subscription?.destroy()
    }

    companion object {
        private val SINK_OR_SERVER = Regex("""on (sink|server) #""")

        /** "Mute: yes" → true. */
        internal fun isMuted(output: String): Boolean =
            output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Mute:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.equals("yes", ignoreCase = true) == true

        /** "Volume: front-left: 0 /   0% / -inf dB,   front-right: 0 /   0% / -inf dB" → true when every channel is 0%. */
        internal fun isVolumeZero(output: String): Boolean {
            val line = output.lineSequence().firstOrNull { it.trimStart().startsWith("Volume:", ignoreCase = true) } ?: return false
            val percents = Regex("""(\d+)%""").findAll(line).map { it.groupValues[1].toInt() }.toList()
            return percents.isNotEmpty() && percents.all { it == 0 }
        }

        /** Runs a short command; its output, or null if it failed, timed out or isn't installed. */
        internal fun run(vararg command: String): String? = runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) output else null
        }.getOrNull()
    }
}
