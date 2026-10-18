package com.dreaminalife.lyricstranslator.ui.lyrics

import com.dreaminalife.lyricstranslator.data.translation.TranslatedLyrics
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
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

    /** OpenRouter auto-translate is off (for every song or the song's language) and nothing is cached; refreshing translates it. */
    data object NotRequested : TranslationState

    /** The lyrics are already in [targetLanguage], so nothing was sent to the translator. */
    data class SameLanguage(val targetLanguage: String) : TranslationState

    data class Failed(val message: String) : TranslationState

    /** [trackKey] and [source] let the UI ignore a translation of a different track, or of another source's lyrics. */
    data class Done(val trackKey: String, val source: LyricsSource, val translation: TranslatedLyrics) : TranslationState
}

data class LyricsUiState(
    val track: Track? = null,
    val content: LyricsContent = LyricsContent.NothingPlaying,
    val translation: TranslationState = TranslationState.None,
    /** The lyrics source's own translation in the target language (NetEase's Chinese), shown before [translation] is done. */
    val providedTranslation: List<String>? = null,
    /** The lyrics source order from Settings, without [pinnedSource] moved to the top. */
    val sourceOrder: List<LyricsSource> = emptyList(),
    /** The source picked on the lyrics screen, tried before the others for every song. */
    val pinnedSource: LyricsSource? = null,
) {
    /** The translation of the lyrics on screen, if ready. */
    val currentTranslation: TranslatedLyrics?
        get() = (translation as? TranslationState.Done)
            ?.takeIf { it.trackKey == track?.key && it.source == (content as? LyricsContent.Loaded)?.lyrics?.source }
            ?.translation

    /** Translated lines to show: the finished translation, else the source's own while the translator runs or if it failed. */
    val translationLines: List<String>?
        get() = currentTranslation?.lines ?: providedTranslation
}
