/**
 * iOS folder library source for document-aware music library access.
 * Uses bookmark persistence and references Swift interop for full iOS functionality.
 *
 * Kotlin actuals delegate to MusicPlayeriOS.shared for iOS-specific implementations.
 * See Platform.ios.kt and corresponding Swift implementations for details.
 */
class IOSFolderLibrarySource(private val bookmarkedURLStrings: List<String>) : LibrarySource {
    // Bookmark-based library source for iOS
    // - URLs are stored as document-aware strings (file:// prefixed or bookmark data)
    // - Full file iteration, tag reading, and cover art decoding handled via Swift interop
    // - Kotlin land provides the LibrarySource interface; Swift land implements platform specifics

    // Note: These actual functions call MusicPlayeriOS.shared Swift methods
    // via @NativeSelector bridge. The Swift implementations are in MusicPlayer.swift.

    override suspend fun listFiles(): List<FileEntry> {
        // Call Swift interop to get bookmarked file URLs
        val urlStrings = MusicPlayeriOS.shared.listBookmarkedFiles()
        return urlStrings.map { urlString -> FileEntry {
            // Convert URL string to FileEntry
            // Default values - full metadata would come from Swift or be populated by caller
            FileEntry(location = urlString, fileName = urlString.split("/").last() ?: "unknown")
        } }
    }

    override suspend fun readTags(entry: FileEntry): TrackTags {
        // Call Swift interop to read tags for the file at entry.location
        // Note: This requires converting the Kotlin String path to a URL usable by Swift
        @Suppress("UNCHECKED_CAST")
        val tags = MusicPlayeriOS.shared.readTagsForFile(at = java.net.URI(entry.location).toURL())
        return TrackTags(
            title = tags["title"],
            artist = tags["artist"],
            album = tags["album"],
            durationMs = tags["durationMs"].let { it.toLong() ?: 0 }
        )
    }
}