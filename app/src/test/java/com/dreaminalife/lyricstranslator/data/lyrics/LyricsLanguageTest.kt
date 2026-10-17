package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLanguageTest {
    /** Texts passed to the identifier, which answers [identified]. */
    private val identifiedTexts = mutableListOf<String>()
    private var identified: String? = null
    private val language = LyricsLanguage { identifiedTexts += it; identified }

    private fun lyrics(vararg lines: String, language: String? = null) =
        Lyrics(lines.map { LyricsLine(0, it) }, synced = false, source = LyricsSource.NETEASE, language = language)

    @Test
    fun `Chinese lyrics are already in either Chinese target`() = runTest {
        val song = lyrics("作词 : 周杰伦", "", "故事的小黄花", "從出生那年就飄著", "...")
        assertTrue(language.isIn(song, "zh-CN"))
        assertTrue(language.isIn(song, "zh-TW"))
        assertFalse(language.isIn(song, "en"))
        assertTrue(identifiedTexts.isEmpty())
    }

    @Test
    fun `Japanese lyrics with kanji-only lines are Japanese, not Chinese`() = runTest {
        val song = lyrics("君の名前を", "永遠", "呼んでいた")
        assertEquals("ja", language.of(song))
        assertFalse(language.isIn(song, "zh-CN"))
    }

    @Test
    fun `Korean lyrics are Korean`() = runTest {
        assertTrue(language.isIn(lyrics("사랑해", "보고 싶어"), "ko"))
    }

    @Test
    fun `a song mixing Chinese and English is translated, without asking the identifier`() = runTest {
        identified = "zh"
        val song = lyrics("我爱你", "你爱我", "baby I love you", "every night")
        assertNull(language.of(song))
        assertTrue(identifiedTexts.isEmpty())
    }

    @Test
    fun `a mixed song is translated even when the source tags it`() = runTest {
        val song = lyrics("사랑해", "보고 싶어", "baby I love you", "every night", language = "ko")
        assertFalse(language.isIn(song, "ko"))
    }

    @Test
    fun `Chinese credit lines don't hide an English song`() = runTest {
        identified = "en"
        val song = lyrics("作词 : Paul Simon", "作曲：Paul Simon", "词：Paul Simon", "Hello darkness", "my old friend")
        assertTrue(language.isIn(song, "en"))
        assertEquals(listOf("Hello darkness\nmy old friend"), identifiedTexts)
    }

    @Test
    fun `a Chinese song with credit lines is still Chinese`() = runTest {
        assertTrue(language.isIn(lyrics("作词 : 周杰伦", "作曲 : 周杰伦", "故事的小黄花"), "zh-CN"))
    }

    @Test
    fun `Latin lyrics use the source's tag first`() = runTest {
        identified = "es"
        assertTrue(language.isIn(lyrics("Hello darkness", language = "en"), "en"))
        assertTrue(identifiedTexts.isEmpty())
    }

    @Test
    fun `untagged Latin lyrics go to the identifier`() = runTest {
        identified = "en"
        assertTrue(language.isIn(lyrics("Hello darkness", "", "my old friend"), "en"))
        assertEquals(listOf("Hello darkness\nmy old friend"), identifiedTexts)
        identified = null
        assertFalse(language.isIn(lyrics("Hello darkness"), "en"))
    }

    @Test
    fun `the script beats a wrong tag`() = runTest {
        assertTrue(language.isIn(lyrics("故事的小黄花", language = "en"), "zh-CN"))
    }
}
