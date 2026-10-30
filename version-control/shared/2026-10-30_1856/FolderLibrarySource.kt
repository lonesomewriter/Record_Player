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
        for (root in roots.filter { it.isDirectory }) {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root.toPath() && dir.fileName.toString().startsWith(".")) FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    if (attrs.isRegularFile && !name.startsWith(".") && AudioFormats.isSupported(name)) {
                        found += FileEntry(file.toAbsolutePath().toString(), name, attrs.size(), attrs.lastModifiedTime().toMillis())
                    }
                    return FileVisitResult.CONTINUE
                }

                // An unreadable folder shouldn't stop the whole scan.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        found
    }

    override suspend fun readTags(entry: FileEntry): TrackTags = withContext(Dispatchers.IO) {
        TagReader.read(File(entry.location))
    }
}
