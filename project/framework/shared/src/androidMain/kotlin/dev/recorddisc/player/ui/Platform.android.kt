package dev.recorddisc.player.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun systemPrefersDark(): Boolean = isSystemInDarkTheme()

@Composable
actual fun SystemBarsEffect(light: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(activity, light) {
        val style = if (light) {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        }
        activity.enableEdgeToEdge(style, style)
    }
}

actual fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
}

actual fun decodeImage(bytes: ByteArray, maxSizePx: Int): ImageBitmap? {
    val (w, h) = imageSize(bytes) ?: return null
    var sample = 1
    while (maxOf(w, h) / (sample * 2) >= maxSizePx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * Shows the song's folder. The folder is a document URI from the folder picker, so it is sent as a
 * folder (not "any file", which file managers try to open as a file and fail). Tried in order:
 * Android's own Files app, any app that opens folders, then the system file browser at that folder.
 * If none starts, a toast names the folder.
 */
@Composable
actual fun viewFileLocation(location: String, folder: String?) {
    val context = LocalContext.current
    val target = Uri.parse(folder ?: location)
    if (folderViewIntents(context, target, isFolder = folder != null).none { context.tryStart(it) }) {
        Toast.makeText(context, "Couldn't open the folder: ${folderLabel(context, target)}", Toast.LENGTH_LONG).show()
    }
}

private val SYSTEM_FILES_APPS = listOf("com.google.android.documentsui", "com.android.documentsui")

private fun folderViewIntents(context: Context, target: Uri, isFolder: Boolean): List<Intent> {
    val intents = ArrayList<Intent>()
    if (isFolder && DocumentsContract.isDocumentUri(context, target)) {
        // The plain (non-tree) address: the Files app reads it with its own access, so no grant is needed.
        val plain = DocumentsContract.buildDocumentUri(target.authority, DocumentsContract.getDocumentId(target))
        fun view() = Intent(Intent.ACTION_VIEW).setDataAndType(plain, DocumentsContract.Document.MIME_TYPE_DIR)
        SYSTEM_FILES_APPS.forEach { intents += view().setPackage(it) }
        intents += view()
    }
    // Last resort: the system file browser (the folder picker's screen), opened at the folder.
    intents += Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "audio/*"
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, target)
    }
    return intents
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** "Music/Albums" from a document ID like "primary:Music/Albums"; the URI itself when it isn't one. */
private fun folderLabel(context: Context, target: Uri): String =
    runCatching { DocumentsContract.getDocumentId(target).substringAfter(':').ifEmpty { "Internal storage" } }
        .getOrNull()?.takeIf { DocumentsContract.isDocumentUri(context, target) } ?: target.toString()
