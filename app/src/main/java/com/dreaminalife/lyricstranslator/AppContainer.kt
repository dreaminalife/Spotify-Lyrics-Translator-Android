package com.dreaminalife.lyricstranslator

import android.content.Context
import com.dreaminalife.lyricstranslator.data.cache.JsonFileCache
import com.dreaminalife.lyricstranslator.data.lyrics.LrcLibProvider
import com.dreaminalife.lyricstranslator.data.lyrics.LyricsLanguage
import com.dreaminalife.lyricstranslator.data.lyrics.LyricsRepository
import com.dreaminalife.lyricstranslator.data.lyrics.MlKitLanguageIdentifier
import com.dreaminalife.lyricstranslator.data.lyrics.kugou.KugouProvider
import com.dreaminalife.lyricstranslator.data.lyrics.netease.NetEaseProvider
import com.dreaminalife.lyricstranslator.data.lyrics.qq.QqMusicProvider
import com.dreaminalife.lyricstranslator.data.lyrics.spotify.SyricsProvider
import com.dreaminalife.lyricstranslator.data.lyrics.utanet.ExactArtistMatcher
import com.dreaminalife.lyricstranslator.data.lyrics.utanet.FallbackArtistMatcher
import com.dreaminalife.lyricstranslator.data.lyrics.utanet.OpenRouterArtistMatcher
import com.dreaminalife.lyricstranslator.data.lyrics.utanet.UtaNetProvider
import com.dreaminalife.lyricstranslator.data.net.createHttpClient
import com.dreaminalife.lyricstranslator.data.playback.MediaSessionNowPlayingSource
import com.dreaminalife.lyricstranslator.data.settings.SecretStore
import com.dreaminalife.lyricstranslator.data.settings.SettingsRepository
import com.dreaminalife.lyricstranslator.data.translation.TranslatedLyrics
import com.dreaminalife.lyricstranslator.data.translation.TranslationRepository
import com.dreaminalife.lyricstranslator.data.translation.TranslatorFactory
import com.dreaminalife.lyricstranslator.data.translation.openrouter.ModelBodyStore
import com.dreaminalife.lyricstranslator.data.translation.openrouter.OpenRouterModelCatalog
import com.dreaminalife.lyricstranslator.domain.LyricsProvider
import com.dreaminalife.lyricstranslator.domain.NowPlayingSource
import com.dreaminalife.lyricstranslator.domain.PlaybackController
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import kotlinx.coroutines.flow.first
import java.io.File

/** Creates and holds the app's singletons. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val http = createHttpClient()

    val settingsRepository = SettingsRepository(appContext)
    val secretStore = SecretStore(appContext)
    val modelBodyStore = ModelBodyStore(File(appContext.filesDir, "openrouter_model_bodies.json"))
    val modelCatalog = OpenRouterModelCatalog(http)

    private val mediaSessions = MediaSessionNowPlayingSource(appContext)
    val nowPlayingSource: NowPlayingSource = mediaSessions
    val playbackController: PlaybackController = mediaSessions

    // The default order (users can change it in Settings); the first provider with lyrics wins. The Chinese services
    // go first for their timed lyrics and Chinese translations; QQ Music and Kugou fill NetEase's gaps.
    private val lyricsProviders: List<LyricsProvider> = listOf(
        NetEaseProvider(http),
        QqMusicProvider(http),
        KugouProvider(http),
        SyricsProvider(http, spDc = { secretStore.spDc.first() }),
        LrcLibProvider(http),
        UtaNetProvider(
            http,
            FallbackArtistMatcher(listOf(
                ExactArtistMatcher(),
                OpenRouterArtistMatcher(
                    http,
                    apiKey = { secretStore.openRouterKey.first() },
                    model = { settingsRepository.settings.first().openRouterModel },
                ),
            )),
        ),
    )

    val lyricsRepository = LyricsRepository(
        lyricsProviders,
        JsonFileCache(File(appContext.filesDir, "cache/lyrics"), Lyrics.serializer()),
    )
    val lyricsLanguage = LyricsLanguage(MlKitLanguageIdentifier()::identify)
    val translationRepository = TranslationRepository(
        JsonFileCache(File(appContext.filesDir, "cache/translations"), TranslatedLyrics.serializer()),
    )
    val translatorFactory = TranslatorFactory(http, modelBodyStore)
}
