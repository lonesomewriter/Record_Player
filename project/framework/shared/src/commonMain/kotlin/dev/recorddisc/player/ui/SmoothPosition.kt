package dev.recorddisc.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.recorddisc.player.playback.PlaybackState

/**
 * The playback position for things that follow it closely (lyrics, the lyrics editor's clock).
 * The players report the position every 200–250 ms; while playing, this moves it forward on
 * every frame in between, and snaps to each new report or seek. It never runs more than a
 * second past the last report, so a stalled player doesn't run away.
 */
@Composable
fun rememberSmoothPosition(state: PlaybackState): Long {
    var shown by remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.positionMs, state.isPlaying, state.current?.id) {
        val base = state.positionMs
        shown = base
        if (!state.isPlaying) return@LaunchedEffect
        val start = withFrameNanos { it }
        val end = state.durationMs ?: Long.MAX_VALUE
        while (true) {
            val elapsedMs = (withFrameNanos { it } - start) / 1_000_000
            shown = minOf(base + minOf(elapsedMs, MAX_AHEAD_MS), end)
        }
    }
    return shown
}

private const val MAX_AHEAD_MS = 1_000L
