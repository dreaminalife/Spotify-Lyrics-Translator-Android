package com.dreaminalife.lyricstranslator.ui.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreaminalife.lyricstranslator.AppContainer
import com.dreaminalife.lyricstranslator.data.lyrics.LyricsRepository
import com.dreaminalife.lyricstranslator.data.settings.SecretStore
import com.dreaminalife.lyricstranslator.data.settings.SettingsRepository
import com.dreaminalife.lyricstranslator.data.translation.MissingApiKeyException
import com.dreaminalife.lyricstranslator.data.translation.TranslationRepository
import com.dreaminalife.lyricstranslator.data.translation.TranslatorFactory
import com.dreaminalife.lyricstranslator.domain.NowPlayingSource
import com.dreaminalife.lyricstranslator.domain.PlaybackController
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Track → lyrics → translation. Each stage uses flatMapLatest, so a track change (or refresh/settings change)
 * cancels the work still running for the previous one and its results never reach the screen.
 * Nothing is fetched or translated while the screen isn't visible; cached results show without a loading state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModel(
    private val nowPlayingSource: NowPlayingSource,
    playbackController: PlaybackController,
    private val lyricsRepository: LyricsRepository,
    private val translationRepository: TranslationRepository,
    private val translatorFactory: TranslatorFactory,
    settingsRepository: SettingsRepository,
    secretStore: SecretStore,
) : ViewModel(), PlaybackController by playbackController {

    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingSource.nowPlaying

    private val refreshCount = MutableStateFlow(0)

    private val lyricsState: StateFlow<Pair<Track?, LyricsContent>> = combine(
        nowPlayingSource.nowPlaying.map { it?.track }.distinctUntilChanged { old, new -> old?.key == new?.key },
        refreshCount,
    ) { track, _ -> track }
        .flatMapLatest(::loadLyrics)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to LyricsContent.NothingPlaying)

    private val translationState: StateFlow<TranslationState> = combine(
        lyricsState,
        settingsRepository.settings,
        secretStore.openRouterKey,
    ) { (track, content), settings, apiKey -> TranslationRequest(track, content, settings, apiKey) }
        .flatMapLatest(::translate)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslationState.None)

    val uiState: StateFlow<LyricsUiState> = combine(
        lyricsState,
        translationState,
        settingsRepository.settings,
    ) { (track, content), translation, settings ->
        val provided = (content as? LyricsContent.Loaded)?.lyrics?.providedTranslation(settings.targetLanguage)
        LyricsUiState(track, content, translation, provided)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState())

    init {
        AppLog.d(TAG, "Created")
        nowPlayingSource.start()
    }

    /** Starts listening if notification access was granted after this ViewModel was created. */
    fun onResume() = nowPlayingSource.start()

    /** Drops cached lyrics and translations for the current track and fetches them again. */
    fun refresh() {
        val track = lyricsState.value.first ?: return
        AppLog.i(TAG, "Refresh requested for '${track.title}'")
        viewModelScope.launch {
            lyricsRepository.clear(track)
            translationRepository.clear(track)
            refreshCount.value++
        }
    }

    override fun onCleared() {
        AppLog.d(TAG, "Cleared")
        nowPlayingSource.stop()
    }

    private fun loadLyrics(track: Track?): Flow<Pair<Track?, LyricsContent>> = flow {
        if (track == null) {
            AppLog.i(TAG, "Nothing playing")
            emit(null to LyricsContent.NothingPlaying)
            return@flow
        }
        AppLog.i(TAG, "Track: '${track.title}' by '${track.artist}'")
        lyricsRepository.cached(track)?.let {
            emit(track to LyricsContent.Loaded(it))
            return@flow
        }
        emit(track to LyricsContent.Loading)
        val content = try {
            lyricsRepository.get(track)?.let { LyricsContent.Loaded(it) } ?: LyricsContent.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Lyrics failed for '${track.title}'", e)
            LyricsContent.Error("Couldn't load lyrics: ${e.message ?: e.javaClass.simpleName}")
        }
        emit(track to content)
    }

    private fun translate(request: TranslationRequest): Flow<TranslationState> = flow {
        val (track, content, settings, apiKey) = request
        if (track == null || content !is LyricsContent.Loaded) {
            emit(TranslationState.None)
            return@flow
        }
        val translator = try {
            translatorFactory.create(settings, apiKey)
        } catch (e: MissingApiKeyException) {
            AppLog.i(TAG, "Translation skipped: OpenRouter key missing")
            emit(TranslationState.MissingKey)
            return@flow
        }
        translationRepository.cached(track, content.lyrics, translator, settings.targetLanguage)?.let {
            emit(TranslationState.Done(track.key, it))
            return@flow
        }
        emit(TranslationState.Translating)
        val result = try {
            TranslationState.Done(
                track.key,
                translationRepository.translate(track, content.lyrics, translator, settings.targetLanguage),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Translation failed for '${track.title}'", e)
            TranslationState.Failed(e.message ?: "Translation failed")
        }
        emit(result)
    }

    private data class TranslationRequest(
        val track: Track?,
        val content: LyricsContent,
        val settings: AppSettings,
        val apiKey: String?,
    )

    companion object {
        private const val TAG = "LyricsScreen"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                LyricsViewModel(
                    nowPlayingSource = container.nowPlayingSource,
                    playbackController = container.playbackController,
                    lyricsRepository = container.lyricsRepository,
                    translationRepository = container.translationRepository,
                    translatorFactory = container.translatorFactory,
                    settingsRepository = container.settingsRepository,
                    secretStore = container.secretStore,
                )
            }
        }
    }
}
