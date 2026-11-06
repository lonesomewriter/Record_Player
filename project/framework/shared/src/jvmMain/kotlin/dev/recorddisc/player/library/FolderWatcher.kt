package dev.recorddisc.player.library

import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.attribute.BasicFileAttributes

/**
 * Watches [root] and the folders below it (hidden ones excepted, as in the scanner) and calls
 * [onChange] when files are added, changed or removed. Runs on its own daemon thread; folders
 * added later are watched as they appear. [close] stops it.
 */
class FolderWatcher(private val root: Path, private val onChange: () -> Unit) : AutoCloseable {
    private val service = root.fileSystem.newWatchService()

    init {
        Thread(::run, "folder-watcher").apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            watchTree(root)
            while (true) {
                val key = service.take()
                val dir = key.watchable() as Path
                for (event in key.pollEvents()) {
                    val name = event.context() as? Path ?: continue
                    val path = dir.resolve(name)
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) watchTree(path)
                }
                key.reset()
                onChange()
            }
        } catch (e: ClosedWatchServiceException) {
            // Closed.
        } catch (e: InterruptedException) {
            // Stopped.
        }
    }

    private fun watchTree(start: Path) {
        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                    try {
                        dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    } catch (e: IOException) {
                        // E.g. the system's limit on watched folders; that folder is then only picked up by rescans.
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (e: IOException) {
            // Folder gone or unreadable.
        }
    }

    override fun close() = service.close()
}
