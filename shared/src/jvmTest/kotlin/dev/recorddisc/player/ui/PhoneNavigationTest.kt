package dev.recorddisc.player.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneNavigationTest {
    @Test
    fun rightSwipesAdvanceThroughMainPagesWithoutWrapping() {
        assertEquals(PhoneScreen.Library, phoneSwipeDestination(PhoneScreen.Home, swipeRight = true))
        assertEquals(PhoneScreen.Playlists, phoneSwipeDestination(PhoneScreen.Library, swipeRight = true))
        assertEquals(PhoneScreen.Settings, phoneSwipeDestination(PhoneScreen.Playlists, swipeRight = true))
        assertEquals(PhoneScreen.Inbox, phoneSwipeDestination(PhoneScreen.Settings, swipeRight = true))
        assertNull(phoneSwipeDestination(PhoneScreen.Inbox, swipeRight = true))
    }

    @Test
    fun leftSwipesReturnThroughMainPagesWithoutWrapping() {
        assertNull(phoneSwipeDestination(PhoneScreen.Home, swipeRight = false))
        assertEquals(PhoneScreen.Home, phoneSwipeDestination(PhoneScreen.Library, swipeRight = false))
        assertEquals(PhoneScreen.Library, phoneSwipeDestination(PhoneScreen.Playlists, swipeRight = false))
        assertEquals(PhoneScreen.Playlists, phoneSwipeDestination(PhoneScreen.Settings, swipeRight = false))
        assertEquals(PhoneScreen.Settings, phoneSwipeDestination(PhoneScreen.Inbox, swipeRight = false))
    }

    @Test
    fun nowPlayingReturnsToPlaybackOriginOnLeftSwipeAndEditorsIgnoreSwipes() {
        assertEquals(PhoneScreen.Home, phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = false))
        assertEquals(
            PhoneScreen.Library,
            phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = false, nowPlayingReturn = PhoneScreen.Library),
        )
        assertEquals(
            PhoneScreen.Playlists,
            phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = false, nowPlayingReturn = PhoneScreen.Playlists),
        )
        assertNull(phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = true))
        for (screen in listOf(PhoneScreen.EditTags, PhoneScreen.EditLyrics)) {
            assertNull(phoneSwipeDestination(screen, swipeRight = false))
            assertNull(phoneSwipeDestination(screen, swipeRight = true))
        }
    }

    @Test
    fun nowPlayingReturnFollowsWhereTheSongWasLastChosen() {
        assertEquals(PhoneScreen.Home, nowPlayingReturnScreen(null))
        assertEquals(PhoneScreen.Library, nowPlayingReturnScreen(PlaySource.Library))
        assertEquals(PhoneScreen.Library, nowPlayingReturnScreen(PlaySource.Album("after hours|neon coast")))
        assertEquals(PhoneScreen.Library, nowPlayingReturnScreen(PlaySource.Artist("Neon Coast")))
        assertEquals(PhoneScreen.Playlists, nowPlayingReturnScreen(PlaySource.Playlist(7)))
    }

    @Test
    fun songDetailsReturnsWhereItWasOpenedOnLeftSwipe() {
        assertEquals(
            PhoneScreen.Playlists,
            phoneSwipeDestination(PhoneScreen.SongDetails, swipeRight = false, detailsReturn = PhoneScreen.Playlists),
        )
        assertEquals(PhoneScreen.Home, phoneSwipeDestination(PhoneScreen.SongDetails, swipeRight = false))
        assertNull(phoneSwipeDestination(PhoneScreen.SongDetails, swipeRight = true))
    }

    @Test
    fun playSourcesAndLibraryPagesSurviveSaving() {
        val sources = listOf(null, PlaySource.Library, PlaySource.Album("a|b"), PlaySource.Artist("Mira Vale"), PlaySource.Playlist(42))
        for (source in sources) {
            val saved = with(PlaySource.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(source) }
            assertEquals(source, PlaySource.Saver.restore(saved!!))
        }
        val pages = listOf(LibraryPage.AlbumPage("slow light|mira vale"), LibraryPage.ArtistPage("Lumen Field"))
        for (page in pages) assertEquals(page, LibraryNav.decodePage(LibraryNav.encodePage(page)))
        assertNull(LibraryNav.decodePage("unknown\u0000x"))
    }
}
