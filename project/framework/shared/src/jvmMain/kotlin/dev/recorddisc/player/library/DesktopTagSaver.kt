package dev.recorddisc.player.library

import dev.recorddisc.player.tags.TagReader
import dev.recorddisc.player.tags.TagWriter
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Saves tags on Linux: the edit is made on a hidden copy next to the song, synced to disk, then
 * swapped in with one atomic rename. The song is either the old file or the new one, never half
 * written, and a player that has the old file open keeps reading it.
 */
object DesktopTagSaver {
    fun support(file: File): EditSupport = when {
        !file.isFile -> EditSupport.ReadOnly("The file is missing")
        !AudioFormats.isSupported(file.name) -> EditSupport.ReadOnly("This kind of file can't be edited")
        !file.canWrite() || file.parentFile?.canWrite() != true -> EditSupport.ReadOnly("You don't have permission to change this file")
        else -> EditSupport.Editable
    }

    fun save(file: File, edit: TagEdit): SavedFile {
        // Hidden (skipped by the scanner and folder watcher), and keeping the extension that jaudiotagger goes by.
        val temp = File(file.parentFile, ".rd-edit-${System.nanoTime()}-${file.name}")
        try {
            Files.copy(file.toPath(), temp.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            TagWriter.apply(temp, edit)
            FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            // jaudiotagger may recreate the file; keep the original's permissions.
            runCatching { Files.setPosixFilePermissions(temp.toPath(), Files.getPosixFilePermissions(file.toPath())) }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
        return SavedFile(file.length(), file.lastModified(), TagReader.read(file))
    }
}
