package dev.recorddisc.player.library

import kotlin.test.Test
import kotlin.test.assertEquals

class M3uTest {
    private val songs = listOf(
        TrackEntity(
            id = 1,
            location = "/music/Artist/01 Song.flac",
            fileName = "01 Song.flac",
            format = "flac",
            sizeBytes = 10,
            modifiedMs = 1,
            title = "Song",
            artist = "Artist",
            album = null,
            albumArtist = null,
            year = null,
            trackNumber = null,
            discNumber = null,
            genre = null,
            durationMs = 180_000,
            hasArtwork = false,
            tagsReadable = true,
        ),
    )

    @Test
    fun formatsRelativeAndFullPathsWithTrackInfo() {
        val entries = listOf(M3uTrack("/music/Artist/Song.flac", "Artist/Song.flac", "Song", "Artist", 180_400))

        assertEquals(
            "#EXTM3U\n#PLAYLIST:Road Trip\n#EXTINF:180,Artist - Song\nArtist/Song.flac\n",
            M3u.format(entries, M3uPathMode.Relative, playlistName = "Road Trip"),
        )
        assertEquals(
            "#EXTM3U\n/music/Artist/Song.flac\n",
            M3u.format(entries, M3uPathMode.Full, trackInfo = false),
        )
    }

    @Test
    fun parsesPlaylistNameExtinfAndPaths() {
        val parsed = M3u.parse(
            "\uFEFF#EXTM3U\r\n#PLAYLIST: Favorites \r\n#EXTINF:180,Artist - Song\r\nArtist/Song.flac\r\n# a comment\r\nloose.ogg\r\n",
        )

        assertEquals("Favorites", parsed.playlistName)
        assertEquals(
            listOf(
                M3uEntry("Artist/Song.flac", "Song", "Artist", 180_000),
                M3uEntry("loose.ogg", null, null, null),
            ),
            parsed.entries,
        )
    }

    @Test
    fun matchesPathsBeforeTagsAndUsesDurationToleranceForFallback() {
        val entries = listOf(
            M3uEntry("known-path.flac", "Wrong title", "Wrong artist", 5_000),
            M3uEntry("not-on-disk.flac", " Song ", " ARTIST ", 182_000),
            M3uEntry("missing.flac", "Song", "Artist", 190_000),
            M3uEntry("no-tags.flac", null, null, null),
        )

        val result = M3u.match(entries, songs) { path ->
            if (path == "known-path.flac") songs.single().location else null
        }

        assertEquals(listOf(entries[0], entries[1]), result.matched.map { it.entry })
        assertEquals(listOf(1L, 1L), result.matched.map { it.track.id })
        assertEquals(listOf(entries[2], entries[3]), result.notFound)
    }
}
