package dev.recorddisc.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
        session = MediaSession.Builder(this, CurrentFirstShuffle(exo)).setCallback(Callback).setSessionActivity(open).build()
        // Settings → "Pause on headphone unplug".
        scope.launch {
            (application as RecordDiscApplication).model.settings.filterNotNull().map { it.pauseOnUnplug }.distinctUntilChanged()
                .collect { exo.setHandleAudioBecomingNoisy(it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private object Callback : MediaSession.Callback {
        /** Only this app, the system (notification, lock screen, Bluetooth) and apps the user allowed as media controllers. */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.isTrusted) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()

        /**
         * Media3 strips URIs from items sent by controllers, so rebuild them from the request metadata.
         * Only local files and documents are accepted.
         */
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

    private companion object {
        val LOCAL_SCHEMES = setOf("content", "file")
    }
}
