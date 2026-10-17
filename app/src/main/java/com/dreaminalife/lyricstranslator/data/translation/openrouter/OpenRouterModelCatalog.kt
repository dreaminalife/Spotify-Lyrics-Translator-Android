package com.dreaminalife.lyricstranslator.data.translation.openrouter

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.net.HttpException
import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

data class ModelInfo(
    val id: String,
    val name: String,
    val contextLength: Long?,
    /** USD per 1M tokens. */
    val promptPricePerM: Double?,
    val completionPricePerM: Double?,
)

class OpenRouterModelCatalog(private val http: OkHttpClient) {
    suspend fun fetch(apiKey: String?): List<ModelInfo> {
        val request = Request.Builder().url("https://openrouter.ai/api/v1/models").apply {
            if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        }.build()
        val response = http.send(request)
        if (!response.isSuccessful) {
            AppLog.w("OpenRouter", "Model list HTTP ${response.code}: ${response.body.take(300)}")
            throw HttpException(response.code, "OpenRouter returned HTTP ${response.code}")
        }
        return parse(response.body).also { AppLog.i("OpenRouter", "Loaded ${it.size} models") }
    }

    companion object {
        fun parse(json: String): List<ModelInfo> =
            AppJson.decodeFromString<ModelsResponse>(json).data.map { model ->
                ModelInfo(
                    id = model.id,
                    name = model.name ?: model.id,
                    contextLength = model.topProvider?.contextLength ?: model.contextLength,
                    promptPricePerM = model.pricing?.prompt?.toDoubleOrNull()?.times(1_000_000),
                    completionPricePerM = model.pricing?.completion?.toDoubleOrNull()?.times(1_000_000),
                )
            }.sortedBy { it.id }
    }
}

@Serializable
private data class ModelsResponse(val data: List<ModelDto> = emptyList())

@Serializable
private data class ModelDto(
    val id: String,
    val name: String? = null,
    @SerialName("context_length") val contextLength: Long? = null,
    @SerialName("top_provider") val topProvider: TopProviderDto? = null,
    val pricing: PricingDto? = null,
)

@Serializable
private data class TopProviderDto(
    @SerialName("context_length") val contextLength: Long? = null,
)

@Serializable
private data class PricingDto(val prompt: String? = null, val completion: String? = null)
