package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProvidedTranslationsTest {
    @Test
    fun `placeholders and notices are blanked, even with surrounding spaces`() {
        val translation = ProvidedTranslations.of(
            listOf(
                "//",
                "  //  ",
                "QQ音乐享有本翻译作品的著作权",
                "TME享有本翻译作品的著作权",
                " 以下歌词翻译由文曲大模型提供",
                "如果只是一场梦",
            ),
        )!!
        assertEquals("zh-CN", translation.language)
        assertEquals(listOf("", "", "", "", "", "如果只是一场梦"), translation.lines)
    }

    @Test
    fun `a slash inside a real translation is kept`() {
        assertEquals(listOf("你/我"), ProvidedTranslations.of(listOf("你/我"))!!.lines)
    }

    @Test
    fun `nothing left after cleaning gives null`() {
        assertNull(ProvidedTranslations.of(emptyList()))
        assertNull(ProvidedTranslations.of(listOf("", " ", "//", "TME享有本翻译作品的著作权")))
    }

    @Test
    fun `pairing matches timestamps across precisions`() {
        val lines = listOf(LyricsLine(19_430, "a"), LyricsLine(20_000, "b"))
        val translation = ProvidedTranslations.pairByTimestamp(lines, "[00:19.43]一\n[00:20.000]二")!!
        assertEquals(listOf("一", "二"), translation.lines)
    }

    @Test
    fun `repeated timestamps pair in order`() {
        val lines = listOf(LyricsLine(0, "a"), LyricsLine(0, "b"), LyricsLine(1_000, "c"))
        val translation = ProvidedTranslations.pairByTimestamp(lines, "[00:00.00]一\n[00:00.00]二\n[00:01.00]三")!!
        assertEquals(listOf("一", "二", "三"), translation.lines)
    }

    @Test
    fun `untranslated lines stay blank and extra translation lines are ignored`() {
        val lines = listOf(LyricsLine(1_000, "a"), LyricsLine(2_000, "b"))
        val translation = ProvidedTranslations.pairByTimestamp(lines, "[00:01.00]一\n[00:05.00]extra\n[00:00.50]early")!!
        assertEquals(listOf("一", ""), translation.lines)
    }

    @Test
    fun `metadata tags in the translation are skipped`() {
        val lines = listOf(LyricsLine(1_000, "a"))
        val translation = ProvidedTranslations.pairByTimestamp(lines, "[ti:Song]\n[by:someone]\n[00:01.00]一")!!
        assertEquals(listOf("一"), translation.lines)
    }

    @Test
    fun `empty or untimed translation gives null`() {
        val lines = listOf(LyricsLine(1_000, "a"))
        assertNull(ProvidedTranslations.pairByTimestamp(lines, ""))
        assertNull(ProvidedTranslations.pairByTimestamp(lines, "plain text"))
        assertNull(ProvidedTranslations.pairByTimestamp(lines, "[00:01.00]"))
    }
}
