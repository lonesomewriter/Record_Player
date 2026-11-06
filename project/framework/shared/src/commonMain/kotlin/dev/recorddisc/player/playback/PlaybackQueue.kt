package dev.recorddisc.player.playback

import kotlin.random.Random

/**
 * Queue order, shuffle and repeat rules, without any audio. Used by the desktop player;
 * Android's Media3 player has the same rules built in.
 */
class PlaybackQueue(private val random: Random = Random.Default) {
    private var items: List<QueueItem> = emptyList()
    /** Indices into [items] in play order. */
    private var order: List<Int> = emptyList()

    /** Position in [order], or -1 when empty. */
    var index: Int = -1
        private set
    var repeatMode: RepeatMode = RepeatMode.Off
    var shuffle: Boolean = false
        private set

    val playOrder: List<QueueItem> get() = order.map { items[it] }
    val current: QueueItem? get() = order.getOrNull(index)?.let { items[it] }

    fun set(newItems: List<QueueItem>, startIndex: Int) {
        items = newItems
        if (newItems.isEmpty()) {
            order = emptyList()
            index = -1
            return
        }
        val start = startIndex.coerceIn(newItems.indices)
        order = if (shuffle) shuffledStartingWith(start) else newItems.indices.toList()
        index = order.indexOf(start)
    }

    /** Replaces the queued copies of [item] (same id), keeping the length already known if [item] has none. */
    fun update(item: QueueItem) {
        items = items.map { if (it.id == item.id) item.copy(durationMs = item.durationMs ?: it.durationMs) else it }
    }

    /** Turning shuffle on keeps the current song and shuffles the rest after it; off restores the original order. */
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffle) return
        shuffle = enabled
        val cur = order.getOrNull(index) ?: return
        order = if (enabled) shuffledStartingWith(cur) else items.indices.toList()
        index = order.indexOf(cur)
    }

    /**
     * Moves to the next song. [userInitiated] is true for the Next button and false when a song ends:
     * repeat-one only repeats on its own, the button still skips. Wraps around only with repeat-all
     * (same rule as Media3 on Android). Returns false at the end of the queue.
     */
    fun moveNext(userInitiated: Boolean): Boolean {
        if (index < 0) return false
        if (!userInitiated && repeatMode == RepeatMode.One) return true
        if (index + 1 < order.size) {
            index++
            return true
        }
        if (repeatMode != RepeatMode.All) return false
        index = 0
        return true
    }

    /**
     * The song that follows when the current one ends on its own (what [moveNext] with
     * userInitiated = false would move to), or null at the end of the queue. Used for gapless playback.
     */
    fun peekNext(): QueueItem? {
        if (index < 0) return null
        val next = when {
            repeatMode == RepeatMode.One -> index
            index + 1 < order.size -> index + 1
            repeatMode == RepeatMode.All -> 0
            else -> return null
        }
        return items[order[next]]
    }

    /** Jumps to position [position] of the play order. Returns false if there is no such position. */
    fun skipTo(position: Int): Boolean {
        if (position !in order.indices) return false
        index = position
        return true
    }

    /** Moves to the previous song; at the start it wraps only with repeat-all. Returns false if there is none. */
    fun movePrevious(): Boolean {
        if (index < 0) return false
        if (index > 0) {
            index--
            return true
        }
        if (repeatMode != RepeatMode.All) return false
        index = order.size - 1
        return true
    }

    private fun shuffledStartingWith(first: Int): List<Int> =
        listOf(first) + items.indices.filter { it != first }.shuffled(random)
}
