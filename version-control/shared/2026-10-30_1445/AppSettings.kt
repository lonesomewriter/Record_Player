package dev.recorddisc.player.app

/** The accent presets from the mockups. */
enum class AccentPreset(val argb: Long) {
    Gold(0xFFE0A458),
    Coral(0xFFE8836A),
    Mint(0xFF7FD1B9),
    Sky(0xFF7FB8E8),
    Lavender(0xFFB39DDB),
    Rose(0xFFE79AB5),
}

/** Typed view of the settings table. Unknown or malformed values fall back to the defaults. */
data class AppSettings(
    /** Desktop: a folder path. Android: the folder grant's tree URI. Null until chosen. */
    val musicFolder: String? = null,
    /** True once first run finished with music found. */
    val setupDone: Boolean = false,
    /** A preset, or null for [customAccent]. */
    val accent: AccentPreset? = AccentPreset.Gold,
    val customAccent: Long = AccentPreset.Gold.argb,
    /** Android 12+: take the accent from the wallpaper. */
    val dynamicColor: Boolean = true,
    /** Reopen the last queue at the same song and position (paused) when the app starts. */
    val resumeOnStart: Boolean = true,
    val pauseOnUnplug: Boolean = true,
    /** Desktop: start the next song with no silence between tracks. */
    val gapless: Boolean = true,
    /** Desktop: rescan by itself when files in the music folder change. */
    val watchFolder: Boolean = true,
) {
    val accentArgb: Long get() = accent?.argb ?: customAccent

    companion object {
        const val MUSIC_FOLDER = "musicFolder"
        const val SETUP_DONE = "setupDone"
        const val ACCENT = "accent"
        const val CUSTOM_ACCENT = "customAccent"
        const val DYNAMIC_COLOR = "dynamicColor"
        const val RESUME_ON_START = "resumeOnStart"
        const val PAUSE_ON_UNPLUG = "pauseOnUnplug"
        const val GAPLESS = "gapless"
        const val WATCH_FOLDER = "watchFolder"
        const val CUSTOM = "Custom"

        fun from(values: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun flag(name: String, default: Boolean) = values[name]?.toBooleanStrictOrNull() ?: default
            val accentName = values[ACCENT]
            return AppSettings(
                musicFolder = values[MUSIC_FOLDER]?.takeIf { it.isNotBlank() },
                setupDone = flag(SETUP_DONE, d.setupDone),
                accent = when (accentName) {
                    null -> d.accent
                    CUSTOM -> null
                    else -> AccentPreset.entries.firstOrNull { it.name == accentName } ?: d.accent
                },
                customAccent = values[CUSTOM_ACCENT]?.toLongOrNull(16)?.let { it or 0xFF000000 } ?: d.customAccent,
                dynamicColor = flag(DYNAMIC_COLOR, d.dynamicColor),
                resumeOnStart = flag(RESUME_ON_START, d.resumeOnStart),
                pauseOnUnplug = flag(PAUSE_ON_UNPLUG, d.pauseOnUnplug),
                gapless = flag(GAPLESS, d.gapless),
                watchFolder = flag(WATCH_FOLDER, d.watchFolder),
            )
        }
    }
}
