package com.dreaminalife.lyricstranslator.data.translation

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.net.HttpException
import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.domain.TranslationException
import com.dreaminalife.lyricstranslator.domain.Translator
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google Translate through the unofficial web endpoint (the kind deep_translator uses on desktop).
 * Lines are sent in batches joined with newlines; if a batch comes back with a different line count,
 * that batch is translated line by line instead.
 */
class GoogleWebTranslator(private val http: OkHttpClient) : Translator {
    override val id = "google"
    override val displayName = "Google"

    override suspend fun translate(lines: List<String>, targetLanguage: String): List<String> {
        val batches = chunk(lines)
        AppLog.i(TAG, "Translating ${lines.size} lines to $targetLanguage in ${batches.size} batch(es)")
        return batches.flatMapIndexed { index, batch ->
            val translated = splitLines(request(batch.joinToString("\n"), targetLanguage))
            if (translated.size == batch.size) {
                translated
            } else {
                AppLog.w(TAG, "Batch ${index + 1}: sent ${batch.size} lines, got ${translated.size}; translating line by line")
                batch.map { request(it, targetLanguage).trim() }
            }
        }
    }

    private suspend fun request(text: String, targetLanguage: String): String {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", targetLanguage)
            .addQueryParameter("dt", "t")
            .build()
        val request = Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
        val response = http.send(request)
        if (!response.isSuccessful) {
            AppLog.w(TAG, "HTTP ${response.code}: ${response.body.take(300)}")
            throw HttpException(response.code, "Google Translate returned HTTP ${response.code}")
        }
        return parseResponse(response.body)
    }

    companion object {
        private const val TAG = "Google"
        private const val MAX_BATCH_CHARS = 4500

        /** The response is `[[["translated", "original", ...], ...], ...]`; joins the translated segments. */
        fun parseResponse(json: String): String = try {
            (AppJson.parseToJsonElement(json).jsonArray[0] as? JsonArray).orEmpty()
                .joinToString("") { segment -> segment.jsonArray[0].jsonPrimitive.contentOrNull.orEmpty() }
        } catch (e: Exception) {
            AppLog.w(TAG, "Unexpected response: ${json.take(300)}", e)
            throw TranslationException("Google Translate returned an unexpected response", e)
        }

        fun splitLines(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        fun chunk(lines: List<String>): List<List<String>> {
            val batches = mutableListOf<MutableList<String>>()
            var length = 0
            for (line in lines) {
                if (batches.isEmpty() || length + line.length + 1 > MAX_BATCH_CHARS) {
                    batches += mutableListOf<String>()
                    length = 0
                }
                batches.last() += line
                length += line.length + 1
            }
            return batches
        }
    }
}
