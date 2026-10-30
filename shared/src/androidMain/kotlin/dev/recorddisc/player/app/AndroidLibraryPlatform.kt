package dev.recorddisc.player.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.SafFolderLibrarySource
import dev.recorddisc.player.library.SavedFile
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.tags.BackupThenOverwrite
import dev.recorddisc.player.tags.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.InputStream

/**
 * Android: the music folder is the tree URI granted through the system folder picker; cover art
 * comes from Android's own metadata reader and image decoder.
 */
class AndroidLibraryPlatform(context: Context) : LibraryPlatform {
    private val context = context.applicationContext

    override val suggestedFolder: String? = null

    private val saver = BackupThenOverwrite(SafDocuments(), File(context.filesDir, "tag-edit"))

    init {
        // jaudiotagger's Android mode: cover art without java.awt / ImageIO.
        TagOptionSingleton.getInstance().isAndroid = true
    }

    /** After a folder change, the old folder's access grant (read, and write if given) is handed back to Android. */
    override fun releaseFolder(folder: String) {
        for (flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(folder), flag)
                return
            } catch (e: SecurityException) {
                // Not held with these flags (or already released).
            }
        }
    }

    override val rewritesInPlace: Boolean get() = true

    override suspend fun editSupport(track: TrackEntity): EditSupport = withContext(Dispatchers.IO) {
        val canWrite = context.contentResolver.persistedUriPermissions.any {
            it.isWritePermission && track.location.startsWith(it.uri.toString() + "/")
        }
        when {
            !track.tagsReadable -> EditSupport.ReadOnly("Its tags couldn't be read, so saving could damage it")
            !canWrite -> EditSupport.NeedsWriteAccess
            else -> EditSupport.Editable
        }
    }

    override suspend fun saveTags(track: TrackEntity, edit: TagEdit): SavedFile = withContext(Dispatchers.IO) {
        val tags = saver.save(track.location, track.fileName, edit)
        val uri = Uri.parse(track.location)
        val (size, modified) = query(uri)
        SavedFile(size ?: track.sizeBytes, modified ?: System.currentTimeMillis(), tags)
    }

    override suspend fun recoverPendingSaves(): String? = withContext(Dispatchers.IO) { saver.recoverPending() }

    /** A document's size and last-modified time. */
    private fun query(uri: Uri): Pair<Long?, Long?> =
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null to null
            else (if (c.isNull(0)) null else c.getLong(0)) to (if (c.isNull(1)) null else c.getLong(1))
        } ?: (null to null)

    /** Song files as SAF documents: read, and overwritten in place (Android has no atomic swap for them). */
    private inner class SafDocuments : DocumentStore {
        override fun size(uri: String): Long = query(Uri.parse(uri)).first ?: throw java.io.IOException("Can't read the song file")

        override fun openRead(uri: String): InputStream =
            context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw java.io.IOException("Can't read the song file")

        override fun overwrite(uri: String, source: File) {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "rwt") ?: throw java.io.IOException("Can't write the song file")
            // Closing the stream closes the descriptor too.
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
                out.flush()
                out.fd.sync()
            }
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
