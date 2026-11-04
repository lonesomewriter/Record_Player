package dev.recorddisc.player.playback

import dev.recorddisc.player.app.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuteAutoPauseTest {
    /** A player whose play/pause update [playing] at once, plus the device's mute switch. */
    private class Rig(settings: AppSettings) {
        val muted = MutableStateFlow(false)
        val playing = MutableStateFlow(false)
        var prefs = settings
        var plays = 0
        var pauses = 0
        val autoPause = MuteAutoPause(
            muted, playing, { playing.value }, { prefs },
            pause = { pauses++; playing.value = false },
            play = { plays++; playing.value = true },
        )
    }

    /** Runs [block] with the rule watching, processing every change immediately. */
    private fun watch(settings: AppSettings, block: Rig.() -> Unit) = runBlocking {
        val rig = Rig(settings)
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { rig.autoPause.run() }
        rig.block()
        job.cancel()
    }

    @Test
    fun mutingPausesAndUnmutingStaysPausedByDefault() = watch(AppSettings()) {
        playing.value = true
        muted.value = true
        assertFalse(playing.value, "Pause when muted is on by default")
        assertTrue(autoPause.pausedByMute)
        muted.value = false
        assertFalse(playing.value, "Resume when unmuted is off by default")
        assertEquals(0, plays)
    }

    @Test
    fun unmutingResumesWhenTheMutePausedItAndResumeIsOn() = watch(AppSettings(resumeOnUnmute = true)) {
        playing.value = true
        muted.value = true
        muted.value = false
        assertTrue(playing.value)
        assertEquals(1, plays)
        assertFalse(autoPause.pausedByMute)
    }

    @Test
    fun aPauseTheUserMadeIsNeverUndoneByUnmuting() = watch(AppSettings(resumeOnUnmute = true)) {
        playing.value = false // paused by the user
        muted.value = true
        muted.value = false
        assertFalse(playing.value)
        assertEquals(0, pauses)
        assertEquals(0, plays)
    }

    @Test
    fun playingWhileMutedMeansUnmutingLeavesItAlone() = watch(AppSettings(resumeOnUnmute = true)) {
        playing.value = true
        muted.value = true
        playing.value = true // the user presses Play while muted
        assertFalse(autoPause.pausedByMute)
        playing.value = false // and pauses again
        muted.value = false
        assertFalse(playing.value, "the mute no longer owns this pause")
        assertEquals(0, plays)
    }

    @Test
    fun pauseWhenMutedOffDoesNothing() = watch(AppSettings(pauseOnMute = false, resumeOnUnmute = true)) {
        playing.value = true
        muted.value = true
        assertTrue(playing.value)
        muted.value = false
        assertEquals(0, pauses)
        assertEquals(0, plays)
    }
}
