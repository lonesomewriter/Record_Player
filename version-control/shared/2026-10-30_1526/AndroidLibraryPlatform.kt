package dev.recorddisc.player.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.SafFolderLibrarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android: the music folder is the tree URI granted through the system folder picker; cover art
 * comes from Android's own metadata reader and image decoder.
 */
class AndroidLibraryPlatform(context: Context) : LibraryPlatform {
    private val context = context.applicationContext

    override val suggestedFolder: String? = null

    /** After a folder change, the old folder's access grant is handed back to Android. */
    override fun releaseFolder(folder: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already released.
        }
    }

    override fun sourceFor(folder: String): LibrarySource = SafFolderLibrarySource(context, Uri.parse(folder))

    /** "primary:Music/Rock" → "Rock"; the storage root shows as "Internal storage". */
    override fun folderLabel(folder: String): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder)) }.getOrNull() ?: return folder
        val path = id.substringAfter(':', "")
        return if (path.isEmpty()) "Internal storage" else path.substringAfterLast('/')
    }

    override suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = MediaMetadataRetriever().run {
            try {
                if (location.startsWith("content://")) setDataSource(context, Uri.parse(location)) else setDataSource(location)
                embeddedPicture
            } finally {
                release()
            }
        } ?: return@withContext null
        if (bytes.size > MAX_ARTWORK_BYTES) return@withContext null
        // Read the size first, then decode at the smallest power-of-two scale that is still large enough.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSizePx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private companion object {
        const val MAX_ARTWORK_BYTES = 20 * 1024 * 1024
    }
}
