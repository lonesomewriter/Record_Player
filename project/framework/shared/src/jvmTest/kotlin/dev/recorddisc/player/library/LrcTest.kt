package dev.recorddisc.player.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LrcTest {
    @Test
    fun readsEveryTimestampForm() {
        val l = Lrc.parse("[00:06]a\n[00:12.8]b\n[00:19.40]c\n[01:02.345]d\n[100:00.00]e").lines
        assertEquals(listOf(6_000L, 12_800L, 19_400L, 62_345L, 6_000_000L), l.map { it.timeMs })
        assertEquals(listOf("a", "b", "c", "d", "e"), l.map { it.text })
    }

    @Test
    fun handlesBomCrlfAndOldEncodings() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[00:01.00]Café\r\n[00:02.00]Naïve\r\n".encodeToByteArray()
        assertEquals(listOf("Café", "Naïve"), Lrc.parse(Lrc.decode(utf8)).lines.map { it.text })
        // Windows-1252: é is 0xE9 and ’ is 0x92, neither valid UTF-8 here.
        val cp1252 = "[00:01.00]Caf".encodeToByteArray() + byteArrayOf(0xE9.toByte()) + " it".encodeToByteArray() + byteArrayOf(0x92.toByte()) + "s".encodeToByteArray()
        assertEquals("Café it’s", Lrc.parse(Lrc.decode(cp1252)).lines.single().text)
    }

    @Test
    fun appliesOffsetAndSplitsRepeatedTimestamps() {
        val l = Lrc.parse("[offset:500]\n[00:10.00][00:30.00]Chorus\n[00:20.00]Verse").lines
        assertEquals(listOf(9_500L to "Chorus", 19_500L to "Verse", 29_500L to "Chorus"), l.map { it.timeMs!! to it.text })
    }

    @Test
    fun stripsWordTimingAndKeepsTags() {
        val lyrics = Lrc.parse("[ti:Midnight Drive]\n[ar:Neon Coast]\n[00:01.00]<00:01.00>Head <00:01.50>lights")
        assertEquals("Head lights", lyrics.lines.single().text)
        assertEquals(listOf("[ti:Midnight Drive]", "[ar:Neon Coast]"), lyrics.tags)
    }

    @Test
    fun untimedLinesKeepTheirPlace() {
        val lyrics = Lrc.parse("Intro line\n[00:10.00]First\nNo time here\n[00:05.00]Earlier")
        // "Intro line" borrows 0, "No time here" borrows 10 s, so the 5 s line moves before them.
        assertEquals(listOf("Intro line", "Earlier", "First", "No time here"), lyrics.lines.map { it.text })
        assertTrue(lyrics.synced)
        assertFalse(Lrc.parse("Only\nplain text").synced)
    }

    @Test
    fun formatRoundTrips() {
        val lyrics = Lyrics(listOf(LyricLine(6_200, "Headlights"), LyricLine(null, "(untimed)"), LyricLine(62_340, "Static")), listOf("[ar:Neon Coast]"))
        val text = Lrc.format(lyrics)
        assertEquals("[ar:Neon Coast]\n[00:06.20]Headlights\n(untimed)\n[01:02.34]Static\n", text)
        assertEquals(lyrics, Lrc.parse(text))
    }

    @Test
    fun activeLineFollowsPosition() {
        val lines = listOf(LyricLine(null, "intro"), LyricLine(1_000, "a"), LyricLine(2_000, "b"))
        assertEquals(-1, Lrc.activeLine(lines, 999))
        assertEquals(1, Lrc.activeLine(lines, 1_000))
        assertEquals(2, Lrc.activeLine(lines, 60_000))
    }

    @Test
    fun formatsTimes() {
        assertEquals("--:--.--", Lrc.formatTime(null))
        assertEquals("00:42.00", Lrc.formatTime(42_000))
        assertEquals("03:05.07", Lrc.formatTime(185_079))
        assertEquals("120:00.00", Lrc.formatTime(7_200_000))
    }
}
