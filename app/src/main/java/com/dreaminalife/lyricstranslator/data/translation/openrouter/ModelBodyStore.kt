package com.dreaminalife.lyricstranslator.data.translation.openrouter

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Per-model request parameters (temperature, max_tokens, reasoning, ...), like desktop's openrouter_models.json. */
class ModelBodyStore(private val file: File) {
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), JsonObject.serializer())

    suspend fun get(model: String): JsonObject = locked { readAll()[model] ?: DEFAULT_BODY }

    suspend fun set(model: String, body: JsonObject) = locked {
        AppLog.i("Settings", "Request JSON for $model = $body")
        val updated = readAll() + (model to body)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(serializer, updated))
        tmp.renameTo(file)
    }

    private fun readAll(): Map<String, JsonObject> =
        if (!file.exists()) emptyMap()
        else runCatching { AppJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())

    private suspend fun <T> locked(block: () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    companion object {
        /** Desktop used max_tokens 512, which can cut a full song short. */
        val DEFAULT_BODY = JsonObject(mapOf("temperature" to JsonPrimitive(0.2), "max_tokens" to JsonPrimitive(4000)))
    }
}
