package dev.recorddisc.player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * True while the media (music) volume is muted or turned all the way down; feeds
 * Settings → Pause when muted. Two signals, either is enough:
 * - Android stores volume levels in its system settings, so a settings change follows any
 *   volume change (public API, may lag by up to about half a second).
 * - Android also announces volume and mute changes at once. These two announcements have been
 *   around for years but aren't in the public SDK constants, so they only make it quicker.
 */
internal fun Context.mediaMutedFlow(): Flow<Boolean> = callbackFlow {
    val audio = getSystemService(AudioManager::class.java)
    fun check() {
        trySend(audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
    }
    check()
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = check()
    }
    contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = check()
    }
    val filter = IntentFilter().apply {
        addAction("android.media.VOLUME_CHANGED_ACTION")
        addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
    }
    // Not exported: only Android itself (and this app) can deliver these to it.
    ContextCompat.registerReceiver(this@mediaMutedFlow, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose {
        contentResolver.unregisterContentObserver(observer)
        unregisterReceiver(receiver)
    }
}.distinctUntilChanged()

/** Whether the player is meant to be playing (play pressed), as it changes. */
internal fun Player.playWhenReadyFlow(): Flow<Boolean> = callbackFlow {
    trySend(playWhenReady)
    val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            trySend(playWhenReady)
        }
    }
    addListener(listener)
    awaitClose { removeListener(listener) }
}
