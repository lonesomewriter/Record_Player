package dev.recorddisc.player.library

import androidx.room.Room
import java.io.File

/** Standard Linux locations, following the freedesktop.org XDG conventions. */
object DesktopPaths {
    private val home: String get() = System.getProperty("user.home")

    /** The user's Music folder from ~/.config/user-dirs.dirs, falling back to ~/Music. */
    fun musicDir(): File {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
        val userDirs = File(config, "user-dirs.dirs")
        val fromConfig = if (userDirs.isFile) parseMusicDir(userDirs.readText(), home) else null
        return File(fromConfig ?: "$home/Music")
    }

    /** Where the app keeps its own data, e.g. ~/.local/share/record-disc. */
    fun dataDir(): File {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
        return File(base, "record-disc")
    }

    fun libraryDatabase(): LibraryDatabase {
        val dir = dataDir().apply { mkdirs() }
        return buildLibraryDatabase(Room.databaseBuilder<LibraryDatabase>(name = File(dir, "library.db").absolutePath))
    }

    /** Reads XDG_MUSIC_DIR="$HOME/Music" style lines; returns null if absent or set to the home folder itself. */
    internal fun parseMusicDir(userDirs: String, home: String): String? {
        val line = userDirs.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("XDG_MUSIC_DIR=") } ?: return null
        val value = line.substringAfter('=').trim().removeSurrounding("\"").replace("\$HOME", home)
        return value.takeIf { it.isNotEmpty() && File(it).absolutePath != File(home).absolutePath }
    }
}
