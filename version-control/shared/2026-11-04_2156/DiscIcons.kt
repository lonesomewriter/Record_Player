package dev.recorddisc.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The mockups' icon set, built from the same 24×24 SVG paths. */
object DiscIcons {
    private fun filled(name: String, vararg paths: String) = icon(name, paths, stroke = null)
    private fun stroked(name: String, width: Float, vararg paths: String) = icon(name, paths, stroke = width)

    private fun icon(name: String, paths: Array<out String>, stroke: Float?): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                val nodes = PathParser().parsePathString(d).toNodes()
                if (stroke == null) {
                    addPath(nodes, fill = SolidColor(Color.Black))
                } else {
                    addPath(
                        nodes,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = stroke,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()

    /** SVG <circle> as a path. */
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    val Prev = filled("Prev", "M6 5h2v14H6z", "M20 5v14L9 12z")
    val Next = filled("Next", "M16 5h2v14h-2z", "M4 5v14l11-7z")
    val Play = filled("Play", "M8 5v14l11-7z")
    val Pause = filled("Pause", "M6 5h4v14H6zM14 5h4v14h-4z")
    val Shuffle = stroked("Shuffle", 1.8f, "M16 3h5v5", "M4 20 21 3", "M21 16v5h-5", "m15 15 6 6", "m4 4 5 5")
    val Repeat = stroked("Repeat", 1.8f, "M17 2l4 4-4 4", "M3 11V9a3 3 0 0 1 3-3h15", "M7 22l-4-4 4-4", "M21 13v2a3 3 0 0 1-3 3H3")
    val Search = stroked("Search", 1.9f, circle(11f, 11f, 7f), "m20 20-3.5-3.5")
    val Lyrics = stroked("Lyrics", 1.8f, "M4 6h11M4 10h11M4 14h6", circle(16.5f, 17.5f, 2.5f), "M19 17.5V8l2-1")
    val Edit = stroked("Edit", 1.8f, "m4 20 4.5-1 10-10a2.1 2.1 0 0 0-3-3l-10 10z", "m14 7 3 3")
    val Plus = stroked("Plus", 1.8f, "M12 5v14M5 12h14")
    val Import = stroked("Import", 1.8f, "M12 3v12", "m7 10 5 5 5-5", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6")
    val Check = stroked("Check", 2f, "m5 12.5 4.5 4.5L19 7.5")
    val Home = stroked("Home", 1.8f, "M3 11.5 12 4l9 7.5", "M5 10v10h14V10")
    val Library = stroked("Library", 1.8f, "M5 4h3v16H5zM11 4h3v16h-3z", "m17.5 5 2.8 14")
    val Playlist = stroked("Playlist", 1.8f, "M4 6h12M4 12h12M4 18h7", "m17 14 4 2-4 2z")
    val Settings = stroked("Settings", 1.8f, "M4 7h16M4 17h16", circle(9f, 7f, 2.2f), circle(15f, 17f, 2.2f))
    val Queue = stroked("Queue", 1.8f, "M4 6h11M4 12h11M4 18h11", "m18 15 3 2-3 2z")
    val Volume = stroked("Volume", 1.8f, "M4 9h4l5-4v14l-5-4H4z", "M17 9a4 4 0 0 1 0 6", "M19 6a8 8 0 0 1 0 12")
}
