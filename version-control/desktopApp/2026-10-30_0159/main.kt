package dev.recorddisc.player

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.DesktopLibraryPlatform
import dev.recorddisc.player.library.DesktopPaths
import dev.recorddisc.player.playback.DesktopPlayer
import dev.recorddisc.player.ui.DesktopApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player = DesktopPlayer()
    val platform = DesktopLibraryPlatform()
    val model = AppModel(DesktopPaths.libraryDatabase(), player, platform, scope)

    application {
        Window(
            onCloseRequest = {
                scope.cancel()
                player.release()
                exitApplication()
            },
            title = "Record Disc",
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
        ) {
            window.minimumSize = Dimension(720, 600)
            DesktopApp(model, chooseFolder = { chooseFolder(window, platform.suggestedFolder) })
        }
    }
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
