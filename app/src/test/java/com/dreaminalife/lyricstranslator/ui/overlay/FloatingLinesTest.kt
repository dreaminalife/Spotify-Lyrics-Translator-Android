package com.dreaminalife.lyricstranslator.ui.overlay

import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsMode
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsMode.BOTH
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsMode.TRANSLATION_ONLY
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLinesTest {
    private val track = Track("key", "Title", "Artist", "", 1000)
    private val lyrics = Lyrics(
        listOf(LyricsLine(0, "a"), LyricsLine(1, "b"), LyricsLine(2, "")),
        synced = true,
        source = LyricsSource.LRCLIB,
    )
    private val translations = listOf("A", " ", "")

    private fun row(
        index: Int,
        mode: FloatingLyricsMode,
        translations: List<String>? = this.translations,
        track: Track = this.track,
        lyrics: Lyrics = this.lyrics,
    ) = floatingRows(track, lyrics, translations, index, mode, count = 1).single()

    @Test
    fun `both shows the original with its translation under it`() {
        assertEquals(FloatingRow("a", "A"), row(0, BOTH))
    }

    @Test
    fun `translation only shows just the translation`() {
        assertEquals(FloatingRow("A", null), row(0, TRANSLATION_ONLY))
    }

    @Test
    fun `a blank or missing translation leaves the original`() {
        assertEquals(FloatingRow("b", null), row(1, BOTH))
        assertEquals(FloatingRow("b", null), row(1, TRANSLATION_ONLY))
        assertEquals(FloatingRow("a", null), row(0, TRANSLATION_ONLY, translations = null))
    }

    @Test
    fun `a blank line shows a note`() {
        assertEquals(FloatingRow("♪", null), row(2, TRANSLATION_ONLY))
    }

    @Test
    fun `before the first line the track shows`() {
        assertEquals(FloatingRow("Title", "Artist"), row(-1, BOTH))
        assertEquals(FloatingRow("Title", null), row(-1, BOTH, track = track.copy(artist = "")))
    }

    @Test
    fun `lyrics that aren't synced show the track and why, in one row`() {
        val unsynced = lyrics.copy(synced = false)
        assertEquals(
            listOf(FloatingRow("Title", "Not time-synced")),
            floatingRows(track, unsynced, translations, -1, TRANSLATION_ONLY, count = 3),
        )
    }

    @Test
    fun `several rows put the current line in the middle`() {
        assertEquals(
            listOf(FloatingRow("a", "A"), FloatingRow("b", null), FloatingRow("♪", null)),
            floatingRows(track, lyrics, translations, 1, BOTH, count = 3),
        )
    }

    @Test
    fun `rows past either end are empty, and the track comes before the first line`() {
        assertEquals(
            listOf(null, FloatingRow("Title", "Artist"), FloatingRow("a", "A"), FloatingRow("b", null), FloatingRow("♪", null)),
            floatingRows(track, lyrics, translations, 0, BOTH, count = 5),
        )
        assertEquals(
            listOf(FloatingRow("b", null), FloatingRow("♪", null), null),
            floatingRows(track, lyrics, translations, 2, TRANSLATION_ONLY, count = 3),
        )
    }
}
