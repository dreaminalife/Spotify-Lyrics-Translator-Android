package com.dreaminalife.lyricstranslator.data.translation

import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.data.translation.openrouter.OpenRouterApi
import com.dreaminalife.lyricstranslator.domain.TranslationException
import com.dreaminalife.lyricstranslator.domain.Translator
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** Translates the whole song in one chat request using the user's prompt template and the model's JSON body. */
class OpenRouterTranslator(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val promptTemplate: String,
    private val modelBody: JsonObject,
) : Translator {
    override val id = "openrouter:$model"
    override val displayName = model.substringAfterLast('/')

    override suspend fun translate(lines: List<String>, targetLanguage: String): List<String> {
        val prompt = PromptTemplate.fill(promptTemplate, targetLanguage, lines.joinToString("\n"))
        val body = OpenRouterApi.buildChatBody(model, prompt, modelBody)
        AppLog.i(
            TAG,
            "Request: ${lines.size} lines to $targetLanguage, prompt ${prompt.length} chars, " +
                "params ${JsonObject(body - "messages")}",
        )
        val started = System.currentTimeMillis()
        val response = http.send(OpenRouterApi.chatRequest(apiKey, body))
        val elapsed = System.currentTimeMillis() - started
        if (!response.isSuccessful) {
            val message = OpenRouterApi.errorMessage(response.body) ?: "HTTP ${response.code}"
            AppLog.w(TAG, "HTTP ${response.code} after $elapsed ms: ${response.body.take(500)}")
            throw TranslationException("OpenRouter error ${response.code}: $message")
        }
        AppLog.i(TAG, "Response in $elapsed ms: ${OpenRouterApi.responseSummary(response.body)}")
        val content = OpenRouterApi.messageContent(response.body)
        val translated = OpenRouterResponseParser.parse(content)
        if (translated.size != lines.size) {
            AppLog.w(TAG, "Line count mismatch: sent ${lines.size}, got ${translated.size}. Raw reply:\n$content")
            // Padding or cutting (as desktop did) would put translations under the wrong lines.
            throw TranslationException("$displayName returned ${translated.size} lines for ${lines.size}. Tap refresh to retry.")
        }
        return translated
    }

    private companion object {
        const val TAG = "OpenRouter"
    }
}
