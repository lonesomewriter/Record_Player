package dev.recorddisc.player.library

import dev.recorddisc.player.ui.counts
import dev.recorddisc.player.ui.meta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryGroupsTest {
    private var nextId = 1L

    private fun track(
        title: String, artist: String?, album: String?, year: Int? = null, trackNumber: Int? = null,
        discNumber: Int? = null, albumArtist: String? = null, art: Boolean = false,
    ) = TrackEntity(
        id = nextId++, location = "/m/$title.flac", fileName = "$title.flac", format = "flac", sizeBytes = 1, modifiedMs = 1,
        title = title, artist = artist, album = album, albumArtist = albumArtist, year = year, trackNumber = trackNumber,
        discNumber = discNumber, genre = null, durationMs = 60_000, hasArtwork = art, tagsReadable = true,
    )

    @Test
    fun albumsAreSortedByNameWithSongsInTrackOrder() {
        val albums = groupAlbums(
            listOf(
                track("Slow Exit", "Neon Coast", "After Hours", 2024, trackNumber = 4),
                track("Midnight Drive", "Neon Coast", "After Hours", 2024, trackNumber = 1),
                track("Bonus", "Neon Coast", "After Hours", 2024, trackNumber = 1, discNumber = 2),
                track("Low Tide", "Neon Coast", "night swim", 2021),
                track("Single", "Neon Coast", null),
            ),
        )
        assertEquals(listOf("After Hours", "night swim"), albums.map { it.name })
        assertEquals(listOf("Midnight Drive", "Slow Exit", "Bonus"), albums[0].tracks.map { it.title })
        assertEquals(180_000, albums[0].durationMs)
        assertEquals("2024 · 3 songs · 3 min", albums[0].meta())
    }

    @Test
    fun sameAlbumNameByDifferentArtistsStaysApart() {
        val albums = groupAlbums(listOf(track("A", "One", "Greatest Hits"), track("B", "Two", "Greatest Hits")))
        assertEquals(listOf("One", "Two"), albums.map { it.artist })
        assertEquals(2, albums.map { it.key }.toSet().size)
    }

    @Test
    fun albumArtistKeepsCompilationsTogether() {
        val albums = groupAlbums(
            listOf(
                track("A", "Guest One", "Mixtape", albumArtist = "Various"),
                track("B", "Guest Two", "Mixtape", albumArtist = "Various"),
            ),
        )
        assertEquals(1, albums.size)
        assertEquals("Various", albums[0].artist)
        assertEquals(listOf("Various"), groupArtists(albums.single().tracks).map { it.name })
    }

    @Test
    fun artistsListAlbumsNewestFirstThenLooseSongs() {
        val artists = groupArtists(
            listOf(
                track("Copper Sky", "Lumen Field", "Northbound", 2022),
                track("Loose", "Lumen Field", null),
                track("Paper Moons", "Lumen Field", "Paper Moons", 2019, art = true),
                track("New", "Lumen Field", "Latest", 2025),
                track("Nobody", null, "Untagged"),
                track("Static Bloom", "the Quiet Hours", "Signal", 2025, art = true),
            ),
        )
        assertEquals(listOf("Lumen Field", "the Quiet Hours"), artists.map { it.name })
        val lumen = artists[0]
        assertEquals(listOf("Latest", "Northbound", "Paper Moons"), lumen.albums.map { it.name })
        assertEquals(listOf("New", "Copper Sky", "Paper Moons", "Loose"), lumen.tracks.map { it.title })
        // Newest album with art.
        assertEquals("Paper Moons", lumen.picture?.title)
        assertEquals("3 albums · 4 songs", lumen.counts())
    }

    @Test
    fun albumsWithoutArtHaveNoCover() {
        val album = groupAlbums(listOf(track("A", "One", "Plain"))).single()
        assertNull(album.cover)
        assertEquals("1 song · 1 min", album.meta())
    }
}
