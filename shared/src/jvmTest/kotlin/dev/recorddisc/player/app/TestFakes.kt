package dev.recorddisc.player.app

import androidx.compose.ui.graphics.ImageBitmap
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.FileEntry
import dev.recorddisc.player.library.LibrarySource
import dev.recorddisc.player.library.SavedFile
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagSaveException
import dev.recorddisc.player.library.TrackEntity
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
    /** (item, reload, play) for each [updateItem] call. */
    val updates = ArrayList<Triple<QueueItem, Boolean, Boolean>>()
    override fun updateItem(item: QueueItem, reload: Boolean, play: Boolean) {
        updates += Triple(item, reload, play)
        state.value = state.value.copy(queue = state.value.queue.map { if (it.id == item.id) item else it }, isPlaying = if (reload) play else state.value.isPlaying)
    }
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
    /** Tag editing: what [editSupport] answers, whether saves rewrite in place, and a failure message to throw. */
    var support: EditSupport = EditSupport.Editable
    var inPlace = false
    var failSave: String? = null
    /** Files saved so far: their tags and new size/time, which later scans see. */
    val saved = HashMap<String, SavedFile>()
    /** How many times a file's tags were read by a scan, and how many times a folder was listed. */
    @Volatile var tagReads = 0
    @Volatile var listCalls = 0

    override suspend fun editSupport(track: TrackEntity) = support
    override val rewritesInPlace get() = inPlace
    override suspend fun saveTags(track: TrackEntity, edit: TagEdit): SavedFile {
        failSave?.let { throw TagSaveException(it) }
        val tags = TrackTags(edit.title, edit.artist, edit.album, edit.albumArtist, edit.year, edit.trackNumber, null, edit.genre, track.durationMs)
        return SavedFile(track.sizeBytes + 10, track.modifiedMs + 1_000, tags).also { saved[track.location] = it }
    }

    override fun sourceFor(folder: String) = object : LibrarySource {
        override suspend fun listFiles(): List<FileEntry> {
            listCalls++
            val songs = folders[folder] ?: error("Permission denied")
            return songs.map { (title) ->
                val location = "$folder/$title.flac"
                val s = saved[location]
                FileEntry(location, "$title.flac", s?.sizeBytes ?: 100, s?.modifiedMs ?: 1)
            }
        }
        override suspend fun readTags(entry: FileEntry): TrackTags {
            tagReads++
            saved[entry.location]?.let { return it.tags }
            val (title, artist, album) = folders.getValue(folder).first { entry.fileName.startsWith(it.first) }
            return TrackTags(title = title, artist = artist, album = album, durationMs = 200_000)
        }
    }
}
