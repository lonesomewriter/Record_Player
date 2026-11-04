package dev.recorddisc.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.recorddisc.player.MainActivity
import dev.recorddisc.player.RecordDiscApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps music playing in the background and provides the lock-screen / notification controls.
 * The app talks to it through [AndroidPlayer]; Media3 handles audio focus, headphone unplugging
 * and the media notification.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val accepted = super.onConnect(session, controller)
            val buttons = mediaButtonPreferences(session.player)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(accepted.availablePlayerCommands)
                .setAvailableSessionCommands(
                    accepted.availableSessionCommands.buildUpon()
                        .add(SHUFFLE_COMMAND)
                        .add(REPEAT_COMMAND)
                        .build(),
                )
                .setMediaButtonPreferences(buttons)
                .setCustomLayout(buttons)
                .build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (uri == null || uri.scheme !in LOCAL_SCHEMES) {
                    return Futures.immediateFailedFuture(IllegalArgumentException("Not a local file: $uri"))
                }
                item.buildUpon().setUri(uri).build()
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val model = (this@PlaybackService.application as RecordDiscApplication).model
            when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> model.setShuffle(!session.player.shuffleModeEnabled)
                ACTION_TOGGLE_REPEAT -> model.setRepeatMode(
                    when (session.player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> dev.recorddisc.player.playback.RepeatMode.All
                        Player.REPEAT_MODE_ALL -> dev.recorddisc.player.playback.RepeatMode.One
                        else -> dev.recorddisc.player.playback.RepeatMode.Off
                    },
                )
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val buttons = mediaButtonPreferences(player)
            session?.setMediaButtonPreferences(buttons)
            session?.setCustomLayout(buttons)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU awake while playing with the screen off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // On by default: sends playback statistics to Android's MediaMetrics service, which Google may collect.
            .setUsePlatformDiagnostics(false)
            .build()
        // Tapping the notification or the lock-screen player opens the app on Now Playing.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NOW_PLAYING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, CurrentFirstShuffle(exo)).setCallback(callback).setSessionActivity(open).build()
        exo.addListener(playerListener)
        val app = application as RecordDiscApplication
        app.startPlaybackWidgetUpdates()
        // Settings → "Pause on headphone unplug".
        scope.launch {
            app.model.settings.filterNotNull().map { it.pauseOnUnplug }.distinctUntilChanged()
                .collect { exo.setHandleAudioBecomingNoisy(it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Like the desktop player: turning shuffle on keeps the current song and shuffles the rest after it. */
    private class CurrentFirstShuffle(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled) reshuffle()
            super.setShuffleModeEnabled(shuffleModeEnabled)
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
            if (exo.shuffleModeEnabled) reshuffle()
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            super.setMediaItems(mediaItems, resetPosition)
            if (exo.shuffleModeEnabled) reshuffle()
        }

        private fun reshuffle() {
            val count = exo.mediaItemCount
            if (count == 0) return
            val first = exo.currentMediaItemIndex
            val order = listOf(first) + (0 until count).filter { it != first }.shuffled()
            exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        }
    }

    companion object {
        private const val ACTION_TOGGLE_SHUFFLE = "dev.recorddisc.player.playback.TOGGLE_SHUFFLE"
        private const val ACTION_TOGGLE_REPEAT = "dev.recorddisc.player.playback.TOGGLE_REPEAT"
        private val SHUFFLE_COMMAND = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        private val REPEAT_COMMAND = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
        val LOCAL_SCHEMES = setOf("content", "file")

        private fun mediaButtonPreferences(player: Player): List<CommandButton> = listOf(
            CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (player.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
                .setSessionCommand(SHUFFLE_COMMAND)
                .build(),
            CommandButton.Builder(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                    Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                    else -> CommandButton.ICON_REPEAT_OFF
                },
            )
                .setDisplayName(
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        else -> "Repeat off"
                    },
                )
                .setSessionCommand(REPEAT_COMMAND)
                .build(),
        )
    }
}
