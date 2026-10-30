package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.recorddisc.player.library.DesktopPaths
import dev.recorddisc.player.library.FolderLibrarySource
import dev.recorddisc.player.library.FolderWatcher
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.tags.TagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Linux: folders are plain paths; cover art comes from jaudiotagger and is decoded by Skia. */
class DesktopLibraryPlatform : LibraryPlatform {
    private val home = System.getProperty("user.home")

    override val suggestedFolder: String? = DesktopPaths.musicDir().takeIf { it.isDirectory }?.absolutePath

    override fun sourceFor(folder: String): LibrarySource = FolderLibrarySource(listOf(File(folder)))

    override fun watch(folder: String, onChange: () -> Unit): AutoCloseable = FolderWatcher(File(folder).toPath(), onChange)

    override fun folderLabel(folder: String): String =
        if (home != null && (folder == home || folder.startsWith("$home/"))) "~" + folder.removePrefix(home) else folder

    override suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = TagReader.readArtwork(File(location)) ?: return@withContext null
        Image.makeFromEncoded(bytes).use { image ->
            val scale = maxSizePx.toFloat() / max(image.width, image.height)
            if (scale >= 1f) return@withContext image.toComposeImageBitmap()
            val w = (image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (image.height * scale).roundToInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(w, h).use { surface ->
                surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }
}
