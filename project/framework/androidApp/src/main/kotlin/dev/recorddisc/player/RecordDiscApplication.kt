package dev.recorddisc.player

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import dev.recorddisc.player.app.AndroidLibraryPlatform
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.convert.AndroidConvertPlatform
import dev.recorddisc.player.library.androidLibraryDatabase
import dev.recorddisc.player.playback.AndroidPlayer
import dev.recorddisc.player.playback.PlaybackWidgetProvider
import dev.recorddisc.player.playback.WidgetPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Holds the app's state for as long as the process lives, so it survives screen rotation. */
class RecordDiscApplication : Application() {
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var widgetUpdates: Job? = null

    val model: AppModel by lazy {
        AppModel(
            androidLibraryDatabase(this),
            AndroidPlayer(this),
            // The shared platform plus Convert to Audio (its converter needs the app's Media3).
            AndroidConvertPlatform(this, AndroidLibraryPlatform(this)),
            appScope,
        )
    }

    fun startPlaybackWidgetUpdates() {
        val component = ComponentName(this, PlaybackWidgetProvider::class.java)
        if (AppWidgetManager.getInstance(this).getAppWidgetIds(component).isEmpty()) return
        if (widgetUpdates?.isActive == true) return
        widgetUpdates = appScope.launch {
            model.player.state
                .map { WidgetPlaybackState(it.current, it.isPlaying, it.shuffle, it.repeatMode) }
                .distinctUntilChanged()
                .collectLatest { PlaybackWidgetProvider.updateAll(this@RecordDiscApplication, model, it) }
        }
    }

    fun refreshPlaybackWidgets() {
        appScope.launch {
            val state = model.player.state.value
            PlaybackWidgetProvider.updateAll(
                this@RecordDiscApplication,
                model,
                WidgetPlaybackState(state.current, state.isPlaying, state.shuffle, state.repeatMode),
            )
        }
    }

    fun stopPlaybackWidgetUpdates() {
        widgetUpdates?.cancel()
        widgetUpdates = null
    }
}
