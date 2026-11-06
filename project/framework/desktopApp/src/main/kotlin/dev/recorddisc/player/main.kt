package dev.recorddisc.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.DesktopLibraryPlatform
import dev.recorddisc.player.app.OpenedAs
import dev.recorddisc.player.app.OpenedFile
import dev.recorddisc.player.app.SingleInstance
import dev.recorddisc.player.app.openArgumentsToPaths
import dev.recorddisc.player.library.DesktopPaths
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.playback.DesktopPlayer
import dev.recorddisc.player.playback.MuteAutoPause
import dev.recorddisc.player.playback.OutputMonitor
import dev.recorddisc.player.playback.SystemMuteMonitor
import dev.recorddisc.player.ui.DesktopApp
import dev.recorddisc.player.convert.ConvertFormat
import dev.recorddisc.player.convert.DesktopConvert
import dev.recorddisc.player.ui.ChosenFile
import dev.recorddisc.player.ui.EditorActions
import dev.recorddisc.player.ui.PlaylistCoverFile
import dev.recorddisc.player.ui.PlaylistFileActions
import dev.recorddisc.player.ui.SystemAccentMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
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

fun main(args: Array<String>) {
    // "Open with" from a file manager starts the app with the file's path. If a window is already
    // open, the path goes to it and this start ends here.
    val openPaths = openArgumentsToPaths(args.toList())
    val instance = SingleInstance.claim(openPaths) ?: return
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
    // Settings → Pause when muted / Resume when unmuted: the system output muted or at 0 (via pactl,
    // when present), or the app's own volume slider at 0.
    val systemMute = runCatching { SystemMuteMonitor() }.getOrNull()
    scope.launch {
        MuteAutoPause(
            muted = combine(systemMute?.muted ?: flowOf(false), player.state.map { it.volume <= 0f }) { system, slider -> system || slider },
            isPlaying = player.state.map { it.isPlaying },
            playingNow = { player.state.value.isPlaying },
            settings = { model.settings.value },
            pause = player::pause,
            play = player::play,
        ).run()
    }
    // Settings → Use system accent: the desktop's accent colour, when it shares one.
    val systemAccent = runCatching { SystemAccentMonitor() }.getOrNull()

    // Opened files play once the startup queue is set; the window then shows Home and comes forward.
    // With several files selected, the first one plays.
    val openedFiles = Channel<OpenedAs>(Channel.CONFLATED)
    val raiseWindow = Channel<Unit>(Channel.CONFLATED)
    fun open(paths: List<String>) {
        raiseWindow.trySend(Unit)
        val file = paths.firstOrNull()?.let(::File) ?: return
        scope.launch {
            try {
                openedFiles.trySend(model.openFile(OpenedFile(file.absolutePath, file.name, file.length())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.message("Couldn't open ${file.name}")
            }
        }
    }
    instance.listen(::open)
    if (openPaths.isNotEmpty()) open(openPaths)

    application {
        Window(
            onCloseRequest = {
                instance.close()
                scope.cancel()
                outputs?.close()
                systemMute?.close()
                systemAccent?.close()
                player.release()
                exitApplication()
            },
            title = "Record Player",
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
        ) {
            SideEffect { window.iconImage = appIcon }
            LaunchedEffect(Unit) {
                for (request in raiseWindow) {
                    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    window.toFront()
                    window.requestFocus()
                }
            }
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
                chooseConvertDestination = { name, format, done -> done(chooseConvertFile(window, name, format)) },
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
                systemAccent = systemAccent?.accent,
                openedFiles = openedFiles.receiveAsFlow(),
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

/** Convert to Audio → Choose…: a save dialog in ~/Music/Exports; asks before replacing a file. */
private fun chooseConvertFile(parent: java.awt.Component, name: String, format: ConvertFormat): ChosenFile? {
    val folder = DesktopConvert.exportsFolder().apply { mkdirs() }
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(folder).apply {
        dialogTitle = "Save the converted file"
        fileFilter = FileNameExtensionFilter("${format.label.substringBefore(" ·")} (*.${format.extension})", format.extension)
        selectedFile = File(folder, name)
    }
    while (true) {
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null
        val picked = chooser.selectedFile
        val file = if (picked.extension.equals(format.extension, ignoreCase = true)) picked else File(picked.parentFile, "${picked.name}.${format.extension}")
        val replace = !file.exists() || javax.swing.JOptionPane.showConfirmDialog(
            parent, "${file.name} already exists. Replace it?", "Replace file", javax.swing.JOptionPane.YES_NO_OPTION,
        ) == javax.swing.JOptionPane.YES_OPTION
        if (replace) return ChosenFile(file.absolutePath, DesktopConvert.shown(file))
    }
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
