package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.playback.MusicPlayer
import dev.recorddisc.player.playback.PlaybackState
import dev.recorddisc.player.playback.QueueItem
import dev.recorddisc.player.playback.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow

/** Records what the app asks the player to do. */
internal class FakePlayer : MusicPlayer {
    override val state = MutableStateFlow(PlaybackState())
    val queues = ArrayList<Triple<List<QueueItem>, Int, Boolean>>()
    override fun setQueue(items: List<QueueItem>, startIndex: Int, playWhenReady: Boolean, startPositionMs: Long) {
        queues += Triple(items, startIndex, playWhenReady)
        state.value = PlaybackState(queue = items, index = startIndex, isPlaying = playWhenReady, positionMs = startPositionMs)
    }
    override fun play() { state.value = state.value.copy(isPlaying = true) }
    override fun pause() { state.value = state.value.copy(isPlaying = false) }
    override fun next() = Unit
    override fun skipTo(index: Int) { state.value = state.value.copy(index = index, isPlaying = true) }
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs)
    }
    override fun setRepeatMode(mode: RepeatMode) = Unit
    var shuffleOn = false
    override fun setShuffle(enabled: Boolean) { shuffleOn = enabled }
    override fun setVolume(volume: Float) = Unit
    override fun release() = Unit
}

/** Folders as in-memory song lists; "/broken" fails to list. Folders can change, and watching is recorded. */
internal open class FakePlatform : LibraryPlatform {
    val folders = mutableMapOf(
        "/music" to listOf(
            Triple("Midnight Drive", "Neon Coast", "After Hours"),
            Triple("Paper Lanterns", "Neon Coast", "After Hours"),
            Triple("Static Bloom", "Mira Vale", "Slow Light"),
        ),
        "/other" to listOf(Triple("Copper Sky", "Lumen Field", "Northbound")),
        "/empty" to emptyList(),
    )
    /** The folder being watched and how to signal a change, while watching. */
    @Volatile var watching: Pair<String, () -> Unit>? = null
    val released = ArrayList<String>()

    override fun watch(folder: String, onChange: () -> Unit): AutoCloseable {
        watching = folder to onChange
        return AutoCloseable { watching = null }
    }

    override fun releaseFolder(folder: String) {
        released += folder
    }
    override val suggestedFolder = "/music"
    override fun folderLabel(folder: String) = folder
    override suspend fun loadArtwork(location: String, maxSizePx: Int): ImageBitmap? = null
    override fun sourceFor(folder: String) = object : LibrarySource {
        override suspend fun listFiles(): List<FileEntry> {
            val songs = folders[folder] ?: error("Permission denied")
            return songs.map { (title) -> FileEntry("$folder/$title.flac", "$title.flac", 100, 1) }
        }
        override suspend fun readTags(entry: FileEntry): TrackTags {
            val (title, artist, album) = folders.getValue(folder).first { entry.fileName.startsWith(it.first) }
            return TrackTags(title = title, artist = artist, album = album, durationMs = 200_000)
        }
    }
}
