package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun `parses two and three digit fractions`() {
        val lines = LrcParser.parse("[00:12.34]two\n[01:02.345]three\n[00:05]none\n[00:07.5]one")
        assertEquals(
            listOf(
                LyricsLine(5_000, "none"),
                LyricsLine(7_500, "one"),
                LyricsLine(12_340, "two"),
                LyricsLine(62_345, "three"),
            ),
            lines,
        )
    }

    @Test
    fun `line with several timestamps produces one line per timestamp, sorted`() {
        val lines = LrcParser.parse("[00:30.00][00:10.00]chorus\n[00:20.00]verse")
        assertEquals(
            listOf(LyricsLine(10_000, "chorus"), LyricsLine(20_000, "verse"), LyricsLine(30_000, "chorus")),
            lines,
        )
    }

    @Test
    fun `skips metadata and blank lines, keeps tag-only lines as empty text`() {
        val lines = LrcParser.parse("[ar:Artist]\n[ti:Title]\n\n   \nuntimed\n[00:01.00]\n[00:02.00] hello ")
        assertEquals(listOf(LyricsLine(1_000, ""), LyricsLine(2_000, "hello")), lines)
    }

    @Test
    fun `applies offset tag - positive offset shows lyrics sooner`() {
        val lines = LrcParser.parse("[offset:+500]\n[00:10.00]a\n[00:00.20]b")
        assertEquals(listOf(LyricsLine(0, "b"), LyricsLine(9_500, "a")), lines)
    }

    @Test
    fun `strips word-level timestamps`() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>world")
        assertEquals(listOf(LyricsLine(1_000, "Hello world")), lines)
    }

    @Test
    fun `lrclib response prefers synced and falls back to plain`() {
        val synced = LrcLibProvider.parseResponse("""{"syncedLyrics":"[00:01.00]a","plainLyrics":"a"}""")!!
        assertEquals(true, synced.synced)
        assertEquals(listOf(LyricsLine(1_000, "a")), synced.lines)

        val plain = LrcLibProvider.parseResponse("""{"syncedLyrics":null,"plainLyrics":"a\n\nb\n"}""")!!
        assertEquals(false, plain.synced)
        assertEquals(listOf("a", "b"), plain.lines.map { it.text })

        assertEquals(null, LrcLibProvider.parseResponse("""{"instrumental":true,"syncedLyrics":null,"plainLyrics":null}"""))
    }
}
