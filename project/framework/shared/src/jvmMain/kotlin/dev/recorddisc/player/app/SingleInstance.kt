package dev.recorddisc.player.app

import dev.recorddisc.player.library.DesktopPaths
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Keeps Record Player to one window. A file manager's "Open with" starts the app again with the
 * file's path; if a window is already open, the new start passes the paths to it over a local
 * socket (a file in the user's runtime folder, never the network) and exits.
 */
class SingleInstance private constructor(private val server: ServerSocketChannel?, private val socket: File) : AutoCloseable {
    /** Receives the paths sent by later starts (empty when started without files), on a background thread. */
    fun listen(onOpen: (List<String>) -> Unit) {
        val server = this.server ?: return
        Thread({
            while (server.isOpen) {
                try {
                    server.accept().use { client -> onOpen(decode(readAll(client))) }
                } catch (_: IOException) {
                    // Closed on exit, or a sender that gave up: keep listening while open.
                }
            }
        }, "record-player-open-files").apply { isDaemon = true }.start()
    }

    override fun close() {
        val server = this.server ?: return
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(socket.toPath()) }
    }

    companion object {
        /**
         * Hands [paths] to a window that's already open and returns null (this start should exit),
         * or becomes the one window and returns its [SingleInstance]. If the socket can't be used at
         * all, this start simply runs on its own.
         */
        fun claim(paths: List<String>, socket: File = defaultSocket()): SingleInstance? {
            if (sendTo(socket, paths)) return null
            return try {
                Files.deleteIfExists(socket.toPath()) // left by a window that closed without cleaning up
                socket.parentFile?.mkdirs()
                val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket.toPath()))
                runCatching { Files.setPosixFilePermissions(socket.toPath(), PosixFilePermissions.fromString("rw-------")) }
                SingleInstance(server, socket)
            } catch (_: Exception) {
                SingleInstance(null, socket)
            }
        }

        private fun sendTo(socket: File, paths: List<String>): Boolean {
            if (!socket.exists()) return false
            return try {
                SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).use { channel ->
                    val bytes = ByteBuffer.wrap(encode(paths))
                    while (bytes.hasRemaining()) channel.write(bytes)
                }
                true
            } catch (_: IOException) {
                false // nothing is listening: a stale socket file
            }
        }

        /** `$XDG_RUNTIME_DIR` (private to the user) when set, else the app's data folder. */
        fun defaultSocket(): File {
            val runtime = System.getenv("XDG_RUNTIME_DIR")?.let(::File)?.takeIf { it.isDirectory }
            return File(runtime ?: DesktopPaths.dataDir(), "record-player.sock")
        }

        // One path per line; paths can't contain a NUL, so it marks the end of the message.
        internal fun encode(paths: List<String>): ByteArray = (paths.joinToString("\n") + "\u0000").toByteArray(StandardCharsets.UTF_8)

        internal fun decode(bytes: ByteArray): List<String> =
            bytes.toString(StandardCharsets.UTF_8).substringBefore('\u0000').split('\n').filter { it.isNotBlank() }

        private fun readAll(channel: SocketChannel): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (out.size() < MAX_MESSAGE_BYTES) {
                buffer.clear()
                if (channel.read(buffer) < 0) break
                out.write(buffer.array(), 0, buffer.position())
                if (buffer.array().take(buffer.position()).contains(0)) break
            }
            return out.toByteArray()
        }

        private const val MAX_MESSAGE_BYTES = 1 shl 20
    }
}

/**
 * Turns what a file manager passes on the command line into file paths: a path as is, or a
 * `file://` URI (some file managers send those). Anything else is ignored.
 */
fun openArgumentsToPaths(args: List<String>): List<String> = args.mapNotNull { arg ->
    when {
        arg.startsWith("file:") -> runCatching { File(URI(arg)).absolutePath }.getOrNull()
        arg.startsWith("-") -> null
        else -> File(arg).absolutePath
    }
}.filter { File(it).isFile }
