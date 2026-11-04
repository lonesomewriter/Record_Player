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
        assertNull(phoneSwipeDestination(PhoneScreen.Settings, swipeRight = true))
    }

    @Test
    fun leftSwipesReturnThroughMainPagesWithoutWrapping() {
        assertNull(phoneSwipeDestination(PhoneScreen.Home, swipeRight = false))
        assertEquals(PhoneScreen.Home, phoneSwipeDestination(PhoneScreen.Library, swipeRight = false))
        assertEquals(PhoneScreen.Library, phoneSwipeDestination(PhoneScreen.Playlists, swipeRight = false))
        assertEquals(PhoneScreen.Playlists, phoneSwipeDestination(PhoneScreen.Settings, swipeRight = false))
    }

    @Test
    fun nowPlayingClosesOnLeftSwipeAndEditorsIgnoreSwipes() {
        assertEquals(PhoneScreen.Home, phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = false))
        assertNull(phoneSwipeDestination(PhoneScreen.NowPlaying, swipeRight = true))
        for (screen in listOf(PhoneScreen.EditTags, PhoneScreen.EditLyrics)) {
            assertNull(phoneSwipeDestination(screen, swipeRight = false))
            assertNull(phoneSwipeDestination(screen, swipeRight = true))
        }
    }
}
