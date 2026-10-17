package com.dreaminalife.lyricstranslator.data.lyrics.qq

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/**
 * QQ Music's unofficial APIs: search with the web search, then fetch the LRC by song id posing as the desktop client.
 * Lyrics often come with a Chinese (Simplified) translation, kept in [Lyrics.translation].
 */
class QqMusicProvider(private val http: OkHttpClient) : LyricsProvider {
    override val source = LyricsSource.QQ_MUSIC

    override suspend fun fetch(track: Track): Lyrics? {
        val searchUrl = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("w", "${track.title} ${track.artist}")
            .addQueryParameter("format", "json")
            .addQueryParameter("p", "1")
            .addQueryParameter("n", "10")
            .addQueryParameter("cr", "1")
            .addQueryParameter("new_json", "1")
            .build()
        val songs = parseSearch(execute(Request.Builder().url(searchUrl).header("Referer", "https://y.qq.com/").build()))
        val song = pickSong(track, songs)
        if (song == null) {
            AppLog.d(TAG, "No match among ${songs.size} results: ${songs.joinToString { "'${it.title}' (${it.interval} s)" }}")
            return null
        }
        AppLog.d(TAG, "Match id=${song.id} '${song.title}' by '${song.singer.orEmpty().joinToString { it.name.orEmpty() }}' (${song.interval} s)")
        return parseLyrics(
            post("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo") {
                put("songID", song.id)
                put("trans", 1)
                put("roma", 0)
                put("qrc", 0)
                put("crypt", 0)
            },
        )
    }

    private suspend fun post(module: String, method: String, param: JsonObjectBuilder.() -> Unit): String {
        val body = buildJsonObject {
            put("comm", COMM)
            putJsonObject("req_1") {
                put("module", module)
                put("method", method)
                putJsonObject("param", param)
            }
        }
        return execute(
            Request.Builder()
                .url(URL)
                .header("Referer", "https://y.qq.com/")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    private suspend fun execute(request: Request): String {
        val response = http.send(request)
        if (!response.isSuccessful) throw HttpException(response.code, "QQ Music returned HTTP ${response.code}")
        return response.body
    }

    companion object {
        private const val TAG = "QQ Music"
        private const val URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"

        // The desktop client's search on musicu.fcg refuses every search from an address (code 2001) after a few
        // dozen in a short time, for minutes, while this search and the lyrics request keep working.
        private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Without the desktop client's identity every musicu.fcg request is refused.
        private val COMM: JsonObject = buildJsonObject {
            put("ct", "19")
            put("cv", "1859")
            put("uin", "0")
        }

        /** Songs come in Simplified Chinese (干杯 for 乾杯); the search itself finds them from either spelling. */
        fun parseSearch(json: String): List<QqSong> {
            val dto = AppJson.decodeFromString<SearchResponse>(json)
            if (dto.code != 0) throw IOException("QQ Music search returned code ${dto.code}")
            return dto.data?.song?.list.orEmpty()
        }

        /** [QqSong.name] is the bare title even for "Lemon (Live)"; [QqSong.title] has the version. */
        fun pickSong(track: Track, songs: List<QqSong>): QqSong? =
            SongMatcher.pick(track, songs, { it.title }, { it.interval?.times(1000) }, { song ->
                song.singer.orEmpty().flatMap { listOfNotNull(it.name, it.title) }
            })

        /** Returns null if QQ Music has no timed lyrics. Credit lines are kept as lyrics. */
        fun parseLyrics(json: String): Lyrics? {
            val request = AppJson.decodeFromString<LyricResponse>(json).request
            if (request?.code != 0) throw IOException("QQ Music lyrics returned code ${request?.code}")
            val lyric = decodeBase64(request.data?.lyric)
            val lines = LrcParser.parse(lyric)
            if (lines.isEmpty()) {
                AppLog.d(TAG, "lyric had no timed lines; first 200 chars: ${lyric.take(200)}")
                return null
            }
            val translation = ProvidedTranslations.pairByTimestamp(lines, decodeBase64(request.data?.trans))
            AppLog.d(TAG, "${lines.size} lines, ${translation?.lines.orEmpty().count { it.isNotBlank() }} translated")
            return Lyrics(lines, synced = true, source = LyricsSource.QQ_MUSIC, translation = translation)
        }

        private fun decodeBase64(text: String?): String =
            if (text.isNullOrEmpty()) "" else String(Base64.getDecoder().decode(text), Charsets.UTF_8)
    }
}

@Serializable
data class QqSong(val id: Long, val title: String, val interval: Long? = null, val singer: List<QqSinger>? = null)

@Serializable
data class QqSinger(val name: String? = null, val title: String? = null)

@Serializable
private data class SearchResponse(val code: Int? = null, val data: SearchData? = null)

@Serializable
private data class SearchData(val song: SongList? = null)

@Serializable
private data class SongList(val list: List<QqSong>? = null)

@Serializable
private data class LyricResponse(@SerialName("req_1") val request: LyricRequest? = null)

@Serializable
private data class LyricRequest(val code: Int? = null, val data: LyricData? = null)

@Serializable
private data class LyricData(val lyric: String? = null, val trans: String? = null)
