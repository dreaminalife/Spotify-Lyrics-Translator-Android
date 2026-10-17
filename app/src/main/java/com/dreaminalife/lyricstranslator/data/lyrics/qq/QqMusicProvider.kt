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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/**
 * QQ Music's unofficial API, posing as the desktop client: search, then fetch the LRC by song id.
 * Lyrics often come with a Chinese (Simplified) translation, kept in [Lyrics.translation].
 */
class QqMusicProvider(private val http: OkHttpClient) : LyricsProvider {
    override val source = LyricsSource.QQ_MUSIC

    override suspend fun fetch(track: Track): Lyrics? {
        val songs = parseSearch(
            post("music.search.SearchCgiService", "DoSearchForQQMusicDesktop") {
                put("query", "${track.title} ${track.artist}")
                put("search_type", 0)
                put("num_per_page", 10)
                put("page_num", 1)
            },
        )
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
        val request = Request.Builder()
            .url(URL)
            .header("Referer", "https://y.qq.com/")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = http.send(request)
        if (!response.isSuccessful) throw HttpException(response.code, "QQ Music returned HTTP ${response.code}")
        return response.body
    }

    companion object {
        private const val TAG = "QQ Music"
        private const val URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Without the desktop client's identity every search is refused.
        private val COMM: JsonObject = buildJsonObject {
            put("ct", "19")
            put("cv", "1859")
            put("uin", "0")
        }

        /** QQ Music answers HTTP 200 even when it refuses a request, e.g. code 2001 when searches come too fast. */
        fun parseSearch(json: String): List<QqSong> {
            val request = AppJson.decodeFromString<SearchResponse>(json).request
            if (request?.code != 0) throw IOException("QQ Music search returned code ${request?.code}")
            return request.data?.body?.song?.list.orEmpty()
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
private data class SearchResponse(@SerialName("req_1") val request: SearchRequest? = null)

@Serializable
private data class SearchRequest(val code: Int? = null, val data: SearchData? = null)

@Serializable
private data class SearchData(val body: SearchBody? = null)

@Serializable
private data class SearchBody(val song: SongList? = null)

@Serializable
private data class SongList(val list: List<QqSong>? = null)

@Serializable
private data class LyricResponse(@SerialName("req_1") val request: LyricRequest? = null)

@Serializable
private data class LyricRequest(val code: Int? = null, val data: LyricData? = null)

@Serializable
private data class LyricData(val lyric: String? = null, val trans: String? = null)
