package dev.recorddisc.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberReduceMotion(): Boolean = false

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun dynamicAccentColor(): Color? = null

/** Linux: the desktop's dark/light setting, read at start and again when the window gains or loses focus. */
@Composable
actual fun systemPrefersDark(): Boolean {
    var dark by remember { mutableStateOf(true) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        dark = withContext(Dispatchers.IO) { LinuxColorScheme.prefersDark() } ?: true
    }
    return dark
}

@Composable
actual fun SystemBarsEffect(light: Boolean) = Unit

actual fun imageSize(bytes: ByteArray): Pair<Int, Int>? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.width to it.height } }.getOrNull()

actual fun decodeImage(bytes: ByteArray, maxSizePx: Int): ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
        val scale = maxSizePx.toFloat() / maxOf(image.width, image.height)
        if (scale >= 1f) return@use image.toComposeImageBitmap()
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { surface ->
            surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
            surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }
}.getOrNull()
