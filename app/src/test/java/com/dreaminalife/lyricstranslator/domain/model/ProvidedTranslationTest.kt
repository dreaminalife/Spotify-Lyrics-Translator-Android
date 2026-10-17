package com.dreaminalife.lyricstranslator.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProvidedTranslationTest {
    private val lyrics = Lyrics(
        lines = listOf(LyricsLine(0, "a"), LyricsLine(1, "b")),
        synced = true,
        source = LyricsSource.NETEASE,
        translation = ProvidedTranslation("zh-CN", listOf("甲", "乙")),
    )

    @Test
    fun `returned only for its own language`() {
        assertEquals(listOf("甲", "乙"), lyrics.providedTranslation("zh-CN"))
        assertNull(lyrics.providedTranslation("zh-TW"))
        assertNull(lyrics.providedTranslation("en"))
    }

    @Test
    fun `not returned when it does not line up with the lyrics`() {
        assertNull(lyrics.copy(lines = lyrics.lines.take(1)).providedTranslation("zh-CN"))
    }

    @Test
    fun `absent when the source shipped none`() {
        assertNull(lyrics.copy(translation = null).providedTranslation("zh-CN"))
    }
}
