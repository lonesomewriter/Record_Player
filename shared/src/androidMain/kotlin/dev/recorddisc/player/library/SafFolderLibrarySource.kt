package dev.recorddisc.player.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds audio files in the folder the user granted through Android's folder picker (Storage
 * Access Framework), so no storage permission is needed. Tags are read with Android's built-in
 * [MediaMetadataRetriever] directly from the granted documents.
 */
class SafFolderLibrarySource(context: Context, private val treeUri: Uri) : LibrarySource {
    private val context = context.applicationContext

    override suspend fun listFiles(): List<FileEntry> = withContext(Dispatchers.IO) {
        val found = ArrayList<FileEntry>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri)))
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.removeFirst())
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        pending.addLast(id)
                    } else if (AudioFormats.isSupported(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        found += FileEntry(uri.toString(), name, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        found
    }

    override suspend fun readTags(entry: FileEntry): TrackTags = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(entry.location))
            fun text(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            TrackTags(
                title = text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                // FLAC/Ogg often report only a date (e.g. "20220101T000000.000Z"), not a year.
                year = parseYear(text(MediaMetadataRetriever.METADATA_KEY_YEAR)) ?: parseYear(text(MediaMetadataRetriever.METADATA_KEY_DATE)),
                trackNumber = parseLeadingNumber(text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = parseLeadingNumber(text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                hasArtwork = retriever.embeddedPicture != null,
            )
        } finally {
            retriever.release()
        }
    }
}

/** Opens the library database in the app's private storage. */
fun androidLibraryDatabase(context: Context): LibraryDatabase {
    val appContext = context.applicationContext
    return buildLibraryDatabase(
        Room.databaseBuilder<LibraryDatabase>(appContext, appContext.getDatabasePath("library.db").absolutePath)
    )
}
