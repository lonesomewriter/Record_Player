package dev.recorddisc.player.library

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [TrackEntity::class, SettingEntity::class],
    version = 2,
    // Version 2 added the settings table.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(LibraryDatabaseConstructor::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun settings(): SettingsDao
}

// Room's compiler generates the actual implementations for each platform.
@Suppress("KotlinNoActualForExpect")
expect object LibraryDatabaseConstructor : RoomDatabaseConstructor<LibraryDatabase> {
    override fun initialize(): LibraryDatabase
}
