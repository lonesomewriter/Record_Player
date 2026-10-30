package dev.recorddisc.player.tags

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.recorddisc.player.app.AndroidLibraryPlatform
import dev.recorddisc.player.library.CoverChange
import dev.recorddisc.player.library.TagEdit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Tag writing on Android itself: jaudiotagger in its Android mode (no java.awt / ImageIO), on the
 * same 2-second tones as the desktop tests, with a cover made by Android's own image encoder.
 */
@RunWith(AndroidJUnit4::class)
class TagWriterAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Before
    fun setUp() {
        // As in the app: creating the platform switches jaudiotagger to its Android mode.
        AndroidLibraryPlatform(app)
    }

    private fun tone(ext: String): File = File(app.cacheDir, "edit.$ext").also { out ->
        instrumentation.context.assets.open("audio/tone.$ext").use { it.copyTo(out.outputStream()) }
    }

    private fun png(): CoverChange.Replace {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 60)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return CoverChange.Replace(bytes, "image/png", 64, 64)
    }

    @Test
    fun writesFieldsAndCoverThenRemovesThemInEveryFormat() {
        for (ext in listOf("mp3", "flac", "ogg", "opus", "m4a", "wav")) {
            val file = tone(ext)
            val cover = png()
            TagWriter.apply(file, TagEdit("Android Title", "Artist", "Album", "Album Artist", 2022, 5, "Genre", cover))
            val read = TagReader.read(file)
            assertEquals("Android Title", read.title, ext)
            assertEquals("Album Artist", read.albumArtist, ext)
            assertEquals(5, read.trackNumber, ext)
            assertContentEquals(cover.bytes, TagReader.readArtwork(file), "$ext cover")

            TagWriter.apply(file, TagEdit("Android Title", null, null, null, null, null, null, CoverChange.Remove))
            val cleared = TagReader.read(file)
            assertNull(cleared.artist, "$ext artist removed")
            assertFalse(cleared.hasArtwork, "$ext cover removed")
            file.delete()
        }
    }
}
