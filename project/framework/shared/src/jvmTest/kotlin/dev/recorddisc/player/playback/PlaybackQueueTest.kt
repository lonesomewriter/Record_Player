package dev.recorddisc.player.playback

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueTest {
    private val songs = (1..5).map { QueueItem(it.toLong(), "/music/$it.flac", "Song $it", null, null, null) }

    private fun queue(start: Int = 0, repeat: RepeatMode = RepeatMode.Off) =
        PlaybackQueue(Random(42)).apply {
            repeatMode = repeat
            set(songs, start)
        }

    @Test
    fun walksForwardAndBackAndStopsAtTheEnds() {
        val q = queue(start = 3)
        assertEquals(songs[3], q.current)
        assertTrue(q.moveNext(userInitiated = false))
        assertEquals(songs[4], q.current)
        assertFalse(q.moveNext(userInitiated = false), "end of queue")
        assertFalse(q.moveNext(userInitiated = true), "Next at the end does nothing")
        assertEquals(songs[4], q.current)

        val first = queue(start = 0)
        assertFalse(first.movePrevious(), "nothing before the first song")
        assertEquals(songs[0], first.current)
    }

    @Test
    fun repeatAllWrapsBothWays() {
        val q = queue(start = 4, repeat = RepeatMode.All)
        assertTrue(q.moveNext(userInitiated = false))
        assertEquals(songs[0], q.current)
        assertTrue(q.movePrevious())
        assertEquals(songs[4], q.current)
    }

    @Test
    fun repeatOneRepeatsOnItsOwnButNextStillSkips() {
        val q = queue(start = 1, repeat = RepeatMode.One)
        assertTrue(q.moveNext(userInitiated = false))
        assertEquals(songs[1], q.current, "song ended: same song again")
        assertTrue(q.moveNext(userInitiated = true))
        assertEquals(songs[2], q.current, "Next button moves on")

        val last = queue(start = 4, repeat = RepeatMode.One)
        assertFalse(last.moveNext(userInitiated = true), "repeat-one does not wrap (same as Media3)")
    }

    @Test
    fun shuffleKeepsCurrentSongFirstAndOffRestoresOrder() {
        val q = queue(start = 2)
        q.setShuffle(true)
        assertEquals(songs[2], q.current)
        assertEquals(0, q.index)
        assertEquals(songs[2], q.playOrder.first())
        assertEquals(songs.toSet(), q.playOrder.toSet())
        assertEquals(songs.size, q.playOrder.size)

        q.moveNext(userInitiated = true)
        val now = q.current
        q.setShuffle(false)
        assertEquals(songs, q.playOrder)
        assertEquals(now, q.current, "turning shuffle off keeps the current song")
        assertEquals(songs.indexOf(now), q.index)
    }

    @Test
    fun newQueueWhileShuffledStartsWithTheChosenSong() {
        val q = queue()
        q.setShuffle(true)
        q.set(songs, 3)
        assertEquals(songs[3], q.playOrder.first())
        assertEquals(songs[3], q.current)
    }

    @Test
    fun skipToJumpsWithinThePlayOrder() {
        val q = queue()
        q.setShuffle(true)
        val target = q.playOrder[3]
        assertTrue(q.skipTo(3))
        assertEquals(target, q.current)
        assertFalse(q.skipTo(5), "past the end")
        assertFalse(q.skipTo(-1))
        assertEquals(target, q.current)
    }

    @Test
    fun emptyQueueHasNoCurrentSong() {
        val q = PlaybackQueue()
        q.set(emptyList(), 0)
        assertNull(q.current)
        assertEquals(-1, q.index)
        assertFalse(q.moveNext(userInitiated = true))
        assertFalse(q.movePrevious())
        q.setShuffle(true)
        assertNull(q.current)
    }

    @Test
    fun peekNextShowsWhatPlaysWhenTheSongEnds() {
        val q = PlaybackQueue()
        assertNull(q.peekNext())
        q.set(songs, 0)
        assertEquals(songs[1], q.peekNext())
        q.skipTo(songs.size - 1)
        assertNull(q.peekNext(), "end of the queue")
        q.repeatMode = RepeatMode.All
        assertEquals(songs[0], q.peekNext())
        q.repeatMode = RepeatMode.One
        assertEquals(songs.last(), q.peekNext())
        assertEquals(songs.last(), q.current, "peeking doesn't move")
    }
}
