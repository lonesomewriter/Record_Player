package dev.recorddisc.player.library

import dev.recorddisc.player.tags.TagReader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Lyrics on Linux: `<song name>.lrc` in the song's folder, written to a hidden copy and swapped in atomically. */
object DesktopLyricsSaver {
    /** The song's `.lrc`: the one the library found, else the name it would have. */
    fun lrcFile(track: TrackEntity): File =
        track.lyricsLocation?.let(::File) ?: File(File(track.location).parentFile, track.fileName.substringBeforeLast('.') + ".lrc")

    fun read(track: TrackEntity): Lyrics? {
        val lrc = lrcFile(track)
        if (lrc.isFile) return Lrc.parse(Lrc.decode(lrc.readBytes()))
        val embedded = runCatching { TagReader.readLyrics(File(track.location)) }.getOrNull() ?: return null
        return Lrc.parse(embedded, embedded = true)
    }

    fun support(track: TrackEntity): EditSupport {
        val folder = File(track.location).parentFile
        return when {
            folder == null || !folder.isDirectory -> EditSupport.ReadOnly("The song's folder is missing")
            !folder.canWrite() || lrcFile(track).let { it.exists() && !it.canWrite() } ->
                EditSupport.ReadOnly("You don't have permission to change files in this folder")
            else -> EditSupport.Editable
        }
    }

    /** Writes or (with null [text]) deletes the `.lrc`; returns its path afterwards, or null once deleted. */
    fun save(track: TrackEntity, text: String?): String? {
        val lrc = lrcFile(track)
        if (text == null) {
            Files.deleteIfExists(lrc.toPath())
            return null
        }
        val temp = File(lrc.parentFile, ".rd-lyrics-${System.nanoTime()}-${lrc.name}")
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.encodeToByteArray())
                out.fd.sync()
            }
            Files.move(temp.toPath(), lrc.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            throw TagSaveException("Couldn't write ${lrc.name}", e)
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
        return lrc.absolutePath
    }
}
