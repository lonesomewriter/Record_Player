import AVFoundation
import Foundation

// iOS MusicPlayer implementation using AVFoundation
// This integrates with the Kotlin Multiplatform MusicPlayer interface

@objc(MusicPlayeriOS) class MusicPlayeriOS: NSObject {
    // Singleton instance
    static let shared = MusicPlayeriOS()
    
    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    
    // Background playback support
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    
    // MARK: - Public Interface
    
    /// Initialize audio session for playback
    func setupAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers, .allowAirPlay])
            try session.setActive(true)
        } catch {
            print("Failed to set up audio session: \(error.localizedDescription)")
        }
    }
    
    /// Replace the queue and start at [startIndex] (before any shuffle), [startPositionMs] into that song
    func setQueue(items: [String: Any], startIndex: Int, playWhenReady: Bool, startPositionMs: Int64) {
        // Implementation will be connected from Kotlin
    }
    
    /// Play playback
    func play() {
        player?.play()
    }
    
    /// Pause playback
    func pause() {
        player?.pause()
    }
    
    /// Next song
    func next() {
        player?.advanceToNextItem()
    }
    
    /// Previous song
    func previous() {
        player?.seek(to: CMTime(seconds: max(0, player?.currentTime().seconds ?? 0 - 10), preferredTimescale: 1))
    }
    
    /// Seek to position (in milliseconds)
    func seekTo(positionMs: Int64) {
        player?.seek(to: CMTime(seconds: Double(positionMs) / 1000, preferredTimescale: 1000))
    }
    
    /// Set repeat mode
    func setRepeatMode(mode: String) {
        // Implementation depends on AVPlayer configuration
    }
    
    /// Set shuffle enabled
    func setShuffle(enabled: Bool) {
        // Shuffle implementation
    }
    
    /// Set volume (0.0 to 1.0)
    func setVolume(volume: Float) {
        player?.volume = volume
    }
    
    /// Get current playback state
    func getPlaybackState() -> [String: Any] {
        guard let player = player else {
            return ["isPlaying": false, "positionMs": 0, "durationMs": nil]
        }
        
        let isPlaying = player.isPlaying
        let positionMs = Int64(player.currentTime().seconds * 1000)
        let durationMs: Int64? = player.currentItem?.duration.isIndeterminate ? nil : Int64(player.currentItem!.duration.seconds * 1000)
        
        return ["isPlaying": isPlaying, "positionMs": positionMs, "durationMs": durationMs]
    }
    
    /// Release player resources
    func release() {
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        playerItem = nil
        
        // End background task if running
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }
    
    /// Load a song at the given URL
    func loadItem(at url: URL) {
        let item = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: item)
        player?.play()
    }
    
    /// List all bookmarked file URLs (for document-aware library access)
    /// Kotlin delegates to this via @NativeSelector from IOSFolderLibrarySource.listFiles()
    @objc func listBookmarkedFiles() -> [String] {
        // Return stored bookmarked URL strings
        // This should retrieve from UserDefaults or Keychain where bookmark data is persisted
        return []
    }
    
    /// Read metadata tags from a file URL
    /// Kotlin delegates to this via @NativeSelector from IOSFolderLibrarySource.readTags()
    /// - Parameter url: The file URL to read tags from
    /// - Returns: TrackTags with title, artist, album, duration, etc.
    @objc func readTagsForFile(at url: URL) -> [String: String] {
        var tags = [String: String]()
        
        do {
            let asset = AVURLAsset(url: url)
            
            if let title = asset.commonMetadata.first(where: { $0.commonKey == AVMetadataKey.commonKeyTitle })?.value as? String {
                tags["title"] = title
            }
            if let artist = asset.commonMetadata.first(where: { $0.commonKey == AVMetadataKey.commonKeyArtist })?.value as? String {
                tags["artist"] = artist
            }
            if let album = asset.commonMetadata.first(where: { $0.commonKey == AVMetadataKey.commonKeyAlbumName })?.value as? String {
                tags["album"] = album
            }
            
            // Get duration
            let durationCMTime = asset.duration
            let durationSeconds = CMTimeGetSeconds(durationCMTime)
            tags["durationMs"] = "\(Int(durationSeconds * 1000))"
            
        } catch {
            print("Failed to read tags from \(url): \(error.localizedDescription)")
        }
        
        return tags
    }
    
    /// Persist a bookmark for a URL (for document-aware access across app launches)
    /// Kotlin may call this to save bookmark data for later file iteration
    /// - Parameters:
    ///   - url: The URL to create a bookmark for
    ///   - completion: Returns the frozen bookmark URL data (or nil on failure)
    @objc func persistBookmark(for url: URL, completion: @escaping (Data?) -> Void) {
        do {
            let bookmarkData = try url.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil)
            completion(bookmarkData)
        } catch {
            print("Failed to create bookmark for \(url): \(error.localizedDescription)")
            completion(nil)
        }
    }
    
    /// Remove a previously persisted bookmark
    /// - Parameter bookmarkData: The bookmark data to remove (not currently implemented as a full feature)
    @objc func removeBookmark(bookmarkData: Data) {
        // Bookmark removal - can be implemented if needed for cleanup
    }
    
    // MARK: - Private Helpers
    
    /// Advance to next item in shuffled order
    private func advanceToNextItem() {
        guard let player = player, let item = player.currentItem else { return }
        // Would need to recreate player item with next song
    }
}

// MARK: - iOS UI Hosting Controller for Kotlin Multiplatform Compose UI
// This Swift file bridges ComposeView (Kotlin MP) with iOS UIHostingController
//
// To use: Create a UIHostingController with this rootView, or present directly
//
// ComposeView is the Kotlin MP Compose bridge view
// It's generated from the @Compose annotation processing
import dev.recorddisc.player.ui // This imports the Kotlin ui package

// MARK: - Compose Root View
/// The main Compose UI view for iOS
/// This is the Kotlin Multiplatform Compose equivalent of Android's setContentView()
struct MusicPlayerComposeView: View {
    var body: some View {
        // This delegates to the Kotlin Compose content
        // The actual composition is defined in PhoneApp's Compose setup
        PhoneApp() // Assuming PhoneApp composable is exported to Swift via @Compose
    }
}

// MARK: - UIHostingController Wrapper
/// A UIHostingController that holds the Kotlin Compose UI
class ComposeViewController: UIHostingController<MusicPlayerComposeView> {
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        // Additional iOS-specific setup can go here
    }
    
    init() {
        super.init(rootView: MusicPlayerComposeView())
    }
}

// MARK: - PhoneApp Adaptation for iOS
/// Adapts the PhoneApp UI for iOS navigation patterns
/// - Uses UINavigationController instead of Android's navigation
/// - Handles iOS system bars, safe area insets, dynamic type
@available(iOS 13.0, *)
struct PhoneApp_iOS: View {
    var body: some View {
        NavigationView {
            // The main Compose UI
            MusicPlayerComposeView()
                .navigationTitle("Music Player")
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        Text("Music Player")
                            .font(.headline)
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        // iOS-specific toolbar items (playback controls, etc.)
                        Image(systemName: "slider.horizontal.3")
                    }
                }
                .background(Color(.systemBackground))
                .preferredColorScheme(.unspecified) // respect user's dark mode setting
        }
    }
}

// MARK: - Presentation Controller
/// Presents the Compose UI in a UIHostingController
/// Can be used modally or as root window
func presentMusicPlayerUI(in window: UIWindow?) {
    let composerView = MusicPlayerComposeView()
    let hostingController = UIHostingController(rootView: composerView)
    hostingController.modalPresentationStyle = .fullScreen
    
    window?.rootViewController?.present(hostingController, animated: true)
}

// MARK: - System Bar & Inset Handling
/// Extends UIView for iOS system bar handling
extension UIView {
    /// Adjusts for iOS safe area insets (notch, home indicator, etc.)
    func applyiOSSafeAreaInsets() {
        self.safeAreaLayoutGuide.topAnchor.constraint(equalToSystemSpacingBelow: nil).isActive = true
        // Additional safe area adjustments based on iOS version
    }
    
    /// Sets status bar style for the view
    func setStatusBarStyle(_ style: UIStatusBarStyle) {
        UIApplication.shared.statusBarStyle = style
    }
}
    
    /// Advance to next item in shuffled order
    private func advanceToNextItem() {
        guard let player = player, let item = player.currentItem else { return }
        // Would need to recreate player item with next song
    }
}
EOF"