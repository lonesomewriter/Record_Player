package dev.recorddisc.player.ui

import dev.recorddisc.player.playback.SystemMuteMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Settings → Use system accent (desktop): the desktop's accent colour, followed live. No library
 * is added; it uses tools that come with Linux desktops:
 * 1. xdg-desktop-portal (GNOME 47+, KDE Plasma 6) through `busctl`: the `accent-color` setting, an
 *    RGB triple. Changes are picked up by watching the portal's SettingChanged signal.
 * 2. Otherwise GNOME's own `accent-color` setting through `gsettings` (a name such as "blue"),
 *    watched with `gsettings monitor`.
 * When neither answers, [accent] is null and Settings greys the switch out.
 */
class SystemAccentMonitor : AutoCloseable {
    private val _accent = MutableStateFlow<SystemAccent?>(null)
    val accent: StateFlow<SystemAccent?> = _accent.asStateFlow()
    private val watchers = mutableListOf<Process>()

    init {
        refresh()
        watch("busctl", "--user", "monitor", "--match", "type='signal',interface='org.freedesktop.portal.Settings',member='SettingChanged'")
        watch("gsettings", "monitor", GNOME_SCHEMA, GNOME_KEY)
    }

    private fun refresh() {
        val name = SystemMuteMonitor.run("gsettings", "get", GNOME_SCHEMA, GNOME_KEY)?.let(::parseGnomeName)
        val portal = SystemMuteMonitor.run(
            "busctl", "--user", "call", "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.Settings", "ReadOne", "ss", "org.freedesktop.appearance", "accent-color",
        )?.let(::parsePortalColor)
        _accent.value = when {
            portal != null -> SystemAccent(portal, name?.let { GNOME_ACCENTS[it]?.first })
            name != null -> GNOME_ACCENTS[name]?.let { (label, argb) -> SystemAccent(argb, label) }
            else -> null
        }
    }

    /** Re-reads the accent whenever [command] prints something (a change notice). */
    private fun watch(vararg command: String) {
        val process = runCatching { ProcessBuilder(*command).redirectErrorStream(true).start() }.getOrNull() ?: return
        watchers += process
        Thread({
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { refresh() } } }
        }, "system-accent-monitor").apply { isDaemon = true; start() }
    }

    override fun close() {
        watchers.forEach { it.destroy() }
    }

    companion object {
        private const val GNOME_SCHEMA = "org.gnome.desktop.interface"
        private const val GNOME_KEY = "accent-color"

        /** GNOME's accent names, their display names and colours (libadwaita). */
        internal val GNOME_ACCENTS: Map<String, Pair<String, Long>> = mapOf(
            "blue" to ("Blue" to 0xFF3584E4), "teal" to ("Teal" to 0xFF2190A4), "green" to ("Green" to 0xFF3A944A),
            "yellow" to ("Yellow" to 0xFFC88800), "orange" to ("Orange" to 0xFFED5B00), "red" to ("Red" to 0xFFE62D42),
            "pink" to ("Pink" to 0xFFD56199), "purple" to ("Purple" to 0xFF9141AC), "slate" to ("Slate" to 0xFF6F8396),
        )

        /**
         * busctl's answer, e.g. `v (ddd) 0.207843 0.517647 0.894118`, to an opaque ARGB colour.
         * The portal reports values outside 0–1 when no accent is set; that gives null.
         */
        internal fun parsePortalColor(output: String): Long? {
            val match = Regex("""\(ddd\)\s+(\S+)\s+(\S+)\s+(\S+)""").find(output) ?: return null
            val channels = match.groupValues.drop(1).map { it.toDoubleOrNull() ?: return null }
            if (channels.any { it < 0.0 || it > 1.0 }) return null
            val (r, g, b) = channels.map { (it * 255).roundToInt() }
            return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
        }

        /** gsettings' answer, e.g. `'teal'`, to a known GNOME accent name. */
        internal fun parseGnomeName(output: String): String? =
            output.trim().removePrefix("accent-color:").trim().trim('\'', '"').lowercase().takeIf { it in GNOME_ACCENTS }
    }
}
