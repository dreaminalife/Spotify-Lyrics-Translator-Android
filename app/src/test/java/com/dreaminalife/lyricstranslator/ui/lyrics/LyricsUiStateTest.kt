package com.dreaminalife.lyricstranslator.ui.lyrics

import com.dreaminalife.lyricstranslator.data.translation.TranslatedLyrics
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsUiStateTest {
    private val track = Track("current", "Title", "Artist", "", 1000)
    private val content = LyricsContent.Loaded(
        Lyrics(listOf(LyricsLine(0, "a"), LyricsLine(1, "b")), synced = true, source = LyricsSource.NETEASE),
    )
    private val provided = listOf("甲", "")
    private val done = TranslatedLyrics(listOf("A", "B"), "T", "Fake")

    @Test
    fun `provided lines show while translating, after a failure, without a key, and with auto-translate off`() {
        listOf(
            TranslationState.Translating,
            TranslationState.Failed("x"),
            TranslationState.MissingKey,
            TranslationState.NotRequested,
            TranslationState.None,
        )
            .forEach { assertEquals(provided, LyricsUiState(track, content, translation = it, providedTranslation = provided).translationLines) }
    }

    @Test
    fun `the finished translation replaces the provided lines`() {
        val translation = TranslationState.Done("current", LyricsSource.NETEASE, done)
        val state = LyricsUiState(track, content, translation, providedTranslation = provided)
        assertEquals(listOf("A", "B"), state.translationLines)
    }

    @Test
    fun `a finished translation for another track is ignored`() {
        val translation = TranslationState.Done("previous", LyricsSource.NETEASE, done)
        val state = LyricsUiState(track, content, translation, providedTranslation = provided)
        assertEquals(provided, state.translationLines)
        assertNull(LyricsUiState(track, content, translation).translationLines)
    }

    @Test
    fun `a finished translation of another source's lyrics is ignored`() {
        val translation = TranslationState.Done("current", LyricsSource.QQ_MUSIC, done)
        val state = LyricsUiState(track, content, translation, providedTranslation = provided)
        assertNull(state.currentTranslation)
        assertEquals(provided, state.translationLines)
    }
}
