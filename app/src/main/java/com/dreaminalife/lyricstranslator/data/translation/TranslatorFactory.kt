package com.dreaminalife.lyricstranslator.data.translation

import com.dreaminalife.lyricstranslator.data.translation.openrouter.ModelBodyStore
import com.dreaminalife.lyricstranslator.domain.Translator
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import com.dreaminalife.lyricstranslator.domain.model.TranslatorType
import com.dreaminalife.lyricstranslator.log.AppLog
import okhttp3.OkHttpClient

class MissingApiKeyException : Exception("OpenRouter is selected but no API key is set")

/** Builds the [Translator] for the current settings. Add a branch here when adding a [TranslatorType]. */
class TranslatorFactory(
    private val http: OkHttpClient,
    private val modelBodyStore: ModelBodyStore,
) {
    private val google by lazy { GoogleWebTranslator(http) }

    /** @throws MissingApiKeyException if OpenRouter is selected without a key. */
    suspend fun create(settings: AppSettings, openRouterKey: String?): Translator = when (settings.translatorType) {
        TranslatorType.GOOGLE -> google
        TranslatorType.OPENROUTER -> {
            val key = openRouterKey?.takeIf { it.isNotBlank() } ?: run {
                AppLog.w("Translation", "OpenRouter selected but no API key is saved")
                throw MissingApiKeyException()
            }
            OpenRouterTranslator(
                http = http,
                apiKey = key,
                model = settings.openRouterModel,
                promptTemplate = settings.promptTemplate,
                modelBody = modelBodyStore.get(settings.openRouterModel),
            )
        }
    }
}
