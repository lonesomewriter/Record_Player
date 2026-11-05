package dev.recorddisc.player.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.recorddisc.player.app.LibraryPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shared Android platform plus Convert to Audio, which lives in the app because the converter
 * uses Media3's resampler (a dependency of the app, not of the shared code).
 *
 * The file is made in the app's cache, then copied to where it belongs:
 * - by default **Music/Exports**, through the shared media store (Android 10+; no permission);
 * - or the file chosen with **Change** (the system file picker; any Android version).
 * Cancelling or failing deletes the cache file and anything already created at the destination.
 */
class AndroidConvertPlatform(context: Context, private val base: LibraryPlatform) : LibraryPlatform by base {
    private val context = context.applicationContext
    private val converter = MediaCodecConverter(this.context)
    private val workFolder get() = File(context.cacheDir, "convert")

    override fun convertFormats(): List<ConvertFormatOption> = androidConvertFormats()

    /** Android 8–9 can't write to Music/Exports without a storage permission, so there the user chooses. */
    override val convertDefaultFolder: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${Environment.DIRECTORY_MUSIC}/Exports" else null

    override suspend fun convertPlaylist(job: ConvertJob, onProgress: (ConvertProgress) -> Unit): ConvertSaved = withContext(Dispatchers.IO) {
        val destination = job.destination?.let(Uri::parse)
        check(destination != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Choose where to save the file first" }
        // Anything left from a run the app was closed during.
        workFolder.deleteRecursively()
        workFolder.mkdirs()
        // The file is made in the cache and then copied, so both places need the room.
        val needed = job.bytesNeeded
        if (workFolder.usableSpace in 0 until needed || (destination == null && Environment.getExternalStorageDirectory().usableSpace in 0 until needed)) {
            error("Not enough free space: this needs about ${needed / (1024 * 1024)} MB")
        }
        val temp = File(workFolder, job.fileName)
        val coroutine = currentCoroutineContext()
        try {
            converter.convert(
                ConvertRequest(job.inputs, job.format, job.gapSeconds, temp.path, job.title, job.evenOutVolume),
                onProgress,
                cancelled = { !coroutine.isActive },
            )
            // Cancelled just as it finished: still nothing is saved.
            if (!coroutine.isActive) throw ConvertCancelled()
            if (destination != null) saveToChosen(temp, destination) else saveToExports(temp, job.format)
        } catch (e: ConvertCancelled) {
            destination?.let(::deleteChosen)
            throw CancellationException("Conversion cancelled")
        } catch (e: Throwable) {
            destination?.let(::deleteChosen)
            throw e
        } finally {
            temp.delete()
        }
    }

    /** Music/Exports through the media store; it renames on a clash ("Road Trip (1).flac"). */
    private suspend fun saveToExports(temp: File, format: ConvertFormat): ConvertSaved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, temp.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(format))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Exports")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Android didn't let the app save to Music/Exports")
        try {
            copy(temp, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val name = displayName(uri) ?: temp.name
        return ConvertSaved("${Environment.DIRECTORY_MUSIC}/Exports/$name", uri.toString(), folder = null)
    }

    private suspend fun saveToChosen(temp: File, uri: Uri): ConvertSaved {
        copy(temp, uri)
        return ConvertSaved(displayName(uri) ?: temp.name, uri.toString(), folder = null)
    }

    private suspend fun copy(temp: File, uri: Uri) {
        val coroutine = currentCoroutineContext()
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the destination file")
        out.use { stream ->
            temp.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    if (!coroutine.isActive) throw CancellationException("Conversion cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    stream.write(buffer, 0, n)
                }
            }
        }
    }

    /** The file the picker created is removed again when nothing usable was written to it. */
    private fun deleteChosen(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        fun mimeType(format: ConvertFormat): String = when (format) {
            ConvertFormat.Flac -> "audio/flac"
            ConvertFormat.M4a -> "audio/mp4"
            ConvertFormat.Opus, ConvertFormat.Ogg -> "audio/ogg"
            ConvertFormat.Wav -> "audio/x-wav"
            ConvertFormat.Mp3 -> "audio/mpeg"
        }
    }
}
