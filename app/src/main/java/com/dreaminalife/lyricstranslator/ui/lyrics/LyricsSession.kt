package com.dreaminalife.lyricstranslator.ui.lyrics

import com.dreaminalife.lyricstranslator.data.lyrics.LyricsLanguage
import com.dreaminalife.lyricstranslator.data.lyrics.LyricsRepository
import com.dreaminalife.lyricstranslator.data.playback.NowPlayingUsers
import com.dreaminalife.lyricstranslator.data.settings.SecretStore
import com.dreaminalife.lyricstranslator.data.settings.SettingsRepository
import com.dreaminalife.lyricstranslator.data.translation.MissingApiKeyException
import com.dreaminalife.lyricstranslator.data.translation.TranslationRepository
import com.dreaminalife.lyricstranslator.data.translation.TranslatorFactory
import com.dreaminalife.lyricstranslator.domain.NowPlayingSource
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.LyricsSourcePreference
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.domain.model.TranslatorType
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
 * Track → lyrics → translation, shared by the lyrics screen and the floating window. Each stage uses flatMapLatest,
 * so a track change (or refresh/settings change) cancels the work still running for the previous one and its results
 * never reach the screen. Nothing is fetched or translated unless the lyrics screen or the floating window is showing;
 * cached results show without a loading state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsSession(
    nowPlayingSource: NowPlayingSource,
    private val lyricsRepository: LyricsRepository,
    private val lyricsLanguage: LyricsLanguage,
    private val translationRepository: TranslationRepository,
    private val translatorFactory: TranslatorFactory,
    private val settingsRepository: SettingsRepository,
    secretStore: SecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val users = NowPlayingUsers(nowPlayingSource)

    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingSource.nowPlaying

    private val refreshCount = MutableStateFlow(0)

    /**
     * The last track refreshed, which is translated even with OpenRouter auto-translate off.
     * Read, not observed: the lyrics reload that refresh starts re-runs the translation, so no request goes out for the
     * lyrics being replaced.
     */
    private var forcedTrackKey: String? = null

    private val lyricsState: StateFlow<LyricsLoad> = combine(
        nowPlayingSource.nowPlaying.map { it?.track }.distinctUntilChanged { old, new -> old?.key == new?.key },
        refreshCount,
        settingsRepository.lyricsSources,
    ) { track, _, preference -> track to preference }
        .flatMapLatest { (track, preference) -> loadLyrics(track, preference) }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            LyricsLoad(null, LyricsSourcePreference(), LyricsContent.NothingPlaying),
        )

    private val translationState: StateFlow<TranslationState> = combine(
        // Not the source preference: picking a source that has nothing for this song leaves the lyrics as they are.
        lyricsState.map { it.track to it.content }.distinctUntilChanged(),
        settingsRepository.settings,
        secretStore.openRouterKey,
    ) { (track, content), settings, apiKey -> TranslationRequest(track, content, settings, apiKey) }
        .flatMapLatest(::translate)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), TranslationState.None)

    val uiState: StateFlow<LyricsUiState> = combine(
        lyricsState,
        translationState,
        settingsRepository.settings,
    ) { (track, preference, content), translation, settings ->
        val provided = (content as? LyricsContent.Loaded)?.lyrics?.providedTranslation(settings.targetLanguage)
        LyricsUiState(
            track,
            content,
            translation,
            provided,
            sourceOrder = preference.copy(pinned = null).resolve(lyricsRepository.defaultOrder),
            pinnedSource = preference.pinned,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), LyricsUiState())

    /** Listens to the player until [release]; each user (the lyrics screen, the floating window) calls both once. */
    fun acquire() = users.acquire()

    fun release() = users.release()

    /** Starts listening if notification access was granted after [acquire]. */
    fun retry() = users.retry()

    /**
     * Drops the cached lyrics and translations of the source on screen for the current track and looks it up again.
     * Other sources' cached lyrics and translations stay. The track is translated even with auto-translate off.
     */
    fun refresh() {
        val (track, _, content) = lyricsState.value
        if (track == null) return
        val source = (content as? LyricsContent.Loaded)?.lyrics?.source
        AppLog.i(TAG, "Refresh requested for '${track.title}' (${source?.displayName ?: "no lyrics shown"})")
        forcedTrackKey = track.key
        scope.launch {
            // With no lyrics shown, nothing is cached for the sources tried first, so a new lookup is enough.
            if (source != null) {
                lyricsRepository.clear(track, source)
                translationRepository.clear(track, source)
            }
            refreshCount.value++
        }
    }

    /** Tries [source] first for every song, until another is picked or the order is changed in Settings. */
    fun selectSource(source: LyricsSource) {
        // Picking the source that is already first in the Settings order just goes back to that order.
        val pinned = source.takeIf { it != uiState.value.sourceOrder.firstOrNull() }
        AppLog.i(TAG, "Source picked: ${source.displayName}${if (pinned == null) " (first in Settings order)" else ""}")
        scope.launch { settingsRepository.setPinnedSource(pinned) }
    }

    private fun loadLyrics(track: Track?, preference: LyricsSourcePreference): Flow<LyricsLoad> = flow {
        if (track == null) {
            AppLog.i(TAG, "Nothing playing")
            emit(LyricsLoad(null, preference, LyricsContent.NothingPlaying))
            return@flow
        }
        AppLog.i(TAG, "Track: '${track.title}' by '${track.artist}'")
        lyricsRepository.cached(track, preference)?.let {
            emit(LyricsLoad(track, preference, LyricsContent.Loaded(it)))
            return@flow
        }
        emit(LyricsLoad(track, preference, LyricsContent.Loading))
        val content = try {
            lyricsRepository.get(track, preference)?.let { LyricsContent.Loaded(it) } ?: LyricsContent.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Lyrics failed for '${track.title}'", e)
            LyricsContent.Error("Couldn't load lyrics: ${e.message ?: e.javaClass.simpleName}")
        }
        emit(LyricsLoad(track, preference, content))
    }

    private fun translate(request: TranslationRequest): Flow<TranslationState> = flow {
        val (track, content, settings, apiKey) = request
        if (track == null || content !is LyricsContent.Loaded) {
            emit(TranslationState.None)
            return@flow
        }
        if (lyricsLanguage.isIn(content.lyrics, settings.targetLanguage)) {
            AppLog.i(TAG, "Translation skipped: '${track.title}' is already in ${settings.targetLanguage}")
            emit(TranslationState.SameLanguage(settings.targetLanguage))
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
            emit(TranslationState.Done(track.key, content.lyrics.source, it))
            return@flow
        }
        if (settings.translatorType == TranslatorType.OPENROUTER && !settings.openRouterAutoTranslate &&
            track.key != forcedTrackKey
        ) {
            AppLog.i(TAG, "Translation not requested: OpenRouter auto-translate is off for '${track.title}'")
            emit(TranslationState.NotRequested)
            return@flow
        }
        emit(TranslationState.Translating)
        val result = try {
            TranslationState.Done(
                track.key,
                content.lyrics.source,
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

    /** Lyrics for [track], with the [preference] they were loaded with. */
    private data class LyricsLoad(
        val track: Track?,
        val preference: LyricsSourcePreference,
        val content: LyricsContent,
    )

    private data class TranslationRequest(
        val track: Track?,
        val content: LyricsContent,
        val settings: AppSettings,
        val apiKey: String?,
    )

    private companion object {
        const val TAG = "LyricsScreen"
    }
}
