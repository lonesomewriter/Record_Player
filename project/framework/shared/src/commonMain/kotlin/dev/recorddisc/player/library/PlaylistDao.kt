package dev.recorddisc.player.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    fun observeItems(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun itemsFor(playlistId: Long): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlists WHERE lower(name) = lower(:name) AND id != :exceptId")
    suspend fun countNamed(name: String, exceptId: Long = -1): Int

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Transaction
    suspend fun insertWithItems(playlist: PlaylistEntity, items: List<PlaylistItemEntity>): Long {
        val id = insert(playlist)
        if (items.isNotEmpty()) insertItems(items.map { it.copy(playlistId = id) })
        return id
    }

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Update
    suspend fun updateItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItems(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deleteItem(playlistId: Long, trackId: Long): Int

    @Transaction
    suspend fun replaceItems(playlistId: Long, items: List<PlaylistItemEntity>) {
        deleteItems(playlistId)
        if (items.isNotEmpty()) insertItems(items)
    }
}
