package dev.recorddisc.player

import android.app.Application
import dev.recorddisc.player.app.AndroidLibraryPlatform
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.library.androidLibraryDatabase
import dev.recorddisc.player.playback.AndroidPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app's state for as long as the process lives, so it survives screen rotation. */
class RecordDiscApplication : Application() {
    val model: AppModel by lazy {
        AppModel(
            androidLibraryDatabase(this),
            AndroidPlayer(this),
            AndroidLibraryPlatform(this),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
