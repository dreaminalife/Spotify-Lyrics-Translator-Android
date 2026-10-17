package com.dreaminalife.lyricstranslator.data.lyrics.netease

import com.dreaminalife.lyricstranslator.data.lyrics.LrcParser
import com.dreaminalife.lyricstranslator.data.lyrics.ProvidedTranslations
import com.dreaminalife.lyricstranslator.data.lyrics.SongMatcher
import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.net.HttpException
import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.domain.LyricsProvider
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * NetEase Cloud Music's unofficial web API: search, then fetch the LRC by song id.
 * Lyrics often come with a human Chinese (Simplified) translation, kept in [Lyrics.translation].
 */
class NetEaseProvider(private val http: OkHttpClient) : LyricsProvider {
    override val source = LyricsSource.NETEASE

    override suspend fun fetch(track: Track): Lyrics? {
        val searchUrl = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", "${track.title} ${track.artist}")
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "10")
            .build()
        val songs = parseSearch(get(searchUrl))
        val song = pickSong(track, songs)
        if (song == null) {
            AppLog.d(TAG, "No match among ${songs.size} results: ${songs.joinToString { "'${it.name}' (${it.dt} ms)" }}")
            return null
        }
        AppLog.d(TAG, "Match id=${song.id} '${song.name}' by '${song.ar.orEmpty().joinToString { it.name.orEmpty() }}' (${song.dt} ms)")
        val lyricUrl = "https://music.163.com/api/song/lyric".toHttpUrl().newBuilder()
            .addQueryParameter("id", song.id.toString())
            .addQueryParameter("lv", "1")
            .addQueryParameter("tv", "-1")
            .build()
        return parseLyrics(get(lyricUrl))
    }

    private suspend fun get(url: HttpUrl): String {
        val response = http.send(Request.Builder().url(url).build())
        if (!response.isSuccessful) throw HttpException(response.code, "NetEase returned HTTP ${response.code}")
        return response.body
    }

    companion object {
        private const val TAG = "NetEase"

        /** NetEase answers HTTP 200 even when it refuses a request; the refusal is in the body's code (e.g. -462). */
        fun parseSearch(json: String): List<NetEaseSong> {
            val dto = AppJson.decodeFromString<SearchResponse>(json)
            if (dto.code != 200) throw IOException("NetEase search returned code ${dto.code}")
            return dto.result?.songs.orEmpty()
        }

        fun pickSong(track: Track, songs: List<NetEaseSong>): NetEaseSong? =
            SongMatcher.pick(track, songs, { it.name }, { it.dt }, { song ->
                song.ar.orEmpty().flatMap { listOfNotNull(it.name) + it.alia.orEmpty() }
            })

        /** Returns null if NetEase has no timed lyrics. Credit lines (作词 : ...) are kept as lyrics. */
        fun parseLyrics(json: String): Lyrics? {
            val dto = AppJson.decodeFromString<LyricResponse>(json)
            if (dto.nolyric || dto.uncollected) {
                AppLog.d(TAG, "No lyrics (nolyric=${dto.nolyric}, uncollected=${dto.uncollected})")
                return null
            }
            val lines = LrcParser.parse(dto.lrc?.lyric.orEmpty())
            if (lines.isEmpty()) {
                AppLog.d(TAG, "lrc had no timed lines; first 200 chars: ${dto.lrc?.lyric.orEmpty().take(200)}")
                return null
            }
            val translation = ProvidedTranslations.pairByTimestamp(lines, dto.tlyric?.lyric.orEmpty())
            AppLog.d(TAG, "${lines.size} lines, ${translation?.lines.orEmpty().count { it.isNotBlank() }} translated")
            return Lyrics(lines, synced = true, source = LyricsSource.NETEASE, translation = translation)
        }
    }
}

@Serializable
data class NetEaseSong(val id: Long, val name: String, val dt: Long? = null, val ar: List<NetEaseArtist>? = null)

@Serializable
data class NetEaseArtist(val name: String? = null, val alia: List<String>? = null)

@Serializable
private data class SearchResponse(val code: Int? = null, val result: SearchResult? = null)

@Serializable
private data class SearchResult(val songs: List<NetEaseSong>? = null)

@Serializable
private data class LyricResponse(
    val nolyric: Boolean = false,
    val uncollected: Boolean = false,
    val lrc: LrcBlock? = null,
    val tlyric: LrcBlock? = null,
)

@Serializable
private data class LrcBlock(val lyric: String? = null)
