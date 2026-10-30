package dev.recorddisc.player

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.DesktopLibraryPlatform
import dev.recorddisc.player.library.DesktopPaths
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.playback.DesktopPlayer
import dev.recorddisc.player.playback.OutputMonitor
import dev.recorddisc.player.ui.DesktopApp
import dev.recorddisc.player.ui.EditorActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.Dimension
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player = DesktopPlayer()
    val platform = DesktopLibraryPlatform()
    val model = AppModel(DesktopPaths.libraryDatabase(), player, platform, scope)
    // Settings → Gapless playback and Pause on headphone unplug.
    scope.launch { model.settings.filterNotNull().map { it.gapless }.distinctUntilChanged().collect(player::setGapless) }
    val outputs = runCatching {
        OutputMonitor { if (model.settings.value?.pauseOnUnplug == true && player.state.value.isPlaying) player.pause() }
    }.getOrNull()

    application {
        Window(
            onCloseRequest = {
                scope.cancel()
                outputs?.close()
                player.release()
                exitApplication()
            },
            title = "Record Disc",
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
        ) {
            window.minimumSize = Dimension(720, 600)
            // Set by the installed package (jpackage); absent when run from Gradle.
            val version = System.getProperty("jpackage.app-version") ?: "dev"
            val editor = EditorActions(chooseCover = { done ->
                val file = chooseImage(window)
                when {
                    file == null -> done(null)
                    file.length() > TagRules.MAX_COVER_BYTES -> { model.message("That image is too large (the limit is 10 MB)"); done(null) }
                    else -> done(runCatching { file.readBytes() }.getOrNull())
                }
            })
            DesktopApp(model, chooseFolder = { chooseFolder(window, platform.suggestedFolder) }, appVersion = version, editor = editor)
        }
    }
}

/** The cover image dialog: JPEG and PNG files. */
private fun chooseImage(parent: java.awt.Component): java.io.File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(System.getProperty("user.home")).apply {
        dialogTitle = "Choose a cover image"
        fileFilter = FileNameExtensionFilter("Images (JPEG, PNG)", "jpg", "jpeg", "png")
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** The folder dialog, in the desktop's own look where Java supports it (GTK). */
private fun chooseFolder(parent: java.awt.Component, start: String?): String? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(start).apply {
        dialogTitle = "Choose your music folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showDialog(parent, "Select") == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}
