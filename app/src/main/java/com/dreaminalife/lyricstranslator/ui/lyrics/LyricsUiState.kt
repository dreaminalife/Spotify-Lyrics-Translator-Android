package com.dreaminalife.lyricstranslator.ui.lyrics

import com.dreaminalife.lyricstranslator.data.translation.TranslatedLyrics
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.Track

sealed interface LyricsContent {
    data object NothingPlaying : LyricsContent
    data object Loading : LyricsContent
    data object NotFound : LyricsContent
    data class Error(val message: String) : LyricsContent
    data class Loaded(val lyrics: Lyrics) : LyricsContent
}

sealed interface TranslationState {
    data object None : TranslationState
    data object Translating : TranslationState
    data object MissingKey : TranslationState
    data class Failed(val message: String) : TranslationState

    /** [trackKey] lets the UI ignore a translation that belongs to a different track. */
    data class Done(val trackKey: String, val translation: TranslatedLyrics) : TranslationState
}

data class LyricsUiState(
    val track: Track? = null,
    val content: LyricsContent = LyricsContent.NothingPlaying,
    val translation: TranslationState = TranslationState.None,
    /** The lyrics source's own translation in the target language (NetEase's Chinese), shown before [translation] is done. */
    val providedTranslation: List<String>? = null,
) {
    /** The translation for the current track, if ready. */
    val currentTranslation: TranslatedLyrics?
        get() = (translation as? TranslationState.Done)?.takeIf { it.trackKey == track?.key }?.translation

    /** Translated lines to show: the finished translation, else the source's own while the translator runs or if it failed. */
    val translationLines: List<String>?
        get() = currentTranslation?.lines ?: providedTranslation
}
