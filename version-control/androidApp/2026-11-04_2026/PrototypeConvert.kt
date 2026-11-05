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
import java.util.concurrent.atomic.AtomicBoolean

/*
 * TEMPORARY — "To audio file" prototype check on a real phone (project manager, 2026-11-04).
 * Only reachable in debuggable (dev) builds, by long-pressing "To audio file · SOON" on a playlist.
 * Remove this file and the PlaylistFileActions.prototypeConvert hook before the full build.
 */

/** Enough songs to prove joining, resampling and song marks, while staying quick on a phone. */
private const val SONG_LIMIT = 3

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
    // Shown at once and kept up to date, so a long or stuck run is visible; Cancel stops it.
    val cancel = AtomicBoolean(false)
    val started = System.nanoTime()
    val progress = AlertDialog.Builder(this)
        .setTitle("To audio file · prototype")
        .setMessage("Starting…")
        .setCancelable(false)
        .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }
        .show()
    var lastUpdate = 0L
    fun showProgress(text: String, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastUpdate < 250_000_000L) return
        lastUpdate = now
        val elapsed = (now - started) / 1_000_000_000
        runOnUiThread { if (progress.isShowing) progress.setMessage("$text\n\n${elapsed / 60}:${"%02d".format(elapsed % 60)} elapsed") }
    }
    scope.launch {
        val report = withContext(Dispatchers.IO) {
            try {
                buildReport(playlist, songs, cancel, ::showProgress)
            } catch (t: Throwable) {
                "✗ The prototype stopped: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        val total = (System.nanoTime() - started) / 1_000_000_000
        if (isFinishing || isDestroyed) {
            model.message("Prototype finished in ${total}s — files in Music/Exports")
            return@launch
        }
        progress.dismiss()
        AlertDialog.Builder(this@runPrototypeConvert)
            .setTitle("To audio file · prototype")
            .setMessage("$report\n\nTotal time: ${total / 60}:${"%02d".format(total % 60)}")
            .setPositiveButton("OK", null)
            .show()
    }
}

/** Converts to FLAC and M4A, checks each file, saves it, and describes what passed. */
private fun Activity.buildReport(
    playlist: PlaylistView,
    songs: List<dev.recorddisc.player.library.TrackEntity>,
    cancel: AtomicBoolean,
    showProgress: (String, Boolean) -> Unit,
): String {
    val converter = MediaCodecConverter(this)
    val inputs = songs.map { ConvertInput(it.location, it.displayTitle) }
    val dir = File(cacheDir, "prototype").apply { mkdirs() }
    val safeName = playlist.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Playlist" }
    return buildString {
        appendLine("${songs.size} song(s)${if (playlist.songs.size > SONG_LIMIT) " (first $SONG_LIMIT)" else ""}, 1 s gaps.")
        for (format in listOf(ConvertFormat.Flac, ConvertFormat.M4a)) {
            appendLine()
            val label = format.name.uppercase()
            val out = File(dir, "$safeName.${format.extension}")
            try {
                val formatStart = System.nanoTime()
                val result = converter.convert(
                    ConvertRequest(inputs, format, 1, out.path, playlist.name),
                    onProgress = { p ->
                        val songPercent = ((p.fraction * p.songs - p.song).coerceIn(0f, 1f) * 100).toInt()
                        val spent = (System.nanoTime() - formatStart) / 1_000_000_000.0
                        val left = if (p.fraction > 0.02f) (spent * (1 - p.fraction) / p.fraction).toLong() else null
                        showProgress(
                            "$label: song ${p.song + 1} of ${p.songs} — $songPercent%\n${inputs[p.song].title}\n" +
                                "$label overall ${(p.fraction * 100).toInt()}%" + (left?.let { ", about ${it / 60}:${"%02d".format(it % 60)} left" } ?: ""),
                            false,
                        )
                    },
                    cancelled = { cancel.get() },
                )
                val seconds = (System.nanoTime() - formatStart) / 1_000_000_000.0
                showProgress("$label: checking the file…", true)
                val back = converter.decodedFrames(out.path, result.sampleRate)
                appendLine("$label: ${"%.1f".format(result.totalSamples.toDouble() / result.sampleRate)} s of audio, made in ${"%.1f".format(seconds)} s")
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
                showProgress("$label: saving to Music/Exports…", true)
                appendLine("  saved: ${saveToMusicExports(out, if (format == ConvertFormat.Flac) "audio/flac" else "audio/mp4")}")
            } catch (e: ConvertCancelled) {
                appendLine("$label: cancelled")
                break
            } catch (e: Exception) {
                appendLine("$label: ✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                out.delete()
            }
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
