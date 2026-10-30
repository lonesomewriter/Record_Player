package dev.recorddisc.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Android player: a Media3 controller connected to [PlaybackService]. Commands can be called from
 * any thread; they run on the main thread, which Media3 requires, and wait for the connection.
 */
class AndroidPlayer(context: Context) : MusicPlayer {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    /** Queue items by media ID, for turning the controller's items back into [QueueItem]s. */
    private var known: Map<String, QueueItem> = emptyMap()
    private var error: String? = null
    /**
     * A seek made while the song was still loading; Media3 would drop it, so it is applied once
     * seeking is possible, but only to the song it was meant for ([pendingSeekItem]).
     */
    private var pendingSeekMs: Long? = null
    private var pendingSeekItem: String? = null
    /** Songs skipped in a row because they failed; stops playback once every song has failed. */
    private var failures = 0
    private var released = false

    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            val seek = pendingSeekMs ?: return
            if (availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                pendingSeekMs = null
                val c = controller ?: return
                if (c.currentMediaItem?.mediaId == pendingSeekItem) c.seekTo(seek)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_READY) failures = 0
            if (playbackState == Player.STATE_ENDED) {
                // End of the queue: stop at the start of the last song, like the desktop player.
                c.pause()
                c.seekTo(c.currentMediaItemIndex, 0)
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val c = controller ?: return
            error = e.message ?: e.errorCodeName
            failures++
            // Skip the broken song (also with repeat-one, which would otherwise retry it forever).
            if (failures < c.mediaItemCount && c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.prepare()
            } else {
                c.pause()
            }
            publish()
        }
    }

    /** Position isn't reported as events, so poll it; the rest of the state comes from [listener]. */
    private val ticker = object : Runnable {
        override fun run() {
            controller?.let { c ->
                _state.update { s ->
                    if (s.current == null) s
                    else s.copy(positionMs = pendingSeekMs ?: c.currentPosition, durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: s.durationMs)
                }
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    init {
        future.addListener({
            if (released) return@addListener
            val c = try {
                future.get()
            } catch (e: Exception) {
                error = "Can't connect to the playback service: ${e.message}"
                publish()
                return@addListener
            }
            c.addListener(listener)
            controller = c
            pending.forEach { it(c) }
            pending.clear()
            publish()
            main.post(ticker)
        }, main::post)
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int, playWhenReady: Boolean, startPositionMs: Long) = command { c ->
        known = items.associateBy { it.id.toString() }
        pendingSeekMs = null
        error = null
        failures = 0
        if (items.isEmpty()) {
            c.clearMediaItems()
            return@command
        }
        c.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(items.indices), startPositionMs.coerceAtLeast(0))
        c.prepare()
        c.playWhenReady = playWhenReady
    }

    override fun play() = command { c ->
        // After an error the player is idle and needs preparing again.
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    override fun pause() = command { it.pause() }

    override fun next() = command { c ->
        if (!c.hasNextMediaItem()) return@command
        pendingSeekMs = null
        error = null
        c.seekToNextMediaItem()
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    override fun skipTo(index: Int) = command { c ->
        val window = playOrder(c).getOrNull(index) ?: return@command
        pendingSeekMs = null
        error = null
        c.seekTo(window, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    // Media3's rule matches ours: restart if more than 3 s in (its default), otherwise go back.
    override fun previous() = command { c ->
        pendingSeekMs = null
        error = null
        c.seekToPrevious()
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    override fun seekTo(positionMs: Long) = command { c ->
        val target = positionMs.coerceAtLeast(0)
        if (c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            pendingSeekMs = null
            c.seekTo(target)
        } else {
            pendingSeekMs = target
            pendingSeekItem = c.currentMediaItem?.mediaId
            publish()
        }
    }

    override fun setRepeatMode(mode: RepeatMode) = command {
        it.repeatMode = when (mode) {
            RepeatMode.Off -> Player.REPEAT_MODE_OFF
            RepeatMode.One -> Player.REPEAT_MODE_ONE
            RepeatMode.All -> Player.REPEAT_MODE_ALL
        }
    }

    override fun setShuffle(enabled: Boolean) = command { it.shuffleModeEnabled = enabled }

    override fun setVolume(volume: Float) = command { it.volume = volume.coerceIn(0f, 1f) }

    override fun release() {
        main.post {
            released = true
            main.removeCallbacks(ticker)
            pending.clear()
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    private fun command(block: (MediaController) -> Unit) {
        main.post {
            if (released) return@post
            val c = controller
            if (c == null) pending += block else block(c)
        }
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = PlaybackState(error = error)
            return
        }
        val order = playOrder(c)
        val queue = order.map { c.getMediaItemAt(it).toQueueItem() }
        val current = queue.getOrNull(order.indexOf(c.currentMediaItemIndex))
        _state.value = PlaybackState(
            queue = queue,
            index = order.indexOf(c.currentMediaItemIndex),
            isPlaying = c.isPlaying,
            positionMs = if (current == null) 0 else pendingSeekMs ?: c.currentPosition,
            durationMs = if (current == null) null else c.duration.takeIf { it != C.TIME_UNSET } ?: current.durationMs,
            repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                else -> RepeatMode.Off
            },
            shuffle = c.shuffleModeEnabled,
            volume = c.volume,
            error = error,
        )
    }

    /** Media item indices in the order they play (the shuffled order when shuffle is on). */
    private fun playOrder(c: MediaController): List<Int> {
        val timeline = c.currentTimeline
        val order = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
        while (i != C.INDEX_UNSET) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return order
    }

    private fun MediaItem.toQueueItem(): QueueItem = known[mediaId] ?: QueueItem(
        id = mediaId.toLongOrNull() ?: -1,
        location = requestMetadata.mediaUri?.toString().orEmpty(),
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString(),
        album = mediaMetadata.albumTitle?.toString(),
        durationMs = mediaMetadata.durationMs,
    )

    private companion object {
        const val TICK_MS = 250L

        /** Library locations are document URIs; plain paths (e.g. tests) become file URIs. */
        fun QueueItem.toMediaItem(): MediaItem {
            val uri = if (location.startsWith("content://")) Uri.parse(location) else Uri.fromFile(File(location))
            return MediaItem.Builder()
                .setMediaId(id.toString())
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setDurationMs(durationMs)
                        .build(),
                )
                .build()
        }
    }
}
