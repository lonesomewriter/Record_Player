package dev.recorddisc.player.ui

import androidx.compose.ui.graphics.Color
import dev.recorddisc.player.app.AccentPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeTest {
    @Test
    fun lightAccentTextReadsOnBothPanels() {
        val accents = AccentPreset.entries.map { Color(it.argb) } +
            listOf(Color(0xFFFFFF00), Color(0xFF00FFFF), Color(0xFFFFFFFF), Color(0xFF7FD1B9), Color(0xFF202020))
        for (accent in accents) {
            val c = DiscColors.forAccent(accent, light = true)
            assertTrue(contrast(c.accentReadable, c.panel) >= 4.5f, "accent $accent on panel")
            assertTrue(contrast(c.accentReadable, c.panel2) >= 4.5f, "accent $accent on panel2")
        }
    }

    @Test
    fun lightTextColoursReadOnEveryBackground() {
        val l = DiscColors.LIGHT
        for (bg in listOf(l.bg, l.shell, l.panel, l.panel2, l.sideTop, l.sideBottom)) {
            assertTrue(contrast(l.text, bg) >= 7f, "text on $bg")
            assertTrue(contrast(l.muted, bg) >= 4.5f, "muted on $bg")
        }
    }

    @Test
    fun darkThemeIsUnchanged() {
        assertEquals(DiscColors(), DiscColors.forAccent(Color(0xFFE0A458)))
    }

    @Test
    fun colorSchemeOutputIsParsed() {
        assertEquals(true, parseColorScheme("(<<uint32 1>>,)\n"))
        assertEquals(false, parseColorScheme("(<uint32 2>,)\n"))
        assertNull(parseColorScheme("(<<uint32 0>>,)\n"))
        assertNull(parseColorScheme("Error: GDBus.Error:org.freedesktop.DBus.Error.ServiceUnknown"))
        assertNull(parseColorScheme(""))
    }
}
