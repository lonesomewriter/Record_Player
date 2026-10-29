package dev.recorddisc.player.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the most recently shown cover images in memory, so scrolling back doesn't re-read files. */
class ArtworkCache(private val platform: LibraryPlatform, private val capacity: Int = 80) {
    private class Entry(val image: ImageBitmap?)

    private val lock = Mutex()
    /** Access order: the eldest entry is dropped first. Files without art are cached as null too. */
    private val entries = LinkedHashMap<String, Entry>()

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
            entries[key] = Entry(image)
            while (entries.size > capacity) entries.remove(entries.keys.first())
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
