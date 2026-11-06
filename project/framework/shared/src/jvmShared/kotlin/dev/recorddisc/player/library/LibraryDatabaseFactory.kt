package dev.recorddisc.player.library

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** Finishes a platform's database builder with the bundled SQLite driver (same SQLite on Android and Linux). */
fun buildLibraryDatabase(builder: RoomDatabase.Builder<LibraryDatabase>): LibraryDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
