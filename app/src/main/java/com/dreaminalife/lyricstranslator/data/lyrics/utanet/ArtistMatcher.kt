package com.dreaminalife.lyricstranslator.data.lyrics.utanet

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.data.translation.openrouter.OpenRouterApi
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/** Picks which Uta-Net search row belongs to the Spotify artist. Uta-Net search only matches on the title. */
fun interface ArtistMatcher {
    suspend fun pick(spotifyArtist: String, candidates: List<UtaNetSearchRow>): UtaNetSearchRow?
}

/** Tries each matcher in order and returns the first match. */
class FallbackArtistMatcher(private val matchers: List<ArtistMatcher>) : ArtistMatcher {
    override suspend fun pick(spotifyArtist: String, candidates: List<UtaNetSearchRow>): UtaNetSearchRow? =
        matchers.firstNotNullOfOrNull { it.pick(spotifyArtist, candidates) }
}

/** Case-insensitive match that ignores full-width and repeated spaces. */
class ExactArtistMatcher : ArtistMatcher {
    override suspend fun pick(spotifyArtist: String, candidates: List<UtaNetSearchRow>): UtaNetSearchRow? {
        val wanted = normalize(spotifyArtist)
        return candidates.firstOrNull { normalize(it.artistName) == wanted }.also {
            AppLog.d("Uta-Net", "Exact artist match for '$wanted': ${it?.artistId ?: "none"}")
        }
    }

    companion object {
        fun normalize(name: String): String =
            name.replace('　', ' ').trim().split(Regex("""\s+""")).joinToString(" ").lowercase()
    }
}

/**
 * Asks an LLM to match romanized/translated artist names (e.g. "Kenshi Yonezu" and its Japanese spelling).
 * Only runs when an OpenRouter key is set; uses the desktop app's prompt with the model chosen in Settings
 * (desktop's fixed openai/gpt-5-chat was removed from OpenRouter).
 */
class OpenRouterArtistMatcher(
    private val http: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val model: suspend () -> String,
) : ArtistMatcher {
    override suspend fun pick(spotifyArtist: String, candidates: List<UtaNetSearchRow>): UtaNetSearchRow? {
        if (candidates.isEmpty()) return null
        val key = apiKey()?.takeIf { it.isNotBlank() }
        if (key == null) {
            AppLog.d(TAG, "Skipped: no OpenRouter key")
            return null
        }
        val model = model()
        AppLog.i(TAG, "Asking $model to match '$spotifyArtist' among ${candidates.size} candidates")
        return try {
            val body = buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildPrompt(spotifyArtist, candidates))
                    })
                })
                put("temperature", 0.7)
            }
            val response = http.send(OpenRouterApi.chatRequest(key, body))
            if (!response.isSuccessful) {
                AppLog.w(TAG, "OpenRouter HTTP ${response.code}: ${OpenRouterApi.errorMessage(response.body) ?: response.body.take(300)}")
                return null
            }
            val content = OpenRouterApi.messageContent(response.body)
            val artistId = extractArtistId(content)
            candidates.firstOrNull { it.artistId == artistId }.also {
                AppLog.i(TAG, "Reply '${content.take(200)}' -> ${it?.let { row -> "${row.artistId} ${row.artistName}" } ?: "no match"}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Artist matching failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "Uta-Net/AI match"

        fun buildPrompt(spotifyArtist: String, candidates: List<UtaNetSearchRow>): String {
            val slim = buildJsonArray {
                candidates.forEach {
                    add(buildJsonObject {
                        put("song_title", it.title)
                        put("artist_id", it.artistId)
                        put("artist_name", it.artistName)
                    })
                }
            }
            return "You are helping to match a song artist from Spotify with a Japanese artist from Uta-Net lyrics database.\n\n" +
                "Current Spotify artist name: '" + spotifyArtist + "'\n\n" +
                "Available candidates from Uta-Net search results:\n" +
                PrettyJson.encodeToString(JsonArray.serializer(), slim) + "\n\n" +
                "TASK: Find which candidate artist matches the current Spotify artist. " +
                "Consider common variations: English vs Japanese names, romanized versions, " +
                "stage names, full names vs shortened names, or common alternative spellings.\n\n" +
                "RESPONSE FORMAT: Return ONLY a JSON object with exactly one key:\n" +
                "- {\"artist_id\": \"<matching_artist_id>\"} if you find a good match\n" +
                "- {\"artist_id\": null} if no good match is found\n\n" +
                "Examples:\n" +
                "- If current artist 'Kenshi Yonezu' matches candidate with artist_id '1234', return: {\"artist_id\": \"1234\"}\n" +
                "- If current artist 'Yoasobi' matches candidate with artist_id '5678', return: {\"artist_id\": \"5678\"}\n" +
                "- If no match is found, return: {\"artist_id\": null}\n\n" +
                "Do not include any other text, explanations, or formatting outside the JSON."
        }

        /** Reads `{"artist_id": "..."}` from a reply that may be wrapped in code fences or extra text. */
        fun extractArtistId(content: String): String? {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                AppJson.parseToJsonElement(content.substring(start, end + 1))
                    .jsonObject["artist_id"]?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                null
            }
        }

        private val PrettyJson = Json { prettyPrint = true }
    }
}
