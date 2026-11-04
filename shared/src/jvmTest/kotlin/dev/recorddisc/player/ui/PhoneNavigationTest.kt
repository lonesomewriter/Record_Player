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
        assertEquals(
            PhoneScreen.Home,
            phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = false, nowPlayingReturn = PhoneScreen.Inbox),
        )
        assertNull(phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = true))
        for (screen in listOf(PhoneScreen.EditTags, PhoneScreen.EditLyrics)) {
            assertNull(phoneSwipeDestination(screen, swipeRight = false))
            assertNull(phoneSwipeDestination(screen, swipeRight = true))
        }
    }
}
