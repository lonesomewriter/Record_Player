package dev.recorddisc.player.ui

import dev.recorddisc.player.playback.SystemMuteMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsing of the desktop tools' output, from recorded samples (no desktop session needed). */
class SystemDesktopReadersTest {
    @Test
    fun pactlMuteAndVolume() {
        assertTrue(SystemMuteMonitor.isMuted("Mute: yes\n"))
        assertFalse(SystemMuteMonitor.isMuted("Mute: no\n"))
        assertFalse(SystemMuteMonitor.isMuted("Failure: No such entity\n"))
        val zero = "Volume: front-left: 0 /   0% / -inf dB,   front-right: 0 /   0% / -inf dB\n        balance 0.00\n"
        val some = "Volume: front-left: 26214 /  40% / -23.88 dB,   front-right: 26214 /  40% / -23.88 dB\n        balance 0.00\n"
        val oneSide = "Volume: front-left: 0 /   0% / -inf dB,   front-right: 26214 /  40% / -23.88 dB\n"
        assertTrue(SystemMuteMonitor.isVolumeZero(zero))
        assertFalse(SystemMuteMonitor.isVolumeZero(some))
        assertFalse(SystemMuteMonitor.isVolumeZero(oneSide), "still audible on one side")
        assertFalse(SystemMuteMonitor.isVolumeZero(""))
    }

    @Test
    fun portalAccentColour() {
        assertEquals(0xFF3584E4, SystemAccentMonitor.parsePortalColor("v (ddd) 0.207843 0.517647 0.894118\n"))
        assertEquals(0xFF2190A4, SystemAccentMonitor.parsePortalColor("v v (ddd) 0.129412 0.564706 0.643137\n"), "older portals wrap it twice")
        assertNull(SystemAccentMonitor.parsePortalColor("v (ddd) -1 -1 -1\n"), "out of range means no accent set")
        assertNull(SystemAccentMonitor.parsePortalColor("Call failed: No such interface\n"))
    }

    @Test
    fun gnomeAccentName() {
        assertEquals("teal", SystemAccentMonitor.parseGnomeName("'teal'\n"))
        assertEquals("purple", SystemAccentMonitor.parseGnomeName("accent-color: 'purple'\n"), "gsettings monitor's form")
        assertNull(SystemAccentMonitor.parseGnomeName("No such key “accent-color”\n"))
        assertEquals("Slate", SystemAccentMonitor.GNOME_ACCENTS.getValue("slate").first)
    }
}
