package dev.recorddisc.player

import androidx.compose.runtime.SideEffect
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
import dev.recorddisc.player.ui.PlaylistCoverFile
import dev.recorddisc.player.ui.PlaylistFileActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Image
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player = DesktopPlayer()
    val platform = DesktopLibraryPlatform()
    val model = AppModel(DesktopPaths.libraryDatabase(), player, platform, scope)
    val appIcon = loadAppIcon()
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
            title = "Record Player",
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
        ) {
            SideEffect { window.iconImage = appIcon }
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
            val playlistFiles = PlaylistFileActions(
                saveM3u = { name, done -> done(choosePlaylistSave(window, name, "M3U8", "m3u8", model::message)) },
                saveBundle = { name, done -> done(choosePlaylistSave(window, name, "Record Disc playlist", "rdplaylist", model::message)) },
                openM3u = { done -> done(choosePlaylistOpen(window, "M3U playlists", "m3u", "m3u8")) },
                openBundle = { done -> done(choosePlaylistOpen(window, "Record Disc playlists", "rdplaylist")) },
                chooseCover = { done ->
                    val file = chooseImage(window)
                    when {
                        file == null -> done(null)
                        file.length() > TagRules.MAX_COVER_BYTES -> { model.message("That image is too large (the limit is 10 MB)"); done(null) }
                        else -> try {
                            done(PlaylistCoverFile(file.name, file.readBytes()))
                        } catch (error: IOException) {
                            model.message("Couldn't read that image: ${error.message ?: "file access failed"}")
                            done(null)
                        }
                    }

                },
            )
            DesktopApp(
                model,
                chooseFolder = { chooseFolder(window, platform.suggestedFolder) },
                appVersion = version,
                editor = editor,
                playlistActions = playlistFiles,
            )
        }
    }
}

private fun loadAppIcon(): Image {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/record-player.png")) {
        "Missing desktop app icon resource"
    }
    return stream.use { checkNotNull(ImageIO.read(it)) { "Couldn't decode desktop app icon resource" } }
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

private fun choosePlaylistSave(
    parent: java.awt.Component,
    name: String,
    description: String,
    extension: String,
    onError: (String) -> Unit,
): String? {
    val directory = File(File(System.getProperty("user.home"), "Music"), "Playlists")
    if (!directory.exists() && !directory.mkdirs()) {
        onError("Couldn't create the playlist export folder.")
        return null
    }
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(directory).apply {
        dialogTitle = "Export $description"
        fileFilter = FileNameExtensionFilter("$description (*.$extension)", extension)
        isAcceptAllFileFilterUsed = false
        selectedFile = File(directory, name)
    }
    if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    val destination = if (selected.extension.equals(extension, ignoreCase = true)) selected else File(selected.parentFile, "${selected.name}.$extension")
    return destination.absolutePath
}

private fun choosePlaylistOpen(parent: java.awt.Component, description: String, vararg extensions: String): String? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(System.getProperty("user.home")).apply {
        dialogTitle = "Import $description"
        fileFilter = FileNameExtensionFilter(description, *extensions)
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}
