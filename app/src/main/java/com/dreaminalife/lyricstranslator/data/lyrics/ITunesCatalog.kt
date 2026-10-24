package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.net.HttpException
import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Apple's public iTunes Search API, used for names only: it has no lyrics. The US and Japan stores share track ids
 * but name songs differently ("Usseewa" and うっせぇわ, "Kenshi Yonezu" and 米津玄師), so the playing track is
 * matched in the US store, then looked up in the Japan store. Apple allows about 20 calls a minute.
 */
class ITunesCatalog(private val http: OkHttpClient) {
    /** Returns null if the US store has no matching song. Throws on network/parse errors. */
    suspend fun nativeNames(track: Track): NativeNames? {
        val searchUrl = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "${track.title} ${track.artist}")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .addQueryParameter("country", "US")
            .build()
        val songs = parseResults(get(searchUrl))
        val song = pickSong(track, songs) ?: run {
            AppLog.d(TAG, "No match among ${songs.size} results: ${songs.joinToString { "'${it.trackName}' (${it.trackTimeMillis} ms)" }}")
            return null
        }
        val lookupUrl = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", song.trackId.toString())
            .addQueryParameter("country", "JP")
            .build()
        return parseResults(get(lookupUrl)).firstOrNull()?.let(::namesOf)?.also {
            AppLog.d(TAG, "Native names of '${track.title}' by '${track.artist}': '${it.title}' by '${it.artist}'")
        }
    }

    private suspend fun get(url: HttpUrl): String {
        val response = http.send(Request.Builder().url(url).build())
        if (!response.isSuccessful) throw HttpException(response.code, "iTunes returned HTTP ${response.code}")
        return response.body
    }

    companion object {
        private const val TAG = "iTunes"

        /** Album and artist results come too; only songs are kept. */
        fun parseResults(json: String): List<ITunesSong> =
            AppJson.decodeFromString<SearchResponse>(json).results.filter { it.trackId != null && it.trackName != null }

        fun pickSong(track: Track, songs: List<ITunesSong>): ITunesSong? =
            SongMatcher.pick(track, songs, { it.trackName.orEmpty() }, { it.trackTimeMillis }, { listOfNotNull(it.artistName) })

        fun namesOf(song: ITunesSong) = NativeNames(song.trackName.orEmpty(), song.artistName.orEmpty())
    }
}

@Serializable
data class ITunesSong(
    val trackId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val trackTimeMillis: Long? = null,
)

@Serializable
private data class SearchResponse(val results: List<ITunesSong> = emptyList())
