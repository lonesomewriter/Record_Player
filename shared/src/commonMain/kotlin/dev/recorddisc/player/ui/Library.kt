package dev.recorddisc.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.library.Album
import dev.recorddisc.player.library.Artist
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.library.displayTitle
import kotlin.math.max

enum class LibraryTab { Songs, Albums, Artists }

/** An album or artist page opened from the Library. */
sealed interface LibraryPage {
    data class AlbumPage(val key: String) : LibraryPage
    data class ArtistPage(val name: String) : LibraryPage
}

/** The Library's tab and its open pages; Back closes the top page. */
@Stable
class LibraryNav(tab: LibraryTab = LibraryTab.Songs, vararg pages: LibraryPage) {
    var tab by mutableStateOf(tab)
    var pages by mutableStateOf(pages.toList())
        private set
    val page: LibraryPage? get() = pages.lastOrNull()

    fun open(page: LibraryPage) {
        pages = pages + page
    }

    fun back() {
        pages = pages.dropLast(1)
    }
}

private const val SOON = "Coming in a later update"

/** "Search songs, albums, artists". */
@Composable
fun LibrarySearch(model: AppModel, modifier: Modifier = Modifier, textSize: Int = 13) {
    val query by model.libraryQuery.collectAsState()
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(disc.panel2).border(1.dp, disc.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DiscIcons.Search, null, Modifier.size(16.dp), tint = disc.muted)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search songs, albums, artists", color = disc.muted.copy(alpha = 0.7f), fontSize = textSize.sp)
            BasicTextField(
                query, model::setLibraryQuery, Modifier.fillMaxWidth().semantics { contentDescription = "Search" },
                singleLine = true, textStyle = TextStyle(color = disc.text, fontSize = textSize.sp), cursorBrush = SolidColor(disc.accentReadable),
            )
        }
        if (query.isNotEmpty()) {
            Text("✕", Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = "Clear search") { model.setLibraryQuery("") }.padding(4.dp), color = disc.muted, fontSize = 12.sp)
        }
    }
}

/** The Songs / Albums / Artists pills. */
@Composable
fun LibraryTabs(selected: LibraryTab, textSize: Int = 12, onSelect: (LibraryTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tab in LibraryTab.entries) {
            val on = tab == selected
            Text(
                tab.name,
                Modifier.clip(RoundedCornerShape(50)).background(if (on) disc.accent else Color.Transparent)
                    .border(1.dp, if (on) disc.accent else disc.line, RoundedCornerShape(50))
                    .clickable { onSelect(tab) }.padding(horizontal = 11.dp, vertical = 7.dp),
                color = if (on) disc.accentInk else disc.muted, fontSize = textSize.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** Shown when a tab has nothing to list: no search results, or no tagged albums/artists. */
@Composable
fun NoMatches(model: AppModel, tab: LibraryTab) {
    val query by model.libraryQuery.collectAsState()
    Text(
        when {
            query.isNotBlank() -> "No matching ${tab.name.lowercase()}."
            tab == LibraryTab.Albums -> "No albums yet. Songs appear here once they have an album tag."
            tab == LibraryTab.Artists -> "No artists yet. Songs appear here once they have an artist tag."
            else -> "No songs yet."
        },
        Modifier.padding(vertical = 18.dp), color = disc.muted, fontSize = 13.sp, lineHeight = 20.sp,
    )
}

/** An album or artist page whose music was removed from the library while it was open. */
@Composable
private fun Gone(what: String) =
    Text("This $what is no longer in your library.", Modifier.padding(vertical = 18.dp), color = disc.muted, fontSize = 13.sp)

/** ⋮ on a song: the per-song actions of later parts. */
@Composable
fun SongMenuButton(model: AppModel, track: TrackEntity, size: Dp = 28.dp) {
    var open by remember { mutableStateOf(false) }
    val editSong = LocalEditSong.current
    val editLyrics = LocalEditLyrics.current
    Box {
        Text(
            "⋮", Modifier.width(size).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "More options") { open = true }.padding(vertical = 4.dp),
            color = disc.muted, fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (item in listOf("Add to playlist", "Edit metadata", "Edit lyrics")) {
                val ready = item != "Add to playlist"
                DropdownMenuItem(
                    text = { Text(item, color = if (ready) disc.text else disc.muted) },
                    trailingIcon = { if (!ready) SoonTag() },
                    onClick = {
                        open = false
                        when {
                            !ready -> model.message(SOON)
                            item == "Edit lyrics" -> editLyrics(track)
                            else -> editSong(track)
                        }
                    },
                )
            }
        }
    }
}

/** Album cover (or an artist's round picture) at [size], loaded at a matching resolution. */
@Composable
fun Cover(model: AppModel, track: TrackEntity?, size: Dp, corner: Dp = 14.dp, round: Boolean = false) {
    // Two sizes keep the cache small: covers in lists and grids, and the large page covers.
    val px = with(LocalDensity.current) { size.roundToPx() }
    ArtThumb(model.artwork, track?.location, size, corner, maxSizePx = if (px <= 160) 160 else if (px <= 360) 360 else 540, round = round)
}

/** "2024 · 4 songs · 16 min". */
fun Album.meta(): String =
    listOfNotNull(year?.toString(), plural(tracks.size, "song"), durationMs.takeIf { it > 0 }?.let { "${max(1L, (it + 30_000) / 60_000)} min" })
        .joinToString(" · ")

/** "Neon Coast · 2024". */
fun Album.byline(): String = listOfNotNull(artist ?: "Unknown artist", year?.toString()).joinToString(" · ")

/** "2 albums · 7 songs" (only the songs when none of them has an album tag). */
fun Artist.counts(): String =
    listOfNotNull(albums.size.takeIf { it > 0 }?.let { plural(it, "album") }, plural(tracks.size, "song")).joinToString(" · ")

/** Play / Shuffle on album and artist pages: an outlined pill, or the filled accent button. */
@Composable
fun PageButton(icon: ImageVector, label: String, filled: Boolean = false, pill: Boolean = true, textSize: Int = 13, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (pill) 50 else 12)
    Row(
        Modifier.clip(shape).background(if (filled) disc.accent else disc.tint.copy(alpha = 0.03f))
            .then(if (filled) Modifier else Modifier.border(1.dp, disc.line, shape))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (filled) disc.accentInk else disc.text)
        Text(label, color = if (filled) disc.accentInk else disc.text, fontSize = textSize.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "ALBUMS" / "SONGS" above a section of an artist page. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, color = disc.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.em)

// ---------------------------------------------------------------- Android

/** Android Library: search, tabs and the lists, plus album and artist pages. Playing a song opens Now Playing. */
@Composable
fun PhoneLibrary(model: AppModel, onBack: () -> Unit, onPlayed: () -> Unit) {
    val nav = remember { LibraryNav() }
    PlatformBackHandler(enabled = nav.page != null) { nav.back() }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 26.dp)) {
        when (val page = nav.page) {
            null -> {
                PhoneHeader("Library", "Back to disc", onBack)
                PhoneLibraryLists(model, nav, onPlayed)
            }
            is LibraryPage.AlbumPage -> {
                PhoneHeader("Album", "Back", nav::back)
                PhoneAlbumPage(model, nav, page.key, onPlayed)
            }
            is LibraryPage.ArtistPage -> {
                PhoneHeader("Artist", "Back", nav::back)
                PhoneArtistPage(model, nav, page.name, onPlayed)
            }
        }
    }
}

@Composable
private fun PhoneHeader(title: String, backLabel: String, onBack: () -> Unit) {
    Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleIconButton("←", backLabel, onBack)
        Text(title, color = disc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Room left at the bottom of phone lists for the mini player. */
private val MiniPlayerSpace = PaddingValues(bottom = 92.dp)

@Composable
private fun PhoneLibraryLists(model: AppModel, nav: LibraryNav, onPlayed: () -> Unit) {
    val songs by model.librarySongs.collectAsState()
    val albums by model.libraryAlbums.collectAsState()
    val artists by model.libraryArtists.collectAsState()
    LibrarySearch(model, Modifier.fillMaxWidth().padding(bottom = 14.dp))
    Box(Modifier.padding(bottom = 12.dp)) { LibraryTabs(nav.tab) { nav.tab = it } }
    when (nav.tab) {
        LibraryTab.Songs -> if (songs.isEmpty()) NoMatches(model, nav.tab) else LazyColumn(Modifier.fillMaxSize(), contentPadding = MiniPlayerSpace) {
            itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                PhoneSongRow(model, t, t.subtitle()) { model.play(songs, i); onPlayed() }
            }
        }
        LibraryTab.Albums -> if (albums.isEmpty()) NoMatches(model, nav.tab) else BoxWithConstraints(Modifier.fillMaxSize()) {
            val gap = 14.dp
            val cell = (maxWidth - gap) / 2
            LazyColumn(Modifier.fillMaxSize(), contentPadding = MiniPlayerSpace, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(albums.chunked(2), key = { it.first().key }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (a in row) PhoneAlbumCard(model, a, cell) { nav.open(LibraryPage.AlbumPage(a.key)) }
                    }
                }
            }
        }
        LibraryTab.Artists -> if (artists.isEmpty()) NoMatches(model, nav.tab) else LazyColumn(Modifier.fillMaxSize(), contentPadding = MiniPlayerSpace) {
            items(artists, key = { it.name }) { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = "Open ${a.name}") { nav.open(LibraryPage.ArtistPage(a.name)) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Cover(model, a.picture, 48.dp, round = true)
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = disc.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.counts(), Modifier.padding(top = 2.dp), color = disc.muted, fontSize = 11.sp)
                    }
                    Text("›", color = disc.muted, fontSize = 18.sp)
                }
            }
        }
    }
}

/** A song row: art, title and [subtitle], length and ⋮; or the track number instead of art. */
@Composable
private fun PhoneSongRow(model: AppModel, t: TrackEntity, subtitle: String?, number: Int? = null, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Play ${t.displayTitle}", onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (number != null) {
            Text("$number", Modifier.width(32.dp), color = disc.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        } else {
            ArtThumb(model.artwork, t.location, 44.dp, 10.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(t.displayTitle, color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, Modifier.padding(top = 2.dp), color = disc.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(t.durationMs), color = disc.muted, fontSize = 11.sp)
        SongMenuButton(model, t)
    }
}

@Composable
private fun PhoneAlbumCard(model: AppModel, a: Album, width: Dp, onOpen: () -> Unit) {
    // Not clipped to a rounded shape: the corner would cut into the first letter of the last line.
    Column(Modifier.width(width).clickable(onClickLabel = "Open ${a.name}", onClick = onOpen)) {
        Cover(model, a.cover, width)
        Text(a.name, Modifier.padding(top = 9.dp), color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.byline(), Modifier.padding(top = 2.dp), color = disc.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PhoneAlbumPage(model: AppModel, nav: LibraryNav, key: String, onPlayed: () -> Unit) {
    val albums by model.albums.collectAsState()
    val album = albums.firstOrNull { it.key == key } ?: return Gone("album")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = MiniPlayerSpace) {
        item(key = "hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(model, album.cover, 120.dp)
                Column(Modifier.weight(1f)) {
                    Text(album.name, color = disc.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    ArtistLink(album.artist, nav, Modifier.padding(top = 4.dp), 13)
                    Text(album.meta(), Modifier.padding(top = 3.dp), color = disc.muted, fontSize = 12.sp)
                }
            }
        }
        item(key = "buttons") {
            Row(Modifier.padding(top = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PageButton(DiscIcons.Play, "Play") { model.playAll(album.tracks, shuffle = false); onPlayed() }
                PageButton(DiscIcons.Shuffle, "Shuffle") { model.playAll(album.tracks, shuffle = true); onPlayed() }
            }
        }
        itemsIndexed(album.tracks, key = { _, t -> t.id }) { i, t ->
            PhoneSongRow(model, t, subtitle = null, number = t.trackNumber ?: (i + 1)) { model.play(album.tracks, i); onPlayed() }
        }
    }
}

/** The album's artist, which opens their page (plain text when the artist isn't tagged). */
@Composable
private fun ArtistLink(artist: String?, nav: LibraryNav, modifier: Modifier, textSize: Int) {
    if (artist == null) {
        Text("Unknown artist", modifier, color = disc.text, fontSize = textSize.sp)
    } else {
        Text(
            artist, modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Open $artist") { nav.open(LibraryPage.ArtistPage(artist)) },
            color = disc.text, fontSize = textSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PhoneArtistPage(model: AppModel, nav: LibraryNav, name: String, onPlayed: () -> Unit) {
    val artists by model.artists.collectAsState()
    val artist = artists.firstOrNull { it.name == name } ?: return Gone("artist")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = MiniPlayerSpace) {
        item(key = "hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(model, artist.picture, 96.dp, round = true)
                Column(Modifier.weight(1f)) {
                    Text(artist.name, color = disc.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(artist.counts(), Modifier.padding(top = 3.dp), color = disc.muted, fontSize = 12.sp)
                }
            }
        }
        item(key = "buttons") {
            Row(Modifier.padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PageButton(DiscIcons.Play, "Play all") { model.playAll(artist.tracks, shuffle = false); onPlayed() }
                PageButton(DiscIcons.Shuffle, "Shuffle") { model.playAll(artist.tracks, shuffle = true); onPlayed() }
            }
        }
        if (artist.albums.isNotEmpty()) {
            item(key = "albums") {
                Column {
                    SectionLabel("Albums", Modifier.padding(top = 16.dp, bottom = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artist.albums, key = { it.key }) { a ->
                            PhoneAlbumCard(model, a, 134.dp) { nav.open(LibraryPage.AlbumPage(a.key)) }
                        }
                    }
                }
            }
        }
        item(key = "songsLabel") { SectionLabel("Songs", Modifier.padding(top = 20.dp, bottom = 6.dp)) }
        itemsIndexed(artist.tracks, key = { _, t -> t.id }) { i, t ->
            PhoneSongRow(model, t, t.album ?: "No album") { model.play(artist.tracks, i); onPlayed() }
        }
    }
}

/** "Artist · Album", leaving out what's unknown. */
fun TrackEntity.subtitle(): String = listOfNotNull(artist ?: "Unknown artist", album).joinToString(" · ")

// ---------------------------------------------------------------- Desktop

/** Columns of album/artist cards that fit [width], each at least 160 dp wide. */
class CardGrid(width: Dp, val gap: Dp = 14.dp) {
    val columns = max(1, ((width + gap) / (160.dp + gap)).toInt())
    val cell = (width - gap * (columns - 1)) / columns
}

/**
 * Desktop Library table rows (art or track number, title/artist, album, time, ⋮), added to the
 * view's lazy list so only visible rows are drawn. The playing song is highlighted. [showAlbum]
 * is false in the narrow layout, as in the mockup; [numbered] shows track numbers (album page).
 */
fun LazyListScope.desktopSongTable(model: AppModel, songs: List<TrackEntity>, currentId: Long?, showAlbum: Boolean, numbered: Boolean = false) {
    item(key = "songhead") {
        val header = TextStyle(color = disc.muted, fontSize = 9.sp, letterSpacing = 0.16.em)
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (numbered) "#" else "", Modifier.width(42.dp), style = header, textAlign = TextAlign.Center)
            Text("TITLE", Modifier.weight(1.5f), style = header)
            if (showAlbum) Text("ALBUM", Modifier.weight(0.9f), style = header)
            Text("TIME", Modifier.width(46.dp), style = header)
            Box(Modifier.width(26.dp))
        }
    }
    if (songs.isEmpty()) item(key = "empty") { NoMatches(model, LibraryTab.Songs) }
    itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
        val current = t.id == currentId
        Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(12.dp))
                .background(if (current) disc.soft else Color.Transparent)
                .then(if (current) Modifier.border(1.dp, disc.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)) else Modifier)
                .clickable(onClickLabel = "Play ${t.displayTitle}") { model.play(songs, i) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (numbered) {
                Text("${t.trackNumber ?: (i + 1)}", Modifier.width(42.dp), color = disc.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            } else {
                ArtThumb(model.artwork, t.location, 42.dp, 9.dp)
            }
            Column(Modifier.weight(1.5f)) {
                Text(t.displayTitle, color = if (current) disc.accentReadable else disc.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist ?: "Unknown artist", color = disc.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showAlbum) Text(t.album ?: "—", Modifier.weight(0.9f), color = disc.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTime(t.durationMs), Modifier.width(46.dp), color = disc.muted, fontSize = 10.sp)
            SongMenuButton(model, t, 26.dp)
        }
    }
}

/** Rows of cards across the [grid], drawn lazily row by row. */
private fun <T> LazyListScope.cardRows(prefix: String, entries: List<T>, grid: CardGrid, key: (T) -> String, card: @Composable (T) -> Unit) {
    items(entries.chunked(grid.columns), key = { "$prefix:${key(it.first())}" }) { row ->
        Row(Modifier.padding(bottom = grid.gap), horizontalArrangement = Arrangement.spacedBy(grid.gap)) {
            for (e in row) card(e)
        }
    }
}

@Composable
private fun DesktopCard(width: Dp, label: String, onOpen: () -> Unit, centered: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(width).clip(shape).background(disc.tint.copy(alpha = 0.02f)).border(1.dp, disc.line, shape)
            .clickable(onClickLabel = label, onClick = onOpen).padding(10.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) { content() }
}

fun LazyListScope.desktopAlbumGrid(model: AppModel, albums: List<Album>, grid: CardGrid, prefix: String, onOpen: (Album) -> Unit) =
    cardRows(prefix, albums, grid, { it.key }) { a ->
        DesktopCard(grid.cell, "Open ${a.name}", { onOpen(a) }, centered = false) {
            Cover(model, a.cover, grid.cell - 20.dp, corner = 12.dp)
            Text(a.name, Modifier.padding(top = 10.dp), color = disc.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.byline(), Modifier.padding(top = 2.dp, bottom = 2.dp), color = disc.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

fun LazyListScope.desktopArtistGrid(model: AppModel, artists: List<Artist>, grid: CardGrid, onOpen: (Artist) -> Unit) =
    cardRows("artist", artists, grid, { it.name }) { a ->
        DesktopCard(grid.cell, "Open ${a.name}", { onOpen(a) }, centered = true) {
            Cover(model, a.picture, grid.cell - 20.dp, round = true)
            Text(a.name, Modifier.padding(top = 10.dp), color = disc.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.counts(), Modifier.padding(top = 2.dp, bottom = 2.dp), color = disc.muted, fontSize = 10.sp)
        }
    }

/** Back button, eyebrow and title at the top of a desktop album or artist page. */
@Composable
private fun DesktopPageHeader(eyebrow: String, title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CircleIconButton("←", "Back", onBack)
        Column {
            Eyebrow(eyebrow)
            Text(title, Modifier.padding(top = 5.dp), color = disc.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.035).em, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Big cover (round for artists) with the lines and buttons beside it, aligned to its foot. */
@Composable
private fun DesktopHero(model: AppModel, cover: TrackEntity?, round: Boolean, text: @Composable () -> Unit, buttons: @Composable () -> Unit) {
    Row(Modifier.padding(bottom = 22.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Cover(model, cover, 172.dp, corner = 18.dp, round = round)
        Column {
            text()
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { buttons() }
        }
    }
}

fun LazyListScope.desktopAlbumPage(model: AppModel, album: Album?, nav: LibraryNav, currentId: Long?) {
    if (album == null) {
        item(key = "gone") { DesktopPageHeader("Album", "", nav::back); Gone("album") }
        return
    }
    item(key = "pagehead") { DesktopPageHeader("Album", album.name, nav::back) }
    item(key = "hero") {
        DesktopHero(
            model, album.cover, round = false,
            text = {
                ArtistLink(album.artist, nav, Modifier, 15)
                Text(album.meta(), Modifier.padding(top = 5.dp), color = disc.muted, fontSize = 12.sp)
            },
            buttons = {
                PageButton(DiscIcons.Play, "Play", filled = true, pill = false, textSize = 12) { model.playAll(album.tracks, shuffle = false) }
                PageButton(DiscIcons.Shuffle, "Shuffle", pill = false, textSize = 12) { model.playAll(album.tracks, shuffle = true) }
            },
        )
    }
    desktopSongTable(model, album.tracks, currentId, showAlbum = false, numbered = true)
}

fun LazyListScope.desktopArtistPage(model: AppModel, artist: Artist?, nav: LibraryNav, currentId: Long?, grid: CardGrid, showAlbum: Boolean) {
    if (artist == null) {
        item(key = "gone") { DesktopPageHeader("Artist", "", nav::back); Gone("artist") }
        return
    }
    item(key = "pagehead") { DesktopPageHeader("Artist", artist.name, nav::back) }
    item(key = "hero") {
        DesktopHero(
            model, artist.picture, round = true,
            text = { Text(artist.counts(), color = disc.muted, fontSize = 12.sp) },
            buttons = {
                PageButton(DiscIcons.Play, "Play all", filled = true, pill = false, textSize = 12) { model.playAll(artist.tracks, shuffle = false) }
                PageButton(DiscIcons.Shuffle, "Shuffle", pill = false, textSize = 12) { model.playAll(artist.tracks, shuffle = true) }
            },
        )
    }
    if (artist.albums.isNotEmpty()) {
        item(key = "albumsLabel") { SectionLabel("Albums", Modifier.padding(bottom = 10.dp)) }
        desktopAlbumGrid(model, artist.albums, grid, "artistAlbum") { nav.open(LibraryPage.AlbumPage(it.key)) }
    }
    item(key = "songsLabel") { SectionLabel("Songs", Modifier.padding(top = 10.dp, bottom = 10.dp)) }
    desktopSongTable(model, artist.tracks, currentId, showAlbum)
}
