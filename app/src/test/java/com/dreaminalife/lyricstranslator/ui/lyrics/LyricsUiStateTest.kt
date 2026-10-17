package com.dreaminalife.lyricstranslator.ui.lyrics

import com.dreaminalife.lyricstranslator.data.translation.TranslatedLyrics
import com.dreaminalife.lyricstranslator.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsUiStateTest {
    private val track = Track("current", "Title", "Artist", "", 1000)
    private val provided = listOf("甲", "")
    private val done = TranslatedLyrics(listOf("A", "B"), "T", "Fake")

    @Test
    fun `provided lines show while translating, after a failure, and without a key`() {
        listOf(TranslationState.Translating, TranslationState.Failed("x"), TranslationState.MissingKey, TranslationState.None)
            .forEach { assertEquals(provided, LyricsUiState(track, translation = it, providedTranslation = provided).translationLines) }
    }

    @Test
    fun `the finished translation replaces the provided lines`() {
        val state = LyricsUiState(track, translation = TranslationState.Done("current", done), providedTranslation = provided)
        assertEquals(listOf("A", "B"), state.translationLines)
    }

    @Test
    fun `a finished translation for another track is ignored`() {
        val state = LyricsUiState(track, translation = TranslationState.Done("previous", done), providedTranslation = provided)
        assertEquals(provided, state.translationLines)
        assertNull(LyricsUiState(track, translation = TranslationState.Done("previous", done)).translationLines)
    }
}
