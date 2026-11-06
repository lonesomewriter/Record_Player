package dev.recorddisc.player.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverArtAccentTest {
    @Test
    fun choosesTheMostCommonChromaticColor() {
        val orange = Color(0xFFE0A458)
        val blue = Color(0xFF568FD4)
        val result = representativeCoverAccent(
            List(80) { orange } + List(30) { blue } + listOf(Color.Black, Color.White, Color.Gray),
        )
        assertEquals(orange, result)
    }

    @Test
    fun returnsNullWhenArtHasNoUsableChromaticSamples() {
        assertNull(representativeCoverAccent(listOf(Color.Black, Color.White, Color.Gray, Color.Transparent)))
    }
}
