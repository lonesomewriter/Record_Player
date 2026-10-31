package dev.recorddisc.player.library

data class PlaylistImportFile(
    val sourcePath: String,
    val fileName: String,
    val lyricsPath: String? = null,
)

data class ImportedPlaylistSong(
    val location: String,
    val lyricsLocation: String?,
)

data class PlaylistTextFile(val name: String, val text: String)

data class M3uImportItem(val entry: M3uEntry, val track: TrackEntity?)

data class M3uImportPreview(val name: String, val items: List<M3uImportItem>) {
    val matchedCount: Int get() = items.count { it.track != null }
    val notFoundCount: Int get() = items.size - matchedCount
}

data class PlaylistBundlePreviewSong(
    val sourcePath: String,
    val fileName: String,
    val lyricsPath: String?,
    val tags: TrackTags?,
    val existingTrack: TrackEntity?,
    val warning: String?,
)

data class PlaylistBundlePreview(
    val name: String,
    val songs: List<PlaylistBundlePreviewSong>,
    val coverBytes: ByteArray?,
    val coverExtension: String?,
    val temporaryDirectory: String,
)
