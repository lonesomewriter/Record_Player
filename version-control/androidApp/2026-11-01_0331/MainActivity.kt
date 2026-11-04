package dev.recorddisc.player

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.ui.EditorActions
import dev.recorddisc.player.ui.PlaylistCoverFile
import dev.recorddisc.player.ui.PlaylistFileActions
import dev.recorddisc.player.ui.PhoneApp
import dev.recorddisc.player.ui.PhoneScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val model get() = (application as RecordDiscApplication).model

    /** Screens to open, e.g. Now Playing when the notification is tapped; each is handled once. */
    private val openRequests = Channel<PhoneScreen>(Channel.CONFLATED)

    /** Android's own folder picker; the app only ever sees the folder chosen here. */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            model.message("No folder chosen")
            return@registerForActivityResult
        }
        keepAccess(uri)
        model.scan(uri.toString())
    }

    /**
     * Folders granted before tag editing are read-only: the picker opens on the music folder again,
     * and choosing it keeps write access too, so song details can be saved.
     */
    private val allowEditing = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val folder = model.settings.value?.musicFolder ?: return@registerForActivityResult
        when {
            uri == null -> Unit
            uri.toString() != folder -> model.message("Choose your music folder (${model.platform.folderLabel(folder)}) to allow editing")
            keepAccess(uri) -> {
                model.accessChanged()
                model.message("Editing allowed")
            }
            else -> model.message("Android didn't allow changes to this folder")
        }
    }

    /** The song details editor's cover picker: Android's document picker, limited to JPEG and PNG. */
    private var coverPicked: ((ByteArray?) -> Unit)? = null
    private val pickCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val done = coverPicked ?: return@registerForActivityResult
        coverPicked = null
        if (uri == null) return@registerForActivityResult done(null)
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                // Stops one byte past the limit, so a too-large image is never read in full.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() <= TagRules.MAX_COVER_BYTES) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray().takeIf { it.size <= TagRules.MAX_COVER_BYTES }
            }
        }.getOrNull()
        if (bytes == null) model.message("That image is too large or can't be read")
        done(bytes)
    }

    private var playlistCoverPicked: ((PlaylistCoverFile?) -> Unit)? = null
    private val pickPlaylistCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val done = playlistCoverPicked ?: return@registerForActivityResult
        playlistCoverPicked = null
        if (uri == null) return@registerForActivityResult done(null)
        val bytes = readCover(uri)
        if (bytes == null) model.message("That image is too large or can't be read")
        done(bytes?.let { PlaylistCoverFile(coverName(uri), it) })
    }

    private var m3uExportPicked: ((String?) -> Unit)? = null
    private val saveM3u = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri: Uri? ->
        val done = m3uExportPicked
        m3uExportPicked = null
        done?.invoke(uri?.toString())
    }
    private var bundleExportPicked: ((String?) -> Unit)? = null
    private val saveBundle = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val done = bundleExportPicked
        bundleExportPicked = null
        done?.invoke(uri?.toString())
    }
    private var m3uImportPicked: ((String?) -> Unit)? = null
    private val openM3u = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val done = m3uImportPicked
        m3uImportPicked = null
        done?.invoke(uri?.toString())
    }
    private var bundleImportPicked: ((String?) -> Unit)? = null
    private val openBundle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val done = bundleImportPicked
        bundleImportPicked = null
        done?.invoke(uri?.toString())
    }

    private val playlistFiles = PlaylistFileActions(
        saveM3u = { name, done -> m3uExportPicked = done; saveM3u.launch(name) },
        saveBundle = { name, done -> bundleExportPicked = done; saveBundle.launch(name) },
        openM3u = { done -> m3uImportPicked = done; openM3u.launch(arrayOf("*/*")) },
        openBundle = { done -> bundleImportPicked = done; openBundle.launch(arrayOf("*/*")) },
        chooseCover = { done ->
            playlistCoverPicked = done
            pickPlaylistCover.launch(arrayOf("image/jpeg", "image/png"))
        },
    )

    private val editor = EditorActions(
        chooseCover = { done ->
            coverPicked = done
            pickCover.launch(arrayOf("image/jpeg", "image/png"))
        },
        allowEditing = { model.settings.value?.musicFolder?.let { allowEditing.launch(Uri.parse(it)) } },
    )

    /** Keeps read and write access after restarts (read only, if write wasn't granted). Returns true with write access. */
    private fun keepAccess(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Start with light bar icons (the default dark theme); PhoneApp switches them to match Settings → Theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // On a rotation the screen is restored instead; don't reopen Now Playing over it.
        if (savedInstanceState == null) handleOpen(intent)

        setContent {
            PhoneApp(
                model,
                onChooseFolder = { pickFolder.launch(null) },
                openRequests = openRequests.receiveAsFlow(),
                editor = editor,
                playlistActions = playlistFiles,
            )
        }
    }

    /** The app is already open (singleTop): the notification brings it back here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpen(intent)
    }

    private fun handleOpen(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_NOW_PLAYING) openRequests.trySend(PhoneScreen.NowPlaying)
    }

    private fun readCover(uri: Uri): ByteArray? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= TagRules.MAX_COVER_BYTES) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray().takeIf { it.size <= TagRules.MAX_COVER_BYTES }
        }
    }.getOrNull()

    private fun coverName(uri: Uri): String {
        val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return displayName?.takeIf { it.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png") }
            ?: "cover.${if (contentResolver.getType(uri) == "image/png") "png" else "jpg"}"
    }

    companion object {
        const val EXTRA_OPEN = "dev.recorddisc.player.OPEN"
        const val OPEN_NOW_PLAYING = "nowPlaying"
    }
}
