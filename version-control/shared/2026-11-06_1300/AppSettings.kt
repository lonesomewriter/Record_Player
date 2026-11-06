package dev.recorddisc.player.app

import dev.recorddisc.player.playback.RepeatMode

/** The accent presets from the mockups. */
enum class AccentPreset(val argb: Long) {
    Gold(0xFFE0A458),
    Coral(0xFFE8836A),
    Mint(0xFF7FD1B9),
    Sky(0xFF7FB8E8),
    Lavender(0xFFB39DDB),
    Rose(0xFFE79AB5),
}

/** Settings → Theme. System follows the phone or desktop setting. */
enum class ThemeMode { System, Dark, Light }

/** Typed view of the settings table. Unknown or malformed values fall back to the defaults. */
data class AppSettings(
    /** Music folder location, platform-dependent format.
     *  Desktop: plain path string.
     *  Android: SAF tree URI string.
     *  iOS: document-aware URL string.
     *  Null until a music folder has been chosen. */
    val musicFolder: String? = null,
    /** True once first run finished with music found. */
    val setupDone: Boolean = false,
    /** A preset, or null for [customAccent]. */
    val accent: AccentPreset? = AccentPreset.Gold,
    val customAccent: Long = AccentPreset.Gold.argb,
    val theme: ThemeMode = ThemeMode.Dark,
    /** Android: take the accent from the current track's cover art. */
    val dynamicColor: Boolean = true,
    /** Reopen the last queue at the same song and position (paused) when the app starts. */
    val resumeOnStart: Boolean = true,
    val pauseOnUnplug: Boolean = true,
    /** Pause when the device's (or desktop's) output is muted or at volume 0. */
    val pauseOnMute: Boolean = true,
    /** Resume when unmuted, but only if muting is what paused playback. */
    val resumeOnUnmute: Boolean = false,
    /** Desktop: follow the desktop's accent colour instead of the chosen one. */
    val useSystemAccent: Boolean = false,
    /** Settings → Battery Saver (2026-11-05): all motion off (spinning disc, visualizer, transitions). Manual, off by default. */
    val batterySaver: Boolean = false,
    /** Desktop: start the next song with no silence between tracks. */
    val gapless: Boolean = true,
    /** Desktop: rescan by itself when files in the music folder change. */
    val watchFolder: Boolean = true,
    /** The Lyrics toggle on Now Playing / Home. */
    val showLyrics: Boolean = false,
    /** Playback modes restored on the next launch. */
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
) {
    val accentArgb: Long get() = accent?.argb ?: customAccent

    /** Converts a raw string value to a music folder format based on detection.
     *  - Strings starting with "file://" or containing UUID-like patterns are treated as iOS document URIs.
     *  - Strings containing "tree" and ":" are treated as Android tree URIs.
     *  - Otherwise treated as desktop path. */
    fun parseMusicFolder(): String? {
        if (musicFolder?.isBlank() == true) return null
        
        // iOS document URI format
        if (musicFolder?.startsWith("file://") == true) return musicFolder
        
        // Android tree URI format detection (contains colon and 'tree' keyword)
        // This is a heuristic; actual SAF URI format varies
        if (musicFolder?.contains(":") == true && musicFolder?.contains("tree") == true) return musicFolder
        
        // Desktop path format
        return musicFolder
    }

    companion object {
        const val MUSIC_FOLDER = "musicFolder"
        const val SETUP_DONE = "setupDone"
        const val THEME = "theme"
        const val ACCENT = "accent"
        const val CUSTOM_ACCENT = "customAccent"
        const val DYNAMIC_COLOR = "dynamicColor"
        const val RESUME_ON_START = "resumeOnStart"
        const val PAUSE_ON_UNPLUG = "pauseOnUnplug"
        const val PAUSE_ON_MUTE = "pauseOnMute"
        const val RESUME_ON_UNMUTE = "resumeOnUnmute"
        const val USE_SYSTEM_ACCENT = "useSystemAccent"
        const val BATTERY_SAVER = "batterySaver"
        const val GAPLESS = "gapless"
        const val WATCH_FOLDER = "watchFolder"
        const val SHOW_LYRICS = "showLyrics"
        const val SHUFFLE = "shuffle"
        const val REPEAT_MODE = "repeatMode"
        const val CUSTOM = "Custom"

        fun from(values: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun flag(name: String, default: Boolean) = values[name]?.toBooleanStrictOrNull() ?: default
            val accentName = values[ACCENT]
            val folderStr = values[MUSIC_FOLDER]
            val blank = folderStr?.isBlank() == true
            val result = if (!blank) folderStr else null
            return AppSettings(
                musicFolder = result,
                setupDone = flag(SETUP_DONE, d.setupDone),
                theme = ThemeMode.entries.firstOrNull { it.name == values[THEME] } ?: d.theme,
                accent = when (accentName) {
                    null -> d.accent
                    CUSTOM -> null
                    else -> AccentPreset.entries.firstOrNull { it.name == accentName } ?: d.accent
                },
                customAccent = values[CUSTOM_ACCENT]?.toLongOrNull(16)?.let { it or 0xFF000000 } ?: d.customAccent,
                dynamicColor = flag(DYNAMIC_COLOR, d.dynamicColor),
                resumeOnStart = flag(RESUME_ON_START, d.resumeOnStart),
                pauseOnUnplug = flag(PAUSE_ON_UNPLUG, d.pauseOnUnplug),
                pauseOnMute = flag(PAUSE_ON_MUTE, d.pauseOnMute),
                resumeOnUnmute = flag(RESUME_ON_UNMUTE, d.resumeOnUnmute),
                useSystemAccent = flag(USE_SYSTEM_ACCENT, d.useSystemAccent),
                batterySaver = flag(BATTERY_SAVER, d.batterySaver),
                gapless = flag(GAPLESS, d.gapless),
                watchFolder = flag(WATCH_FOLDER, d.watchFolder),
                showLyrics = flag(SHOW_LYRICS, d.showLyrics),
                shuffle = flag(SHUFFLE, d.shuffle),
                repeatMode = RepeatMode.entries.firstOrNull { it.name == values[REPEAT_MODE] } ?: d.repeatMode,
            )
        }
    }
}
