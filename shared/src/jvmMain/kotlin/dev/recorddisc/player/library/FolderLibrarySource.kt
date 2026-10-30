package dev.recorddisc.player.library

import dev.recorddisc.player.tags.TagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Finds audio files under [roots] on the desktop. Hidden files and folders are skipped and
 * symbolic links are not followed, so link loops can't trap the scan.
 */
class FolderLibrarySource(private val roots: List<File>) : LibrarySource {
    override suspend fun listFiles(): List<FileEntry> = withContext(Dispatchers.IO) {
        val found = ArrayList<FileEntry>()
        // Lyrics files by folder, then by base name: matched to songs once the walk is done.
        val lyrics = HashMap<String, HashMap<String, String>>()
        for (root in roots.filter { it.isDirectory }) {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root.toPath() && dir.fileName.toString().startsWith(".")) FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    if (!attrs.isRegularFile || name.startsWith(".")) return FileVisitResult.CONTINUE
                    val folder = file.toAbsolutePath().parent.toString()
                    if (AudioFormats.isSupported(name)) {
                        found += FileEntry(file.toAbsolutePath().toString(), name, attrs.size(), attrs.lastModifiedTime().toMillis(), folder)
                    } else if (name.endsWith(".lrc", ignoreCase = true)) {
                        lyrics.getOrPut(folder) { HashMap() }[sidecarKey(name)] = file.toAbsolutePath().toString()
                    }
                    return FileVisitResult.CONTINUE
                }

                // An unreadable folder shouldn't stop the whole scan.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        found.map { it.copy(lyricsLocation = lyrics[it.folder]?.get(sidecarKey(it.fileName))) }
    }

    override suspend fun readTags(entry: FileEntry): TrackTags = withContext(Dispatchers.IO) {
        TagReader.read(File(entry.location))
    }
}
