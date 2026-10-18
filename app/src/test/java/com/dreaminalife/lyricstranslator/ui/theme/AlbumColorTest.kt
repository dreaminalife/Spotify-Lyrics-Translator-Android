package com.dreaminalife.lyricstranslator.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumColorTest {
    /** WCAG contrast ratio of white text on [background]. */
    private fun whiteContrast(background: Color) = 1.05f / (background.luminance() + 0.05f)

    @Test
    fun brightYellowIsDarkenedEnoughForWhiteText() {
        val darkened = darkenForWhiteText(Color(0xFFFFEB3B))
        assertTrue(darkened.luminance() <= 0.12f)
        assertTrue(whiteContrast(darkened) >= 6f)
    }

    @Test
    fun nearWhiteIsDarkenedEnoughForWhiteText() {
        val darkened = darkenForWhiteText(Color(0xFFF5F5F5))
        assertTrue(darkened.luminance() <= 0.12f)
        assertTrue(whiteContrast(darkened) >= 6f)
    }

    @Test
    fun darkColorIsUnchanged() {
        val dark = Color(0xFF102030)
        assertEquals(dark, darkenForWhiteText(dark))
    }

    @Test
    fun darkeningKeepsTheHue() {
        // A red cover stays red, just darker.
        val darkened = darkenForWhiteText(Color(0xFFFF5252))
        assertTrue(darkened.red > darkened.green && darkened.red > darkened.blue)
    }
}
