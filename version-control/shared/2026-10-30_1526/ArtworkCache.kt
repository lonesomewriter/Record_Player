package dev.recorddisc.player.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the most recently shown cover images in memory, so scrolling back doesn't re-read files.
 * Holds at most [capacity] images and [maxPixels] pixels (about 48 MB), whichever comes first.
 */
class ArtworkCache(private val platform: LibraryPlatform, private val capacity: Int = 80, private val maxPixels: Long = 12_000_000) {
    private class Entry(val image: ImageBitmap?) {
        val pixels: Long = image?.let { it.width.toLong() * it.height } ?: 0
    }

    private val lock = Mutex()
    /** Access order: the eldest entry is dropped first. Files without art are cached as null too. */
    private val entries = LinkedHashMap<String, Entry>()
    private var pixels = 0L

    suspend fun get(location: String, maxSizePx: Int): ImageBitmap? {
        val key = "$maxSizePx|$location"
        lock.withLock {
            entries.remove(key)?.let { entries[key] = it; return it.image }
        }
        val image = try {
            platform.loadArtwork(location, maxSizePx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        lock.withLock {
            val entry = Entry(image)
            entries.remove(key)?.let { pixels -= it.pixels }
            entries[key] = entry
            pixels += entry.pixels
            while (entries.size > 1 && (entries.size > capacity || pixels > maxPixels)) {
                pixels -= entries.remove(entries.keys.first())!!.pixels
            }
        }
        return image
    }
}

/** Cover art for [location] (null while loading, or when the file has none). */
@Composable
fun ArtworkCache.rememberArtwork(location: String?, maxSizePx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, location, maxSizePx) {
        value = location?.let { get(it, maxSizePx) }
    }
