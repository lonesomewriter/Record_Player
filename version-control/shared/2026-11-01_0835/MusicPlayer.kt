package dev.recorddisc.player.playback

import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.displayTitle
import kotlinx.coroutines.flow.StateFlow

/** One song in the play queue. */
data class QueueItem(
    val id: Long,
    /** Absolute path on desktop; document URI on Android. */
    val location: String,
    val title: String,
    val artist: String?,
    val album: String?,
    /** Length from the tags; the player's own measurement replaces it once known. */
    val durationMs: Long?,
)

fun TrackEntity.toQueueItem(): QueueItem = QueueItem(id, location, displayTitle, artist, album, durationMs)

enum class RepeatMode { Off, One, All }

data class PlaybackState(
    /** Songs in the order they will play (shuffled order when shuffle is on). */
    val queue: List<QueueItem> = emptyList(),
    /** Position of the current song in [queue], or -1 when the queue is empty. */
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val shuffle: Boolean = false,
    /** 0 (silent) to 1 (full). */
    val volume: Float = 1f,
    /** Last playback error, e.g. a file that was skipped because it can't be decoded. Cleared by the next song change the user makes. */
    val error: String? = null,
) {
    val current: QueueItem? get() = queue.getOrNull(index)
}

/**
 * The app's player, implemented with GStreamer on Linux and Media3 on Android.
 * Commands return immediately; their effect shows up in [state].
 */
interface MusicPlayer {
    val state: StateFlow<PlaybackState>

    /** Replaces the queue and starts at [startIndex] (an index into [items], before any shuffle), [startPositionMs] into that song. */
    fun setQueue(items: List<QueueItem>, startIndex: Int = 0, playWhenReady: Boolean = true, startPositionMs: Long = 0)
    fun play()
    fun pause()
    fun next()
    /** Jumps to song [index] of [PlaybackState.queue] (the play order) and plays it. */
    fun skipTo(index: Int)
    /** Restarts the song if more than [RESTART_THRESHOLD_MS] in, otherwise goes to the previous one. */
    fun previous()
    fun seekTo(positionMs: Long)
    fun setRepeatMode(mode: RepeatMode)
    fun setShuffle(enabled: Boolean)
    /** Stops at the end of the current song instead of automatically advancing the queue. */
    fun setPauseAtTrackEnd(enabled: Boolean)
    /** [volume] from 0 (silent) to 1 (full). */
    fun setVolume(volume: Float)
    /**
     * A song's details changed: shows [item]'s title, artist and album wherever it is queued.
     * With [reload], the current song's file was rewritten in place, so it is opened again at the
     * same position, playing if [play].
     */
    fun updateItem(item: QueueItem, reload: Boolean = false, play: Boolean = false)
    fun release()

    companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
