package dev.recorddisc.player.convert

import dev.recorddisc.player.library.DesktopPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop Convert to Audio: offers the formats this machine's GStreamer can make, saves to
 * `~/Music/Exports` unless a file was chosen, and writes through a hidden `.part` file that only
 * takes the real name once the conversion has finished, so a cancelled or failed run never leaves
 * something that looks like a finished file.
 */
object DesktopConvert {
    private val converter: GstConverter? by lazy { runCatching { GstConverter() }.getOrNull() }
    private val home = System.getProperty("user.home")

    fun exportsFolder(): File = File(DesktopPaths.musicDir(), "Exports")

    /** A path as people read it: the home folder as "~". */
    fun shown(file: File): String = file.absolutePath.let { if (home != null && it.startsWith("$home/")) "~" + it.removePrefix(home) else it }

    fun formats(): List<ConvertFormatOption> {
        val gst = converter ?: return emptyList()
        return ConvertFormat.entries.map { format ->
            val ok = runCatching { gst.supports(format) }.getOrDefault(false)
            ConvertFormatOption(format, ok, if (ok) null else missingPackage(format))
        }
    }

    private fun missingPackage(format: ConvertFormat): String = when (format) {
        ConvertFormat.M4a -> "needs gstreamer1.0-libav"
        ConvertFormat.Mp3, ConvertFormat.Flac, ConvertFormat.Wav -> "needs gstreamer1.0-plugins-good"
        ConvertFormat.Ogg, ConvertFormat.Opus -> "needs gstreamer1.0-plugins-base"
    }

    suspend fun convert(job: ConvertJob, onProgress: (ConvertProgress) -> Unit): ConvertSaved = withContext(Dispatchers.IO) {
        val gst = converter ?: error("GStreamer isn't available, so files can't be converted")
        val target = job.destination?.let(::File) ?: uniqueFile(exportsFolder(), job.fileName)
        val folder = target.absoluteFile.parentFile
        if (!folder.isDirectory && !folder.mkdirs()) error("Couldn't create ${shown(folder)}")
        if (folder.usableSpace in 0 until job.bytesNeeded) {
            error("Not enough free space in ${shown(folder)}: this needs about ${job.bytesNeeded / (1024 * 1024)} MB")
        }
        val part = File(folder, ".${target.name}.part")
        val context = currentCoroutineContext()
        try {
            gst.convert(
                ConvertRequest(job.inputs, job.format, job.gapSeconds, part.absolutePath, job.title, job.evenOutVolume),
                onProgress,
                cancelled = { !context.isActive },
            )
            // Cancelled just as it finished: still nothing is saved.
            if (!context.isActive) throw ConvertCancelled()
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: ConvertCancelled) {
            part.delete()
            throw CancellationException("Conversion cancelled")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        ConvertSaved(shownAs = shown(target), location = target.absolutePath, folder = folder.absolutePath)
    }

    /** [name] in [folder], or "name (2).ext", "name (3).ext"… when it's taken. */
    internal fun uniqueFile(folder: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var candidate = File(folder, name)
        var n = 2
        while (candidate.exists()) candidate = File(folder, "$base ($n)${if (ext.isEmpty()) "" else ".$ext"}").also { n++ }
        return candidate
    }
}
