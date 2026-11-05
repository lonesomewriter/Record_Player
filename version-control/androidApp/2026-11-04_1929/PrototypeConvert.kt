package dev.recorddisc.player.convert

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.library.PlaylistView
import dev.recorddisc.player.library.displayTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * TEMPORARY — "To audio file" prototype check on a real phone (project manager, 2026-11-04).
 * Only reachable in debuggable (dev) builds, by long-pressing "To audio file · SOON" on a playlist.
 * Remove this file and the PlaylistFileActions.prototypeConvert hook before the full build.
 */

private const val SONG_LIMIT = 10

/**
 * Joins the playlist's first songs (1 s gaps) into FLAC and M4A, checks each file on the phone
 * (length, song marks, tags), saves both to Music/Exports and shows what passed.
 */
internal fun Activity.runPrototypeConvert(scope: CoroutineScope, model: AppModel, playlist: PlaylistView) {
    val songs = playlist.songs.take(SONG_LIMIT)
    if (songs.isEmpty()) {
        model.message("Prototype: add songs to this playlist first")
        return
    }
    model.message("Prototype: converting ${playlist.name}…")
    scope.launch {
        val report = withContext(Dispatchers.IO) {
            val converter = MediaCodecConverter(this@runPrototypeConvert)
            val inputs = songs.map { ConvertInput(it.location, it.displayTitle) }
            val dir = File(cacheDir, "prototype").apply { mkdirs() }
            val safeName = playlist.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Playlist" }
            buildString {
                appendLine("${songs.size} song(s)${if (playlist.songs.size > SONG_LIMIT) " (first $SONG_LIMIT)" else ""}, 1 s gaps.")
                for (format in listOf(ConvertFormat.Flac, ConvertFormat.M4a)) {
                    appendLine()
                    val out = File(dir, "$safeName.${format.extension}")
                    try {
                        val started = System.nanoTime()
                        val result = converter.convert(ConvertRequest(inputs, format, 1, out.path, playlist.name))
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        val back = converter.decodedFrames(out.path, result.sampleRate)
                        appendLine("${format.name.uppercase()}: ${"%.1f".format(result.totalSamples.toDouble() / result.sampleRate)} s of audio, made in ${"%.1f".format(seconds)} s")
                        if (format == ConvertFormat.Flac) {
                            line("length (decodes back exactly)", back == result.totalSamples, "$back vs ${result.totalSamples}")
                            line("length in header", FlacMarks.readTotalSamples(out) == result.totalSamples, "${FlacMarks.readTotalSamples(out)}")
                            line("song marks (cue sheet)", FlacMarks.readTrackOffsets(out) == result.songStarts, "${FlacMarks.readTrackOffsets(out)} vs ${result.songStarts}")
                            line("title tag", "TITLE=${playlist.name}" in FlacMarks.readTags(out), FlacMarks.readTags(out).joinToString())
                        } else {
                            line("length (within the AAC lead-in)", back - result.totalSamples in 0..4_096, "$back vs ${result.totalSamples}")
                            val chapters = Mp4Chapters.read(out)
                            line("song marks (chapters)", chapters.map { it.second } == inputs.map { it.title }, chapters.joinToString { "${it.first} ms ${it.second}" })
                        }
                        appendLine("  saved: ${saveToMusicExports(out, if (format == ConvertFormat.Flac) "audio/flac" else "audio/mp4")}")
                    } catch (e: Exception) {
                        appendLine("${format.name.uppercase()}: ✗ ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        out.delete()
                    }
                }
            }
        }
        if (!isFinishing) {
            AlertDialog.Builder(this@runPrototypeConvert)
                .setTitle("To audio file · prototype")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}

private fun StringBuilder.line(what: String, ok: Boolean, detail: String) {
    appendLine("  ${if (ok) "✓" else "✗"} $what${if (ok) "" else " — $detail"}")
}

/** Music/Exports through the shared media store (Android 10+; no permission), else the app's Music folder. */
private fun Activity.saveToMusicExports(file: File, mime: String): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Exports")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Android wouldn't create the file in Music/Exports")
        contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return "Music/Exports/${file.name}"
    }
    val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Exports").apply { mkdirs() }
    val target = File(dir, file.name)
    file.copyTo(target, overwrite = true)
    return target.path
}
