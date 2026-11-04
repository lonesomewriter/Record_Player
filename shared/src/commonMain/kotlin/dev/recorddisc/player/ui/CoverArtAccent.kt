package dev.recorddisc.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap

internal fun representativeCoverAccent(image: ImageBitmap): Color? {
    val pixels = image.toPixelMap()
    val stepX = maxOf(1, (image.width + 31) / 32)
    val stepY = maxOf(1, (image.height + 31) / 32)
    val samples = buildList {
        for (y in 0 until image.height step stepY) {
            for (x in 0 until image.width step stepX) add(pixels[x, y])
        }
    }
    return representativeCoverAccent(samples)
}

internal fun representativeCoverAccent(colors: Iterable<Color>): Color? {
    data class Bucket(var count: Int = 0, var red: Float = 0f, var green: Float = 0f, var blue: Float = 0f)

    val buckets = mutableMapOf<Int, Bucket>()
    for (color in colors) {
        val red = color.red.coerceIn(0f, 1f)
        val green = color.green.coerceIn(0f, 1f)
        val blue = color.blue.coerceIn(0f, 1f)
        val brightest = maxOf(red, green, blue)
        val darkest = minOf(red, green, blue)
        if (color.alpha < 0.5f || brightest < 0.08f || brightest - darkest < 0.12f) continue

        val key = ((red * 8).toInt().coerceAtMost(7) shl 6) or
            ((green * 8).toInt().coerceAtMost(7) shl 3) or
            (blue * 8).toInt().coerceAtMost(7)
        val bucket = buckets.getOrPut(key) { Bucket() }
        bucket.count++
        bucket.red += red
        bucket.green += green
        bucket.blue += blue
    }
    val dominant = buckets.values.maxByOrNull { it.count } ?: return null
    return Color(
        red = dominant.red / dominant.count,
        green = dominant.green / dominant.count,
        blue = dominant.blue / dominant.count,
    )
}
