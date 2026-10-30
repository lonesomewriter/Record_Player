package dev.recorddisc.player.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Linux player: [GstPlayer] plus the [PlaybackQueue] rules. Every GStreamer call runs on one
 * dedicated thread, so commands and the progress ticker never overlap.
 *
 * [audioSink] is passed to [GstPlayer]; tests use "fakesink".
 */
class DesktopPlayer(audioSink: String? = null) : MusicPlayer {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "gst-player").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val gst = GstPlayer(audioSink)
    private val queue = PlaybackQueue()
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var playing = false
    private var volume = 1f
    private var error: String? = null
    /** Songs skipped in a row because they failed; stops playback once every song has failed. */
    private var failures = 0

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int, playWhenReady: Boolean) = command {
        queue.set(items, startIndex)
        failures = 0
        error = null
        playing = playWhenReady
        if (queue.current == null) {
            gst.pause()
            playing = false
            publish()
        } else {
            loadCurrent()
        }
    }

    override fun play() = command {
        if (queue.current == null) return@command
        gst.play()
        playing = true
        publish()
    }

    override fun pause() = command {
        gst.pause()
        playing = false
        publish()
    }

    override fun next() = command {
        if (queue.moveNext(userInitiated = true)) {
            error = null
            loadCurrent()
        }
    }

    override fun skipTo(index: Int) = command {
        if (queue.skipTo(index)) {
            error = null
            playing = true
            loadCurrent()
        }
    }

    override fun previous() = command {
        if (queue.current == null) return@command
        val position = gst.positionMs() ?: 0
        if (position > MusicPlayer.RESTART_THRESHOLD_MS || !queue.movePrevious()) {
            gst.seekMs(0)
            publish()
        } else {
            error = null
            loadCurrent()
        }
    }

    override fun seekTo(positionMs: Long) = command {
        if (queue.current == null) return@command
        gst.seekMs(positionMs.coerceAtLeast(0))
        publish()
    }

    override fun setRepeatMode(mode: RepeatMode) = command {
        queue.repeatMode = mode
        publish()
    }

    override fun setShuffle(enabled: Boolean) = command {
        queue.setShuffle(enabled)
        publish()
    }

    override fun setVolume(volume: Float) = command {
        this.volume = volume.coerceIn(0f, 1f)
        gst.setVolume(this.volume.toDouble())
        publish()
    }

    override fun release() {
        scope.cancel()
        executor.submit { gst.close() }.get()
        executor.shutdown()
    }

    private fun command(block: () -> Unit) {
        scope.launch { block() }
    }

    /** Loads the current song, starts it if [playing], and skips it if it can't be played. */
    private fun loadCurrent() {
        val item = queue.current ?: return
        val loadError = try {
            gst.load(item.location)
            gst.pollError()
        } catch (e: Exception) {
            e.message ?: "Can't open ${item.location}"
        }
        if (loadError != null) {
            skipFailed(loadError)
            return
        }
        failures = 0
        if (playing) gst.play()
        publish()
    }

    private fun skipFailed(message: String) {
        error = message
        failures++
        // Repeat-one must not retry a broken file forever, so skip as if Next was pressed.
        if (failures < queue.playOrder.size && queue.moveNext(userInitiated = true)) {
            loadCurrent()
        } else {
            gst.pause()
            playing = false
            publish()
        }
    }

    private fun tick() {
        if (queue.current == null) return
        // Only checks for messages already waiting, so the ticker never delays commands.
        if (playing && gst.awaitEnd(1)) {
            val endError = gst.pollError()
            when {
                endError != null -> skipFailed(endError)
                queue.moveNext(userInitiated = false) -> loadCurrent()
                else -> {
                    // End of the queue: stop at the start of the last song.
                    gst.pause()
                    gst.seekMs(0)
                    playing = false
                    publish()
                }
            }
            return
        }
        _state.update { it.copy(positionMs = gst.positionMs() ?: it.positionMs, durationMs = gst.durationMs() ?: it.durationMs) }
    }

    private fun publish() {
        val item = queue.current
        _state.value = PlaybackState(
            queue = queue.playOrder,
            index = queue.index,
            isPlaying = playing,
            positionMs = if (item == null) 0 else gst.positionMs() ?: 0,
            // Header-less VBR MP3s report a length only once playing; until then use the tag's value.
            durationMs = if (item == null) null else gst.durationMs() ?: item.durationMs,
            repeatMode = queue.repeatMode,
            shuffle = queue.shuffle,
            volume = volume,
            error = error,
        )
    }

    private companion object {
        const val TICK_MS = 200L
    }
}
