package dev.recorddisc.player.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.AudioFormats
import dev.recorddisc.player.library.ImportedPlaylistSong
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.Lrc
import dev.recorddisc.player.library.Lyrics
import dev.recorddisc.player.library.M3u
import dev.recorddisc.player.library.PlaylistImportFile
import dev.recorddisc.player.library.PlaylistBundlePreview
import dev.recorddisc.player.library.PlaylistBundlePreviewSong
import dev.recorddisc.player.library.PlaylistTextFile
import dev.recorddisc.player.library.SafFolderLibrarySource
import dev.recorddisc.player.library.SavedFile
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagSaveException
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.tags.BackupThenOverwrite
import dev.recorddisc.player.tags.DocumentStore
import dev.recorddisc.player.tags.PlaylistBundle
import dev.recorddisc.player.tags.PlaylistBundleSong
import dev.recorddisc.player.tags.TagReader
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
    override fun currentTimeMs(): Long = System.currentTimeMillis()

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

    /** True when the music folder holding [track] was granted with write access. */
    private fun canWrite(track: TrackEntity) = context.contentResolver.persistedUriPermissions.any {
        it.isWritePermission && track.location.startsWith(it.uri.toString() + "/")
    }

    override suspend fun editSupport(track: TrackEntity): EditSupport = withContext(Dispatchers.IO) {
        val canWrite = canWrite(track)
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

    override suspend fun readLyrics(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) {
        track.lyricsLocation?.let { lrc ->
            val bytes = runCatching { context.contentResolver.openInputStream(Uri.parse(lrc))?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) return@withContext Lrc.parse(Lrc.decode(bytes))
        }
        embeddedLyrics(track)?.let { Lrc.parse(it, embedded = true) }
    }

    /**
     * Lyrics inside the song file. jaudiotagger needs a file, so the document is read through its
     * open descriptor (/proc/self/fd), without copying it; if that path can't be read, a copy in
     * the app's cache is used instead (songs up to 200 MB).
     */
    private fun embeddedLyrics(track: TrackEntity): String? {
        val uri = Uri.parse(track.location)
        val ext = AudioFormats.extensionOf(track.fileName)
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> return TagReader.readLyrics(File("/proc/self/fd/${pfd.fd}"), ext) }
        }
        if (track.sizeBytes > MAX_LYRICS_COPY_BYTES) return null
        val copy = File(context.cacheDir, "lyrics-read.$ext")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: return null
            runCatching { TagReader.readLyrics(copy, ext) }.getOrNull()
        } finally {
            copy.delete()
        }
    }

    override suspend fun lyricsSupport(track: TrackEntity): EditSupport = withContext(Dispatchers.IO) {
        if (canWrite(track)) EditSupport.Editable else EditSupport.NeedsWriteAccess
    }

    override suspend fun saveLyrics(track: TrackEntity, text: String?): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val existing = track.lyricsLocation?.let(Uri::parse)
        if (text == null) {
            if (existing != null && !DocumentsContract.deleteDocument(resolver, existing)) throw TagSaveException("Couldn't remove the lyrics file")
            return@withContext null
        }
        val bytes = text.encodeToByteArray()
        if (existing != null) {
            // A small text file: keep the old bytes in memory, overwrite, check, and put them back if needed.
            val old = resolver.openInputStream(existing)?.use { it.readBytes() } ?: throw TagSaveException("Couldn't read the lyrics file")
            writeDocument(existing, bytes)
            if (!(resolver.openInputStream(existing)?.use { it.readBytes() } ?: ByteArray(0)).contentEquals(bytes)) {
                writeDocument(existing, old)
                throw TagSaveException("The lyrics didn't save correctly; the old file was kept")
            }
            return@withContext existing.toString()
        }
        val folder = track.folder?.let(Uri::parse) ?: throw TagSaveException("Rescan the library, then try again")
        val name = track.fileName.substringBeforeLast('.') + ".lrc"
        // octet-stream so the provider keeps the name as given (text/plain could add ".txt").
        val created = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name)
            ?: throw TagSaveException("Couldn't create $name in the music folder")
        val actual = resolver.query(created, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (actual != name) {
            DocumentsContract.deleteDocument(resolver, created)
            throw TagSaveException("Android would have named the lyrics file \"$actual\" instead of \"$name\", so it wasn't kept")
        }
        writeDocument(created, bytes)
        created.toString()
    }

    override suspend fun writeExport(destination: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(destination)
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rwt")
            ?: throw java.io.IOException("Couldn't open the export destination")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    override suspend fun writeExportFile(destination: String, sourcePath: String) = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        require(source.isFile) { "Export file can't be read" }
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(destination), "rwt")
            ?: throw java.io.IOException("Couldn't open the export destination")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
            output.flush()
            output.fd.sync()
        }
    }

    override suspend fun readPlaylistText(location: String): PlaylistTextFile = withContext(Dispatchers.IO) {
        val uri = Uri.parse(location)
        val name = context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist.m3u8"
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't read the playlist file")
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= M3u.MAX_IMPORT_BYTES) { "Playlist file is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        PlaylistTextFile(name, bytes.decodeToString(throwOnInvalidSequence = true))
    }

    override suspend fun writePlaylistBundle(
        destination: String,
        name: String,
        songs: List<TrackEntity>,
        customCoverPath: String?,
        fallbackCover: TrackEntity?,
        includeLyrics: Boolean,
    ) = withContext(Dispatchers.IO) {
        val transferRoot = playlistTransferDirectory().apply {
            check(isDirectory || mkdirs()) { "Couldn't prepare playlist export storage" }
        }
        val temporaryDirectory = java.nio.file.Files.createTempDirectory(transferRoot.toPath(), "export-").toFile()
        try {
            val bundleSongs = songs.mapIndexed { index, track ->
                val extension = AudioFormats.extensionOf(track.fileName)
                val audioFile = File(temporaryDirectory, "song-$index.$extension")
                copyDocumentToFile(track.location, audioFile, PlaylistBundle.MAX_ENTRY_BYTES)
                val lyricsFile = track.lyricsLocation?.takeIf { includeLyrics }?.let { location ->
                    File(temporaryDirectory, "lyrics-$index.lrc").also {
                        copyDocumentToFile(location, it, PlaylistBundle.MAX_ENTRY_BYTES)
                    }
                }
                PlaylistBundleSong(audioFile, track.displayTitle, track.artist, track.durationMs, lyricsFile)
            }
            val coverFile = customCoverPath?.let(::File) ?: fallbackCover?.let { track ->
                val bytes = readEmbeddedArtwork(track.location) ?: return@let null
                val extension = imageExtension(bytes) ?: throw java.io.IOException("The first song's cover isn't JPEG or PNG")
                require(bytes.size <= TagRules.MAX_COVER_BYTES) { "Playlist cover is too large to export" }
                File(temporaryDirectory, "cover.$extension").apply { writeBytes(bytes) }
            }
            if (customCoverPath != null) require(coverFile?.isFile == true) { "Playlist cover can't be read" }
            val bundleFile = File(temporaryDirectory, "playlist.rdplaylist")
            FileOutputStream(bundleFile).use { output ->
                PlaylistBundle.write(output, name, bundleSongs, coverFile, includeLyrics)
                output.flush()
                output.fd.sync()
            }
            writeExportFile(destination, bundleFile.absolutePath)
        } finally {
            if (temporaryDirectory.exists() && !temporaryDirectory.deleteRecursively()) {
                throw java.io.IOException("Couldn't remove temporary playlist export files")
            }
        }
    }

    override suspend fun readPlaylistBundle(location: String, library: List<TrackEntity>): PlaylistBundlePreview =
        withContext(Dispatchers.IO) {
            val root = playlistImportDirectory().apply { check(isDirectory || mkdirs()) { "Couldn't prepare playlist import storage" } }
            val input = context.contentResolver.openInputStream(Uri.parse(location))
                ?: throw java.io.IOException("Couldn't read the playlist bundle")
            val contents = input.use { PlaylistBundle.read(it, root) }
            try {
                val inspection = PlaylistBundle.inspect(contents, library)
                val coverBytes = contents.coverFile?.readBytes()
                PlaylistBundlePreview(
                    name = contents.name,
                    songs = inspection.map { item ->
                        PlaylistBundlePreviewSong(
                            sourcePath = item.song.file.absolutePath,
                            fileName = item.song.file.name.substringAfter(" - "),
                            lyricsPath = item.song.lyricsFile?.absolutePath,
                            tags = item.tags,
                            existingTrack = item.existingTrack,
                            warning = item.warning,
                        )
                    },
                    coverBytes = coverBytes,
                    coverExtension = contents.coverFile?.extension?.lowercase(),
                    temporaryDirectory = contents.temporaryDirectory.absolutePath,
                )
            } catch (error: Exception) {
                if (contents.temporaryDirectory.exists() && !contents.temporaryDirectory.deleteRecursively()) {
                    error.addSuppressed(java.io.IOException("Couldn't remove temporary playlist import files"))
                }
                throw error
            }
        }

    override suspend fun cleanupPlaylistBundlePreview(preview: PlaylistBundlePreview) = withContext(Dispatchers.IO) {
        cleanupPlaylistImportDirectory(preview.temporaryDirectory)
    }

    override suspend fun importSongs(
        playlistName: String,
        musicFolder: String,
        files: List<PlaylistImportFile>,
    ): List<ImportedPlaylistSong> = withContext(Dispatchers.IO) {
        require(playlistName.isNotBlank()) { "Playlist name can't be empty" }
        if (!canWriteFolder(musicFolder)) throw TagSaveException("Allow editing to add imported songs")
        val tree = Uri.parse(musicFolder)
        val treeRoot = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val imported = findOrCreateDirectory(treeRoot, IMPORTED_DIR)
        val safeName = safeDirectoryName(playlistName)
        val existingName = childDocuments(imported).firstOrNull { it.first == safeName }
        val targetName = if (existingName == null || existingName.second == Document.MIME_TYPE_DIR) {
            safeName
        } else {
            uniqueChildName(imported, safeName, isDirectory = true)
        }
        val target = findOrCreateDirectory(imported, targetName)
        val created = ArrayList<Uri>()
        try {
            val usedNames = childNames(target).mapTo(HashSet()) { it.lowercase() }
            val results = ArrayList<ImportedPlaylistSong>(files.size)
            for (file in files) {
                require(AudioFormats.isSupported(file.fileName)) { "Unsupported audio file: ${file.fileName}" }
                val source = File(file.sourcePath)
                require(source.isFile) { "Imported song can't be read: ${file.fileName}" }
                val audioName = uniqueChildName(target, safeFileName(file.fileName), usedNames)
                val audioUri = createImportedDocument(target, audioName)
                created += audioUri
                copyIntoDocument(source, audioUri)
                val actualAudioName = documentName(audioUri) ?: audioName
                usedNames += actualAudioName.lowercase()
                val lyricsLocation = file.lyricsPath?.let { lyricsPath ->
                    val lyrics = File(lyricsPath)
                    require(lyrics.isFile && lyrics.extension.equals("lrc", ignoreCase = true)) {
                        "Imported lyrics can't be read"
                    }
                    val lyricsName = uniqueChildName(target, actualAudioName.substringBeforeLast('.') + ".lrc", usedNames)
                    val lyricsUri = createImportedDocument(target, lyricsName)
                    created += lyricsUri
                    copyIntoDocument(lyrics, lyricsUri)
                    (documentName(lyricsUri) ?: lyricsName).also { usedNames += it.lowercase() }
                    lyricsUri.toString()
                }
                results += ImportedPlaylistSong(audioUri.toString(), lyricsLocation)
            }
            results
        } catch (error: Exception) {
            created.forEach { uri ->
                if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                    error.addSuppressed(java.io.IOException("Couldn't remove a partially imported file"))
                }
            }
            throw error
        }
    }

    override fun relativePlaylistPath(track: TrackEntity, musicFolder: String): String {
        val tree = Uri.parse(musicFolder)
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return track.location
        val documentId = runCatching { DocumentsContract.getDocumentId(Uri.parse(track.location)) }.getOrNull() ?: return track.location
        val prefix = "$treeId/"
        return documentId.removePrefix(prefix).takeIf { documentId.startsWith(prefix) } ?: track.location
    }

    override suspend fun resolvePlaylistPath(path: String, playlistLocation: String?, musicFolder: String): String? =
        withContext(Dispatchers.IO) {
            if (path.startsWith("content://")) return@withContext path
            val tree = Uri.parse(musicFolder)
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return@withContext null
            val relative = path.replace('\\', '/')
            val sourceBase = playlistLocation?.let { location ->
                runCatching { DocumentsContract.getDocumentId(Uri.parse(location)) }.getOrNull()
                    ?.substringBeforeLast('/', treeId)
            }
            val candidates = listOfNotNull(sourceBase, treeId).distinct().mapNotNull { base ->
                resolveDocumentId(treeId, base, relative)?.let { documentId ->
                    DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString()
                }
            }
            candidates.firstOrNull { location ->
                context.contentResolver.query(Uri.parse(location), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                    it.moveToFirst()
                } == true
            }
        }

    override suspend fun savePlaylistCover(playlistId: Long, extension: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(extension in PLAYLIST_COVER_EXTENSIONS && bytes.isNotEmpty() && bytes.size <= TagRules.MAX_COVER_BYTES) {
            "Playlist cover must be a JPEG or PNG image, 10 MB or smaller"
        }
        val directory = playlistCoverDirectory().apply {
            check(isDirectory || mkdirs()) { "Couldn't create playlist cover storage" }
        }
        val target = File(directory, "$playlistId.$extension")
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            target.absolutePath
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    override suspend fun deletePlaylistCover(path: String) = withContext(Dispatchers.IO) {
        val file = playlistCoverFile(path)
        if (file.exists() && !file.delete()) throw java.io.IOException("Couldn't remove the playlist cover")
    }

    override suspend fun loadPlaylistCover(path: String, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = playlistCoverFile(path)
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSizePx) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private fun writeDocument(uri: Uri, bytes: ByteArray) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rwt") ?: throw TagSaveException("Couldn't write the lyrics file")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    private fun copyDocumentToFile(location: String, destination: File, maxBytes: Long) {
        val input = context.contentResolver.openInputStream(Uri.parse(location))
            ?: throw java.io.IOException("Couldn't read ${Uri.parse(location).lastPathSegment ?: "a song"}")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maxBytes) { "Playlist file is too large" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun readEmbeddedArtwork(location: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (location.startsWith("content://")) retriever.setDataSource(context, Uri.parse(location)) else retriever.setDataSource(location)
            retriever.embeddedPicture?.takeIf { it.size <= MAX_ARTWORK_BYTES }
        } finally {
            retriever.release()
        }
    }

    private fun canWriteFolder(folder: String): Boolean {
        val location = Uri.parse(folder).toString()
        return context.contentResolver.persistedUriPermissions.any {
            it.isWritePermission && (location == it.uri.toString() || location.startsWith(it.uri.toString() + "/"))
        }
    }

    private fun findOrCreateDirectory(parent: Uri, name: String): Uri {
        val existing = childDocuments(parent).firstOrNull { (childName, mimeType, _) ->
            childName == name && mimeType == Document.MIME_TYPE_DIR
        }?.third
        if (existing != null) return existing
        return DocumentsContract.createDocument(context.contentResolver, parent, Document.MIME_TYPE_DIR, name)
            ?: throw java.io.IOException("Couldn't create the $name folder")
    }

    private fun childDocuments(parent: Uri): List<Triple<String, String, Uri>> {
        val documentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId)
        return context.contentResolver.query(
            childrenUri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2) ?: continue
                    add(Triple(name, mimeType, DocumentsContract.buildDocumentUriUsingTree(parent, childId)))
                }
            }
        } ?: throw java.io.IOException("Couldn't list the music folder")
    }

    private fun childNames(parent: Uri): Set<String> = childDocuments(parent).mapTo(HashSet()) { it.first }

    private fun uniqueChildName(parent: Uri, name: String, used: Set<String> = childNames(parent), isDirectory: Boolean = false): String {
        val clean = if (isDirectory) safeDirectoryName(name) else safeFileName(name)
        val usedLower = used.mapTo(HashSet()) { it.lowercase() }
        if (clean.lowercase() !in usedLower) return clean
        val base = if (isDirectory) clean else clean.substringBeforeLast('.', clean)
        val extension = if (isDirectory) "" else clean.substringAfterLast('.', "").takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
        var suffix = 2
        var candidate: String
        do {
            candidate = "$base ($suffix)$extension"
            suffix++
        } while (candidate.lowercase() in usedLower)
        return candidate
    }

    private fun createImportedDocument(parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
            ?: throw java.io.IOException("Couldn't create $name in the Imported folder")

    private fun documentName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun copyIntoDocument(source: File, target: Uri) {
        val descriptor = context.contentResolver.openFileDescriptor(target, "rwt")
            ?: throw java.io.IOException("Couldn't write an imported file")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
            output.flush()
            output.fd.sync()
        }
    }

    private fun safeDirectoryName(name: String): String =
        name.replace(Regex("[\\\\/\\u0000-\\u001F]"), "_").replace("..", "_").trim()
            .takeIf(String::isNotEmpty) ?: "Playlist"

    private fun safeFileName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .replace("..", "_")
            .trim()
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Invalid imported file name" }
        return clean
    }

    private fun resolveDocumentId(treeId: String, baseId: String, relativePath: String): String? {
        if (relativePath.isBlank() || relativePath.startsWith('/') || '\u0000' in relativePath) return null
        val treeVolume = treeId.substringBefore(':')
        val baseVolume = baseId.substringBefore(':')
        if (treeVolume != baseVolume) return null
        val rootParts = treeId.substringAfter(':').split('/').filter(String::isNotEmpty)
        val baseParts = baseId.substringAfter(':').split('/').filter(String::isNotEmpty).toMutableList()
        if (baseParts.take(rootParts.size) != rootParts || baseParts.size < rootParts.size) return null
        relativePath.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (baseParts.size > rootParts.size) baseParts.removeAt(baseParts.lastIndex) else return null
                else -> baseParts += segment
            }
        }
        return "$treeVolume:${baseParts.joinToString("/")}"
    }

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

    private fun playlistCoverDirectory() = File(context.filesDir, "playlist-covers")

    private fun playlistTransferDirectory() = File(context.cacheDir, "playlist-transfer")

    private fun playlistImportDirectory() = File(context.cacheDir, "playlist-imports")

    private fun cleanupPlaylistImportDirectory(path: String) {
        val root = playlistImportDirectory().canonicalFile
        val directory = File(path).canonicalFile
        require(directory.parentFile == root && directory.name.startsWith("rdplaylist-")) {
            "Invalid temporary playlist import directory"
        }
        if (directory.exists() && !directory.deleteRecursively()) throw java.io.IOException("Couldn't remove temporary playlist import files")
    }

    private fun imageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
        else -> null
    }

    private fun playlistCoverFile(path: String): File {
        val root = playlistCoverDirectory().canonicalFile
        val file = File(path).canonicalFile
        require(file.parentFile == root && file.name.matches(PLAYLIST_COVER_NAME)) { "Invalid playlist cover path" }
        return file
    }

    private companion object {
        const val MAX_ARTWORK_BYTES = 20 * 1024 * 1024
        const val MAX_LYRICS_COPY_BYTES = 200L * 1024 * 1024
        val PLAYLIST_COVER_EXTENSIONS = setOf("jpg", "jpeg", "png")
        val PLAYLIST_COVER_NAME = Regex("""\d+\.(jpg|jpeg|png)""")
        const val IMPORTED_DIR = "Imported"
    }
}
