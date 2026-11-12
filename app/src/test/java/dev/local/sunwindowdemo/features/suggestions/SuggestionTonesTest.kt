package dev.local.sunwindowdemo.features.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SuggestionTonesTest {
    @Test fun nightAndDayUseTheSamePaletteAsTheInAppRows() {
        assertEquals(SuggestionTones.TORCH_LIGHT, SuggestionTones.tone(false, SuggestionTones.TORCH_LIGHT, SuggestionTones.TORCH_DARK))
        assertEquals(SuggestionTones.TORCH_DARK, SuggestionTones.tone(true, SuggestionTones.TORCH_LIGHT, SuggestionTones.TORCH_DARK))
        assertEquals(0xFFB26A00.toInt(), SuggestionTones.TORCH_LIGHT)
        assertEquals(0xFFFFB74D.toInt(), SuggestionTones.TORCH_DARK)
        assertEquals(0xFF00897B.toInt(), SuggestionTones.OTP_LIGHT)
        assertEquals(0xFF2E7D32.toInt(), SuggestionTones.MAP_LIGHT)
    }

    @Test fun containerKeepsAFixedTonalTintOverTheSurface() {
        val surface = 0xFFF6F3FA.toInt()
        val container = SuggestionTones.container(surface, SuggestionTones.TORCH_LIGHT)
        assertNotEquals(surface, container)
        // 16% of the tone over an opaque surface: stays light, never the old solid mustard.
        val r = (container shr 16) and 0xFF
        val g = (container shr 8) and 0xFF
        val b = container and 0xFF
        assertEquals(Math.round(0xF6 * (1 - SuggestionTones.CONTAINER_ALPHA) + 0xB2 * SuggestionTones.CONTAINER_ALPHA), r)
        assertEquals(Math.round(0xF3 * (1 - SuggestionTones.CONTAINER_ALPHA) + 0x6A * SuggestionTones.CONTAINER_ALPHA), g)
        assertEquals(Math.round(0xFA * (1 - SuggestionTones.CONTAINER_ALPHA) + 0x00 * SuggestionTones.CONTAINER_ALPHA), b)
        assertEquals(0xFF, (container shr 24) and 0xFF)
    }
}
