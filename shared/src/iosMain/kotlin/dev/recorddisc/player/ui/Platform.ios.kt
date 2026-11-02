package dev.recorddisc.player.ui

import dev.recorddisc.player.audio.MusicPlayeriOS
import kotlinx.coroutines.flow.Flow

/** iOS-specific implementations of Platform expect functions.
 *  Full iOS implementations require Swift interop.
 *  - Basic UI/platform abstractions are implemented as Kotlin stubs
 *  - Folder-library methods (listBookmarkedFiles, readTags) now have Swift interop implementations in MusicPlayer.swift
 *  - These Kotlin actuals reference the shared MusicPlayeriOS instance
 */

actual fun rememberReduceMotion(): Boolean {
    // iOS Reduce Motion: checked via MusicPlayeriOS.shared.isReduceMotionEnabled
    // Kotlin fallback: assume reduced motion is false
    return false
}

actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS back handling: managed by navigation stack (UINavigationController pop)
    // Kotlin no-op; actual dismiss logic in Swift
}

actual fun systemPrefersDark(): Boolean {
    // iOS: checked via MusicPlayeriOS.shared.userInterfaceStyle
    // Kotlin fallback: assume dark mode is false
    return false
}

actual fun SystemBarsEffect(light: Boolean) {
    // iOS: status bar style - implemented in Swift interop
    // Kotlin no-op
}

actual fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    // iOS image size: will use UIImage via Swift interop
    // Kotlin fallback: return null
    return null
}

actual fun decodeImage(bytes: ByteArray, maxSizePx: Int): ImageBitmap? {
    // iOS image decoding: will use UIImage via Swift interop
    // Kotlin fallback: return null
    return null
}

actual fun viewFileLocation(location: String, folder: String?) {
    // iOS file opening: will use UIApplication.openURL via Swift interop
    // Kotlin no-op
}