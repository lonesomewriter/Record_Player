package dev.recorddisc.player.playback

import dev.recorddisc.player.app.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Settings → Pause when muted / Resume when unmuted (2026-11-04 mockups), shared by Android and
 * desktop. Each platform only says when its output is [muted] (Android: media volume muted or at 0;
 * desktop: system output muted or at 0, or the app's volume slider at 0).
 *
 * - Muting while something plays pauses it, if Pause when muted is on.
 * - Unmuting resumes it only when the mute is what paused it and Resume when unmuted is on.
 * - Any other start of playback in between (the user presses Play while muted, picks a song)
 *   means the mute no longer owns that pause, so unmuting then does nothing.
 *
 * Events are handled one at a time from a single merged stream, so there are no races.
 */
class MuteAutoPause(
    private val muted: Flow<Boolean>,
    private val isPlaying: Flow<Boolean>,
    private val playingNow: () -> Boolean,
    private val settings: () -> AppSettings?,
    private val pause: () -> Unit,
    private val play: () -> Unit,
) {
    /** True only while a mute is what paused playback. */
    var pausedByMute = false
        private set

    private sealed interface Event {
        data class Muted(val muted: Boolean) : Event
        data class Playing(val playing: Boolean) : Event
    }

    /** Runs until the calling coroutine is cancelled. */
    suspend fun run() {
        merge(
            muted.distinctUntilChanged().map { Event.Muted(it) },
            isPlaying.distinctUntilChanged().map { Event.Playing(it) },
        ).collect { event ->
            val prefs = settings() ?: AppSettings()
            when (event) {
                is Event.Muted -> if (event.muted) {
                    if (prefs.pauseOnMute && playingNow()) {
                        pause()
                        pausedByMute = true
                    }
                } else {
                    if (pausedByMute && prefs.resumeOnUnmute && !playingNow()) play()
                    pausedByMute = false
                }
                is Event.Playing -> if (event.playing) pausedByMute = false
            }
        }
    }
}
