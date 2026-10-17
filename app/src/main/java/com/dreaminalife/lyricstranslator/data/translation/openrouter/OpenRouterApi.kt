package com.dreaminalife.lyricstranslator.data.translation.openrouter

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.domain.TranslationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Request building and response reading shared by everything that calls OpenRouter. */
object OpenRouterApi {
    private const val CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    fun chatRequest(apiKey: String, body: JsonObject): Request = Request.Builder()
        .url(CHAT_URL)
        .header("Authorization", "Bearer $apiKey")
        .header("X-Title", "Spotify Lyrics Translator")
        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

    /**
     * Builds the chat request body: the model's JSON body, then `model` and `messages` (which the body can't override).
     * Keys inside `extra_body` are moved to the top level, which is where OpenRouter reads `reasoning`/`provider`.
     */
    fun buildChatBody(model: String, prompt: String, modelBody: JsonObject): JsonObject = buildJsonObject {
        (modelBody["extra_body"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
        modelBody.forEach { (key, value) ->
            if (key !in setOf("extra_body", "model", "messages")) put(key, value)
        }
        put("model", JsonPrimitive(model))
        put("messages", JsonArray(listOf(
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(prompt))
            }
        )))
    }

    /** `choices[0].message.content` of a successful response. */
    fun messageContent(responseBody: String): String = try {
        AppJson.parseToJsonElement(responseBody).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
    } catch (e: Exception) {
        throw TranslationException("OpenRouter returned an unexpected response", e)
    }

    /** Model, finish reason and token usage of a response, for logging. "length" means max_tokens cut the reply short. */
    fun responseSummary(responseBody: String): String = try {
        val json = AppJson.parseToJsonElement(responseBody).jsonObject
        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        "model=${json["model"]?.jsonPrimitive?.contentOrNull} provider=${json["provider"]?.jsonPrimitive?.contentOrNull} " +
            "finish_reason=${choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull} usage=${json["usage"]}"
    } catch (e: Exception) {
        "unreadable (${responseBody.take(200)})"
    }

    /** `error.message` of a failed response, if present. */
    fun errorMessage(responseBody: String): String? = try {
        AppJson.parseToJsonElement(responseBody).jsonObject["error"]?.jsonObject?.get("message")
            ?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }
}
