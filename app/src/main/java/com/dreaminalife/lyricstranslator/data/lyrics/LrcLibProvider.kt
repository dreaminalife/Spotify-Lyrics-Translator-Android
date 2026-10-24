package com.dreaminalife.lyricstranslator.data.lyrics

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
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.roundToLong

class LrcLibProvider(private val http: OkHttpClient) : LyricsProvider {
    override val source = LyricsSource.LRCLIB

    /**
     * A sent album must equal the entry's, and uploaders file songs under all sorts of albums ("Ado Videos"), so a
     * miss is retried without it. Without an album, title, artist and duration (±2 s) still have to match.
     */
    override suspend fun fetch(track: Track): Lyrics? {
        var response = get(track, track.album.takeIf { it.isNotBlank() })
        if (response.code == 404 && track.album.isNotBlank()) {
            AppLog.d(TAG, "No match (404) with album_name='${track.album}'; retrying without the album")
            response = get(track, album = null)
        }
        if (response.code == 404) {
            AppLog.d(TAG, "No match (404) for track_name='${track.title}' artist_name='${track.artist}'")
            return null
        }
        if (!response.isSuccessful) throw HttpException(response.code, "LRCLib returned HTTP ${response.code}")
        return parseResponse(response.body)
    }

    private suspend fun get(track: Track, album: String?): HttpResponse {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artist)
            .apply { if (album != null) addQueryParameter("album_name", album) }
            .addQueryParameter("duration", (track.durationMs / 1000.0).roundToLong().coerceAtLeast(0).toString())
            .build()
        return sendWithRetry(Request.Builder().url(url).build())
    }

    /** LRCLib sometimes answers 503 and succeeds moments later, so network errors and 5xx are retried. */
    private suspend fun sendWithRetry(request: Request): HttpResponse {
        repeat(ATTEMPTS - 1) { attempt ->
            try {
                val response = http.send(request)
                if (response.code < 500) return response
                AppLog.d(TAG, "HTTP ${response.code}, retrying (${attempt + 2}/$ATTEMPTS)")
            } catch (e: IOException) {
                AppLog.d(TAG, "$e, retrying (${attempt + 2}/$ATTEMPTS)")
            }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
        return http.send(request)
    }

    companion object {
        private const val TAG = "LRCLib"
        // Observed 503 streaks last up to ~5 s; 5 attempts wait 0.5 + 1 + 1.5 + 2 = 5 s in total.
        private const val ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 500L

        /** Prefers synced lyrics; falls back to plain lyrics. Returns null if there are neither. */
        fun parseResponse(json: String): Lyrics? {
            val dto = AppJson.decodeFromString<LrcLibResponse>(json)
            AppLog.d(
                TAG,
                "Match id=${dto.id} '${dto.trackName}' by '${dto.artistName}' (${dto.duration} s, instrumental=${dto.instrumental}): " +
                    "syncedLyrics ${dto.syncedLyrics?.length ?: 0} chars, plainLyrics ${dto.plainLyrics?.length ?: 0} chars",
            )
            val synced = dto.syncedLyrics?.let(LrcParser::parse).orEmpty()
            if (!dto.syncedLyrics.isNullOrBlank() && synced.isEmpty()) {
                AppLog.w(TAG, "syncedLyrics had no parsable timestamps; first 200 chars: ${dto.syncedLyrics.take(200)}")
            }
            if (synced.isNotEmpty()) {
                return Lyrics(synced, synced = true, source = LyricsSource.LRCLIB)
            }
            val plain = dto.plainLyrics.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (plain.isNotEmpty()) {
                return Lyrics(plain.map { LyricsLine(0, it) }, synced = false, source = LyricsSource.LRCLIB)
            }
            return null
        }
    }
}

@Serializable
private data class LrcLibResponse(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
)
