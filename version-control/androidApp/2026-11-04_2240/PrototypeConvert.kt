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
import kotlinx.coroutines.delay
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

/** Asks which format(s) to try (so M4A can be tested on its own), then runs the check. */
internal fun Activity.runPrototypeConvert(scope: CoroutineScope, model: AppModel, playlist: PlaylistView) {
    if (playlist.songs.isEmpty()) {
        model.message("Prototype: add songs to this playlist first")
        return
    }
    val choices = arrayOf("FLAC", "M4A", "Both")
    AlertDialog.Builder(this)
        .setTitle("Convert prototype: which format?")
        .setItems(choices) { _, which ->
            val formats = when (which) {
                0 -> listOf(ConvertFormat.Flac)
                1 -> listOf(ConvertFormat.M4a)
                else -> listOf(ConvertFormat.Flac, ConvertFormat.M4a)
            }
            startPrototype(scope, model, playlist, formats)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/**
 * Joins the playlist's first songs (1 s gaps) into the chosen formats, checks each file on the
 * phone (length, song marks, tags), saves it to Music/Exports and shows what passed. Every step is
 * also written to "Download/RecordPlayer prototype log.txt" as it happens, so if the app goes down
 * the last line shows where.
 */
private fun Activity.startPrototype(scope: CoroutineScope, model: AppModel, playlist: PlaylistView, formats: List<ConvertFormat>) {
    val songs = playlist.songs.take(SONG_LIMIT)
    val stepLog = StepLog(this)
    stepLog.write("=== ${playlist.name}: ${formats.joinToString { it.name }} · ${songs.size} song(s) · Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})")
    // A crash in the app's own code is written down before the app closes.
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        stepLog.write("CRASH on ${thread.name}: ${error.javaClass.name}: ${error.message}\n${error.stackTraceToString().take(3_000)}")
        previousHandler?.uncaughtException(thread, error)
    }
    model.message("Prototype: converting ${playlist.name}…")
    val cancel = AtomicBoolean(false)
    val started = System.nanoTime()
    val progress = AlertDialog.Builder(this)
        .setTitle("To audio file · prototype")
        .setMessage("Starting…")
        .setCancelable(false)
        .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }
        .show()
    val status = java.util.concurrent.atomic.AtomicReference("Starting…") // set from the worker, read by the clock
    fun render() {
        val elapsed = (System.nanoTime() - started) / 1_000_000_000
        if (progress.isShowing) progress.setMessage("${status.get()}\n\n${elapsed / 60}:${"%02d".format(elapsed % 60)} elapsed")
    }
    // The clock ticks every second on its own, also during the checking and saving steps.
    val clock = scope.launch {
        while (true) {
            render()
            delay(1_000)
        }
    }
    scope.launch {
        val report = withContext(Dispatchers.IO) {
            try {
                buildReport(playlist, songs, formats, cancel, stepLog) { status.set(it) }
            } catch (t: Throwable) {
                stepLog.write("stopped: ${t.javaClass.simpleName}: ${t.message}")
                "✗ The prototype stopped: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        clock.cancel()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        val total = (System.nanoTime() - started) / 1_000_000_000
        stepLog.write("finished in ${total}s")
        if (isFinishing || isDestroyed) {
            model.message("Prototype finished in ${total}s — files in Music/Exports, log in Download")
            return@launch
        }
        progress.dismiss()
        AlertDialog.Builder(this@startPrototype)
            .setTitle("To audio file · prototype")
            .setMessage("$report\n\nTotal time: ${total / 60}:${"%02d".format(total % 60)}\nStep log: Download/${StepLog.NAME}")
            .setPositiveButton("OK", null)
            .show()
    }
}

/** Converts to each format, checks the file, saves it, and describes what passed. */
private fun Activity.buildReport(
    playlist: PlaylistView,
    songs: List<dev.recorddisc.player.library.TrackEntity>,
    formats: List<ConvertFormat>,
    cancel: AtomicBoolean,
    stepLog: StepLog,
    status: (String) -> Unit,
): String {
    val converter = MediaCodecConverter(this) { stepLog.write(it) }
    val inputs = songs.map { ConvertInput(it.location, it.displayTitle) }
    val dir = File(cacheDir, "prototype").apply { mkdirs() }
    val safeName = playlist.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Playlist" }
    return buildString {
        appendLine("${songs.size} song(s)${if (playlist.songs.size > SONG_LIMIT) " (first $SONG_LIMIT)" else ""}, 1 s gaps.")
        for (format in formats) {
            appendLine()
            val label = format.name.uppercase()
            val out = File(dir, "$safeName.${format.extension}")
            stepLog.write("--- $label")
            try {
                val formatStart = System.nanoTime()
                val result = converter.convert(
                    ConvertRequest(inputs, format, 1, out.path, playlist.name),
                    onProgress = { p ->
                        val songPercent = ((p.fraction * p.songs - p.song).coerceIn(0f, 1f) * 100).toInt()
                        val spent = (System.nanoTime() - formatStart) / 1_000_000_000.0
                        val left = if (p.fraction > 0.02f) (spent * (1 - p.fraction) / p.fraction).toLong() else null
                        status(
                            "$label: song ${p.song + 1} of ${p.songs} — $songPercent%\n${inputs[p.song].title}\n" +
                                "$label overall ${(p.fraction * 100).toInt()}%" + (left?.let { ", about ${it / 60}:${"%02d".format(it % 60)} left" } ?: ""),
                        )
                    },
                    cancelled = { cancel.get() },
                )
                val seconds = (System.nanoTime() - formatStart) / 1_000_000_000.0
                stepLog.write("$label made in ${"%.1f".format(seconds)} s; checking")
                val back = converter.decodedFrames(out.path, result.sampleRate) { f -> status("$label: checking the file — ${(f * 100).toInt()}%") }
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
                status("$label: saving to Music/Exports…")
                val saved = saveToMusicExports(out, if (format == ConvertFormat.Flac) "audio/flac" else "audio/mp4")
                stepLog.write("$label saved: $saved")
                appendLine("  saved: $saved")
            } catch (e: ConvertCancelled) {
                stepLog.write("$label cancelled")
                appendLine("$label: cancelled")
                break
            } catch (e: Exception) {
                stepLog.write("$label failed: ${e.javaClass.simpleName}: ${e.message}")
                appendLine("$label: ✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                out.delete()
            }
        }
    }
}

/**
 * The prototype's step log, "Download/RecordPlayer prototype log.txt". Each line is appended and
 * flushed straight to storage, so it survives the app being killed mid-run.
 */
private class StepLog(private val activity: Activity) {
    companion object {
        const val NAME = "RecordPlayer prototype log.txt"
    }

    private val start = System.nanoTime()
    private val target: android.net.Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) findOrCreate() else null
    private val fallback = File(activity.getExternalFilesDir(null), NAME)

    @Synchronized
    fun write(line: String) {
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        val text = "[${"%7.1f".format(seconds)} s] $line\n".toByteArray()
        runCatching {
            if (target != null) {
                activity.contentResolver.openFileDescriptor(target, "wa")!!.use { fd ->
                    java.io.FileOutputStream(fd.fileDescriptor).use { out -> out.write(text); out.fd.sync() }
                }
            } else {
                java.io.FileOutputStream(fallback, true).use { out -> out.write(text); out.fd.sync() }
            }
        }
    }

    private fun findOrCreate(): android.net.Uri? = runCatching {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        activity.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(NAME), null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return@runCatching android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        activity.contentResolver.insert(collection, values)
    }.getOrNull()
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
