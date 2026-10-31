package dev.recorddisc.player.library

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [TrackEntity::class, SettingEntity::class, PlaylistEntity::class, PlaylistItemEntity::class],
    version = 4,
    // Version 2 added settings, version 3 added song folders and lyrics files, and version 4 added playlists.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
@ConstructedBy(LibraryDatabaseConstructor::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun settings(): SettingsDao
    abstract fun playlists(): PlaylistDao
}

// Room's compiler generates the actual implementations for each platform.
@Suppress("KotlinNoActualForExpect")
expect object LibraryDatabaseConstructor : RoomDatabaseConstructor<LibraryDatabase> {
    override fun initialize(): LibraryDatabase
}
