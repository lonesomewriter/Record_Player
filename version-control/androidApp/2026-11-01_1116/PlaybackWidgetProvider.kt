package dev.recorddisc.player.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.recorddisc.player.R
import dev.recorddisc.player.RecordDiscApplication
import dev.recorddisc.player.app.AppModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class WidgetPlaybackState(
    val current: QueueItem?,
    val isPlaying: Boolean,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
)

class PlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val application = context.applicationContext as RecordDiscApplication
        application.startPlaybackWidgetUpdates()
        application.refreshPlaybackWidgets()
    }

    override fun onDisabled(context: Context) {
        (context.applicationContext as RecordDiscApplication).stopPlaybackWidgetUpdates()
    }

    companion object {
        internal suspend fun updateAll(application: RecordDiscApplication, model: AppModel, state: WidgetPlaybackState) {
            val manager = AppWidgetManager.getInstance(application)
            val ids = manager.getAppWidgetIds(ComponentName(application, PlaybackWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val artwork = state.current?.let { model.artwork.get(it.location, 256)?.asAndroidBitmap() }
            val views = RemoteViews(application.packageName, R.layout.playback_widget).apply {
                setTextViewText(
                    R.id.widget_brand,
                    "${application.getString(R.string.app_name)} · ${application.getString(R.string.widget_now_playing)}",
                )
                setTextViewText(
                    R.id.widget_title,
                    state.current?.title?.takeIf(String::isNotBlank) ?: application.getString(R.string.widget_nothing_playing),
                )
                setTextViewText(
                    R.id.widget_artist,
                    state.current?.artist?.takeIf(String::isNotBlank) ?: application.getString(R.string.widget_unknown_artist),
                )
                if (artwork == null) {
                    setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                } else {
                    setImageViewBitmap(R.id.widget_artwork, artwork)
                }

                setImageViewResource(
                    R.id.widget_play_pause,
                    if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                )
                setContentDescription(
                    R.id.widget_play_pause,
                    application.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
                )
                setInt(
                    R.id.widget_shuffle,
                    "setColorFilter",
                    application.getColor(if (state.shuffle) R.color.widget_accent else R.color.widget_muted),
                )
                setContentDescription(
                    R.id.widget_shuffle,
                    application.getString(if (state.shuffle) R.string.widget_shuffle_on else R.string.widget_shuffle_off),
                )

                val repeatString = when (state.repeatMode) {
                    RepeatMode.Off -> R.string.widget_repeat_off
                    RepeatMode.All -> R.string.widget_repeat_all
                    RepeatMode.One -> R.string.widget_repeat_one
                }
                setInt(
                    R.id.widget_repeat,
                    "setColorFilter",
                    application.getColor(if (state.repeatMode == RepeatMode.Off) R.color.widget_muted else R.color.widget_accent),
                )
                setContentDescription(R.id.widget_repeat, application.getString(repeatString))
                setViewVisibility(R.id.widget_repeat_badge, if (state.repeatMode == RepeatMode.One) android.view.View.VISIBLE else android.view.View.GONE)

                bindAction(application, R.id.widget_previous, WidgetActions.PREVIOUS)
                bindAction(application, R.id.widget_shuffle, WidgetActions.TOGGLE_SHUFFLE)
                bindAction(application, R.id.widget_play_pause, WidgetActions.TOGGLE_PLAY)
                bindAction(application, R.id.widget_next, WidgetActions.NEXT)
                bindAction(application, R.id.widget_repeat, WidgetActions.TOGGLE_REPEAT)
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun RemoteViews.bindAction(context: Context, viewId: Int, action: String) {
            val intent = Intent(context, PlaybackWidgetActionReceiver::class.java).setAction(action)
            val pending = PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setOnClickPendingIntent(viewId, pending)
        }
    }
}

class PlaybackWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val application = context.applicationContext as RecordDiscApplication
        val pendingResult = goAsync()
        application.appScope.launch {
            try {
                val model = application.model
                if (withTimeoutOrNull(5_000) { model.initialized.first { it } } != true) {
                    Toast.makeText(context, "Playback controls aren't ready yet", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                application.startPlaybackWidgetUpdates()
                when (action) {
                    WidgetActions.PREVIOUS -> model.player.previous()
                    WidgetActions.NEXT -> model.player.next()
                    WidgetActions.TOGGLE_PLAY -> {
                        if (model.player.state.value.current == null) {
                            val tracks = withTimeoutOrNull(3_000) { model.tracks.first { it.isNotEmpty() } }
                            if (tracks == null) {
                                Toast.makeText(context, "No music is available to play yet", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                        }
                        model.togglePlay()
                    }
                    WidgetActions.TOGGLE_SHUFFLE -> model.setShuffle(!model.player.state.value.shuffle)
                    WidgetActions.TOGGLE_REPEAT -> model.setRepeatMode(
                        when (model.player.state.value.repeatMode) {
                            RepeatMode.Off -> RepeatMode.All
                            RepeatMode.All -> RepeatMode.One
                            RepeatMode.One -> RepeatMode.Off
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to handle playback widget action", failure)
                Toast.makeText(context, "Playback controls couldn't be updated", Toast.LENGTH_SHORT).show()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackWidget"
    }
}

private object WidgetActions {
    const val PREVIOUS = "dev.recorddisc.player.widget.PREVIOUS"
    const val NEXT = "dev.recorddisc.player.widget.NEXT"
    const val TOGGLE_PLAY = "dev.recorddisc.player.widget.TOGGLE_PLAY"
    const val TOGGLE_SHUFFLE = "dev.recorddisc.player.widget.TOGGLE_SHUFFLE"
    const val TOGGLE_REPEAT = "dev.recorddisc.player.widget.TOGGLE_REPEAT"
}
