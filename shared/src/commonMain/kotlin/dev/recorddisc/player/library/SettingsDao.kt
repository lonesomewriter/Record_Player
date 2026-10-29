package dev.recorddisc.player.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One app setting, stored as text. [dev.recorddisc.player.app.AppSettings] gives them types. */
@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val name: String, val value: String)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Upsert
    suspend fun put(setting: SettingEntity)
}
