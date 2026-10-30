package dev.recorddisc.player.ui

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the desktop's dark/light preference from the freedesktop Settings portal
 * (`org.freedesktop.appearance color-scheme`, provided by GNOME, KDE and others) through the
 * system `gdbus` tool. Local D-Bus only; nothing leaves the machine.
 */
internal object LinuxColorScheme {
    private const val GDBUS = "/usr/bin/gdbus"
    private const val MAX_OUTPUT = 4096

    /** True for dark, false for light, null when there's no preference or it can't be read. */
    fun prefersDark(): Boolean? {
        if (!File(GDBUS).canExecute()) return null
        // ReadOne is the current method; older portals only have Read.
        return call("ReadOne") ?: call("Read")
    }

    private fun call(method: String): Boolean? {
        val process = runCatching {
            ProcessBuilder(
                GDBUS, "call", "--session",
                "--dest", "org.freedesktop.portal.Desktop",
                "--object-path", "/org/freedesktop/portal/desktop",
                "--method", "org.freedesktop.portal.Settings.$method",
                "org.freedesktop.appearance", "color-scheme",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val out = process.inputStream.use { String(it.readNBytes(MAX_OUTPUT)) }
            parseColorScheme(out)
        } finally {
            process.destroyForcibly()
        }
    }
}

/** Parses gdbus output such as `(<uint32 1>,)` or `(<<uint32 1>>,)`: 1 = dark, 2 = light, else null. */
internal fun parseColorScheme(out: String): Boolean? =
    when (Regex("""uint32 (\d+)""").find(out)?.groupValues?.get(1)) {
        "1" -> true
        "2" -> false
        else -> null
    }
