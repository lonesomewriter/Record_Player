package dev.recorddisc.player.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY COALESCE(title, fileName) COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    /** [pattern] must already be escaped for LIKE; use [searchText]. */
    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :pattern || '%' ESCAPE '\'
           OR artist LIKE '%' || :pattern || '%' ESCAPE '\'
           OR album LIKE '%' || :pattern || '%' ESCAPE '\'
           OR fileName LIKE '%' || :pattern || '%' ESCAPE '\'
        ORDER BY COALESCE(title, fileName) COLLATE NOCASE
        """
    )
    fun search(pattern: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT album AS name, COALESCE(albumArtist, artist) AS artist, MIN(year) AS year, COUNT(*) AS trackCount
        FROM tracks WHERE album IS NOT NULL
        GROUP BY album, COALESCE(albumArtist, artist)
        ORDER BY album COLLATE NOCASE
        """
    )
    fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query(
        """
        SELECT COALESCE(albumArtist, artist) AS name, COUNT(*) AS trackCount
        FROM tracks WHERE COALESCE(albumArtist, artist) IS NOT NULL
        GROUP BY COALESCE(albumArtist, artist)
        ORDER BY name COLLATE NOCASE
        """
    )
    fun observeArtists(): Flow<List<ArtistSummary>>

    @Query("SELECT id, location, sizeBytes, modifiedMs, folder, lyricsLocation FROM tracks")
    suspend fun scanIndex(): List<ScanKey>

    /** A song's folder and lyrics file changed, but the song itself didn't. */
    @Query("UPDATE tracks SET folder = :folder, lyricsLocation = :lyricsLocation WHERE location = :location")
    suspend fun updateSidecar(location: String, folder: String?, lyricsLocation: String?)

    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE location IN (:locations)")
    suspend fun deleteByLocations(locations: List<String>)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int
}

/** Searches titles, artists, albums and file names for [text], treating % and _ literally. */
fun TrackDao.searchText(text: String): Flow<List<TrackEntity>> =
    search(text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))
