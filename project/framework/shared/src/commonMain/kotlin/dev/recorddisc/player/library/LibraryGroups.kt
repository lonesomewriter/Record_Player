package dev.recorddisc.player.library

/** An album: songs sharing an album name and album artist, in track order. */
data class Album(val name: String, val artist: String?, val year: Int?, val tracks: List<TrackEntity>) {
    /** Identifies the album across library updates (two artists can both have a "Greatest Hits"). */
    val key: String get() = albumKey(name, artist)
    val durationMs: Long get() = tracks.sumOf { it.durationMs ?: 0 }
    /** The song whose cover art stands for the album, or null when none has art. */
    val cover: TrackEntity? get() = tracks.firstOrNull { it.hasArtwork }
}

/** An artist (album artist where tagged): their albums, newest first, and all their songs. */
data class Artist(val name: String, val albums: List<Album>, val tracks: List<TrackEntity>) {
    /** The newest album's cover, else any song with art. */
    val picture: TrackEntity? get() = albums.firstNotNullOfOrNull { it.cover } ?: tracks.firstOrNull { it.hasArtwork }
}

fun albumKey(name: String, artist: String?): String = "$name\u0000${artist.orEmpty()}"

/** Album artist where tagged, otherwise the song's artist. */
val TrackEntity.albumArtistOrArtist: String? get() = albumArtist ?: artist

private val trackOrder = compareBy<TrackEntity>({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.displayTitle.lowercase() })

/** Albums of [tracks] (songs without an album tag are left out), sorted by name. */
fun groupAlbums(tracks: List<TrackEntity>): List<Album> =
    tracks.filter { it.album != null }
        .groupBy { albumKey(it.album!!, it.albumArtistOrArtist) }
        .values
        .map { songs ->
            val first = songs.first()
            Album(first.album!!, first.albumArtistOrArtist, songs.mapNotNull { it.year }.minOrNull(), songs.sortedWith(trackOrder))
        }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.artist.orEmpty().lowercase() }))

/**
 * Artists of [tracks] (songs without an artist are left out), sorted by name. Their songs run
 * album by album, newest first, then any songs without an album.
 */
fun groupArtists(tracks: List<TrackEntity>): List<Artist> =
    tracks.filter { it.albumArtistOrArtist != null }
        .groupBy { it.albumArtistOrArtist!! }
        .map { (name, songs) ->
            val albums = groupAlbums(songs).sortedWith(compareByDescending<Album> { it.year ?: Int.MIN_VALUE }.thenBy { it.name.lowercase() })
            val loose = songs.filter { it.album == null }.sortedBy { it.displayTitle.lowercase() }
            Artist(name, albums, albums.flatMap { it.tracks } + loose)
        }
        .sortedBy { it.name.lowercase() }
