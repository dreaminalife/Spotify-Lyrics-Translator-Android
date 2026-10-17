package com.dreaminalife.lyricstranslator.data.lyrics.spotify

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.net.HttpException
import com.dreaminalife.lyricstranslator.data.net.HttpResponse
import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.domain.LyricsProvider
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Spotify's own lyrics, fetched the way the syrics Python library does: the sp_dc cookie plus a TOTP buys a
 * web-player access token, which is then used on the color-lyrics endpoint. Unofficial and may break whenever
 * Spotify changes it; failures fall through to the next provider.
 */
class SyricsProvider(
    private val http: OkHttpClient,
    private val spDc: suspend () -> String?,
) : LyricsProvider {
    override val source = LyricsSource.SPOTIFY

    private val mutex = Mutex()
    private var secret: SpotifyTotp.Secret? = null
    private var token: Token? = null

    override suspend fun fetch(track: Track): Lyrics? {
        val trackId = track.spotifyTrackId ?: run {
            AppLog.d(TAG, "No Spotify track ID; skipping")
            return null
        }
        // Cookie values never contain whitespace, but a pasted one can pick up line breaks.
        val cookie = spDc()?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() } ?: run {
            AppLog.d(TAG, "No sp_dc cookie set; skipping")
            return null
        }
        var response = requestLyrics(trackId, accessToken(cookie))
        if (response.code == 401) {
            AppLog.d(TAG, "Access token rejected (401); logging in again")
            mutex.withLock { token = null }
            response = requestLyrics(trackId, accessToken(cookie))
        }
        if (response.code == 404) {
            AppLog.d(TAG, "No lyrics (404) for track $trackId")
            return null
        }
        if (!response.isSuccessful) throw HttpException(response.code, "Spotify lyrics returned HTTP ${response.code}")
        return parseResponse(response.body)
    }

    private suspend fun requestLyrics(trackId: String, accessToken: String): HttpResponse {
        val url = "$LYRICS_URL/$trackId".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("market", "from_token")
            .build()
        return http.send(webPlayerRequest(url.toString()).header("Authorization", "Bearer $accessToken").build())
    }

    /** A cached token for [cookie], or a fresh one. Tokens are tied to the cookie, so changing it forces a login. */
    private suspend fun accessToken(cookie: String): String = mutex.withLock {
        token?.takeIf { it.spDc == cookie && it.expiresAtMs - EXPIRY_MARGIN_MS > System.currentTimeMillis() }
            ?.let { return@withLock it.value }
        try {
            login(cookie).also { token = it }.value
        } catch (e: IOException) {
            // The secret may have been rotated; fetch it again next time.
            secret = null
            throw e
        }
    }

    private suspend fun login(cookie: String): Token {
        val secret = secret ?: fetchSecret().also { secret = it }
        val serverTimeMs = getJson<ServerTimeResponse>(webPlayerRequest(SERVER_TIME_URL).build()).serverTime * 1000
        val url = TOKEN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("reason", "init")
            .addQueryParameter("productType", "web-player")
            .addQueryParameter("totp", SpotifyTotp.generate(secret.key, serverTimeMs))
            .addQueryParameter("totpVer", secret.version)
            .addQueryParameter("ts", serverTimeMs.toString())
            .build()
        val dto = getJson<TokenResponse>(webPlayerRequest(url.toString()).header("Cookie", "sp_dc=$cookie").build())
        val accessToken = dto.accessToken?.takeIf { it.isNotBlank() && !dto.isAnonymous }
            ?: throw IOException("sp_dc cookie is invalid or expired")
        AppLog.i(TAG, "Got a Spotify access token (TOTP version ${secret.version})")
        return Token(cookie, accessToken, dto.accessTokenExpirationTimestampMs)
    }

    private suspend fun fetchSecret(): SpotifyTotp.Secret {
        val response = http.send(Request.Builder().url(SpotifyTotp.SECRET_DICT_URL).build())
        if (!response.isSuccessful) throw HttpException(response.code, "TOTP secret returned HTTP ${response.code}")
        return SpotifyTotp.parseSecretDict(response.body)
    }

    private suspend inline fun <reified T> getJson(request: Request): T {
        val response = http.send(request)
        if (!response.isSuccessful) {
            throw HttpException(response.code, "${request.url.encodedPath} returned HTTP ${response.code}: ${response.body.take(200)}")
        }
        return AppJson.decodeFromString<T>(response.body)
    }

    private class Token(val spDc: String, val value: String, val expiresAtMs: Long)

    companion object {
        private const val TAG = "Syrics"
        private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
        private const val TOKEN_URL = "https://open.spotify.com/api/token"
        private const val LYRICS_URL = "https://spclient.wg.spotify.com/color-lyrics/v2/track"
        private const val EXPIRY_MARGIN_MS = 60_000L

        // Same browser identity syrics uses.
        private fun webPlayerRequest(url: String) = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Origin", "https://open.spotify.com/")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")
            .header("App-Platform", "WebPlayer")
            .header("Spotify-App-Version", "1.2.46.25.g7f189073")

        /** Returns null if the response has no lines. */
        fun parseResponse(json: String): Lyrics? {
            val lyrics = AppJson.decodeFromString<ColorLyricsResponse>(json).lyrics ?: return null
            val synced = lyrics.syncType != "UNSYNCED"
            AppLog.d(TAG, "syncType=${lyrics.syncType} language=${lyrics.language}: ${lyrics.lines.size} lines")
            val lines = lyrics.lines.map {
                LyricsLine(if (synced) it.startTimeMs?.toLongOrNull() ?: 0 else 0, it.words.orEmpty())
            }
            if (lines.isEmpty()) return null
            return Lyrics(lines, synced = synced, source = LyricsSource.SPOTIFY, language = lyrics.language)
        }
    }
}

@Serializable
private data class ServerTimeResponse(val serverTime: Long)

@Serializable
private data class TokenResponse(
    val accessToken: String? = null,
    val accessTokenExpirationTimestampMs: Long = 0,
    val isAnonymous: Boolean = false,
)

@Serializable
private data class ColorLyricsResponse(val lyrics: ColorLyrics? = null)

@Serializable
private data class ColorLyrics(
    val syncType: String? = null,
    val language: String? = null,
    val lines: List<ColorLyricsLine> = emptyList(),
)

@Serializable
private data class ColorLyricsLine(val startTimeMs: String? = null, val words: String? = null)
