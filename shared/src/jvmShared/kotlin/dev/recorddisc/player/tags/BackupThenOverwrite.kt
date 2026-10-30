package dev.recorddisc.player.tags

import dev.recorddisc.player.library.AudioFormats
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagSaveException
import dev.recorddisc.player.library.TrackTags
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/** A song file that can only be read and overwritten in place (an Android document URI). */
interface DocumentStore {
    fun size(uri: String): Long
    fun openRead(uri: String): InputStream
    /** Replaces the document's bytes with [source]'s, in place, flushed to storage. */
    fun overwrite(uri: String, source: File)
}

/**
 * Saves tags into a file that can't be swapped atomically (Android's Storage Access Framework):
 * 1. copy the song into [dir] and apply the edit there (checked by [TagWriter]);
 * 2. copy the original to a backup and write a "pending" note;
 * 3. overwrite the song in place, then check its bytes;
 * 4. delete the backup and the note.
 * If the app dies during step 3, [recoverPending] (called at the next launch) finishes the job:
 * it keeps the new file if it was written completely and puts the backup back otherwise.
 * Needs free space in [dir] for about two copies of the song.
 */
class BackupThenOverwrite(
    private val store: DocumentStore,
    private val dir: File,
    private val freeBytes: () -> Long = { dir.usableSpace },
) {
    private val work = File(dir, "work")
    private val backups = File(dir, "backup")
    private val note = File(dir, "pending.properties")

    /** Saves [edit] into the document [uri] (named [fileName], for its format) and returns the tags read back from it. */
    fun save(uri: String, fileName: String, edit: TagEdit): TrackTags {
        recoverPending()
        // Made first: a folder that doesn't exist yet reports no free space at all.
        work.mkdirs()
        backups.mkdirs()
        val size = store.size(uri)
        if (freeBytes() < size * 2 + FREE_MARGIN) throw TagSaveException("Not enough free space to save safely")
        val ext = AudioFormats.extensionOf(fileName)
        val newFile = File(work, "song.$ext")
        val backup = File(backups, "song.$ext")
        try {
            copy(uri, newFile)
            val originalHash = sha256(newFile)
            TagWriter.apply(newFile, edit)
            val tags = TagReader.read(newFile)
            val newHash = sha256(newFile)

            copy(uri, backup)
            if (sha256(backup) != originalHash) throw TagSaveException("The song changed while saving; try again")
            writeNote(uri, backup, originalHash, newHash)

            try {
                store.overwrite(uri, newFile)
            } catch (e: Exception) {
                restore(uri, backup, originalHash)
                throw TagSaveException("Couldn't write the song file; the original was kept", e)
            }
            if (store.openRead(uri).use(::sha256) != newHash) {
                restore(uri, backup, originalHash)
                throw TagSaveException("The saved file didn't check out; the original was put back")
            }
            clearNote()
            return tags
        } finally {
            newFile.delete()
            if (!note.exists()) backup.delete()
        }
    }

    /**
     * Finishes a save the app couldn't complete. Returns a notice for the user, or null if there
     * was nothing to do. If the backup can't be put back yet, the note is kept for the next try.
     */
    fun recoverPending(): String? {
        if (!note.exists()) return null
        val props = Properties().apply { note.inputStream().use(::load) }
        val uri = props.getProperty("uri")
        val backup = props.getProperty("backup")?.let(::File)
        val originalHash = props.getProperty("originalHash")
        val newHash = props.getProperty("newHash")
        if (uri == null || backup == null || originalHash == null || newHash == null) {
            clearNote()
            return null
        }
        val current = runCatching { store.openRead(uri).use(::sha256) }.getOrNull()
        return when {
            current == newHash -> {
                clearNote()
                backup.delete()
                null
            }
            current == originalHash -> {
                clearNote()
                backup.delete()
                null
            }
            backup.exists() -> runCatching {
                restore(uri, backup, originalHash)
                clearNote()
                backup.delete()
                "A song's details weren't saved because the app closed; the original file was put back"
            }.getOrElse { "Couldn't put back a song after an interrupted save; Record Disc will try again next time" }
            else -> {
                clearNote()
                null
            }
        }
    }

    private fun copy(uri: String, target: File) {
        store.openRead(uri).use { input ->
            FileOutputStream(target).use { out ->
                input.copyTo(out)
                out.fd.sync()
            }
        }
    }

    private fun restore(uri: String, backup: File, originalHash: String) {
        store.overwrite(uri, backup)
        if (store.openRead(uri).use(::sha256) != originalHash) throw TagSaveException("Couldn't put the original song back")
    }

    /** Written to a temporary file and renamed, so a half-written note is never read. */
    private fun writeNote(uri: String, backup: File, originalHash: String, newHash: String) {
        val tmp = File(dir, "pending.tmp")
        FileOutputStream(tmp).use { out ->
            Properties().apply {
                setProperty("uri", uri)
                setProperty("backup", backup.absolutePath)
                setProperty("originalHash", originalHash)
                setProperty("newHash", newHash)
            }.store(out, null)
            out.fd.sync()
        }
        if (!tmp.renameTo(note)) throw TagSaveException("Couldn't prepare the save")
    }

    private fun clearNote() {
        note.delete()
    }

    private companion object {
        /** Room left over after the two copies. */
        const val FREE_MARGIN = 16L * 1024 * 1024

        fun sha256(file: File): String = file.inputStream().use(::sha256)

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
