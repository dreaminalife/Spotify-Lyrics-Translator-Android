package com.dreaminalife.lyricstranslator.data.lyrics.kugou

import com.dreaminalife.lyricstranslator.data.lyrics.ProvidedTranslations
import com.dreaminalife.lyricstranslator.data.lyrics.SongMatcher
import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.net.HttpException
import com.dreaminalife.lyricstranslator.data.net.send
import com.dreaminalife.lyricstranslator.domain.LyricsProvider
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * Kugou's lyrics server: search by artist, title and duration, then download KRC, Kugou's scrambled word-timed format.
 * KRC is used rather than plain LRC because only KRC carries the Chinese (Simplified) translation.
 * The search knows artists by their native name only, so "Kenshi Yonezu" finds nothing.
 */
class KugouProvider(private val http: OkHttpClient) : LyricsProvider {
    override val source = LyricsSource.KUGOU

    override suspend fun fetch(track: Track): Lyrics? {
        val searchUrl = "https://krcs.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "mobi")
            .addQueryParameter("keyword", "${track.artist} - ${track.title}")
            .addQueryParameter("duration", track.durationMs.toString())
            .addQueryParameter("hash", "")
            .build()
        val candidates = AppJson.decodeFromString<SearchResponse>(get(searchUrl)).candidates.orEmpty()
        val candidate = pickCandidate(track, candidates)
        if (candidate == null) {
            AppLog.d(TAG, "No match among ${candidates.size} results: ${candidates.joinToString { "'${it.song}' (${it.duration} ms)" }}")
            return null
        }
        AppLog.d(TAG, "Match id=${candidate.id} '${candidate.song}' by '${candidate.singer}' (${candidate.duration} ms)")
        val downloadUrl = "https://krcs.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "pc")
            .addQueryParameter("id", candidate.id)
            .addQueryParameter("accesskey", candidate.accesskey)
            .addQueryParameter("fmt", "krc")
            .addQueryParameter("charset", "utf8")
            .build()
        val content = AppJson.decodeFromString<DownloadResponse>(get(downloadUrl)).content
        if (content.isNullOrBlank()) {
            AppLog.d(TAG, "Download had no content")
            return null
        }
        return parseKrc(decodeKrc(content))
    }

    private suspend fun get(url: HttpUrl): String {
        val response = http.send(Request.Builder().url(url).build())
        if (!response.isSuccessful) throw HttpException(response.code, "Kugou returned HTTP ${response.code}")
        return response.body
    }

    companion object {
        private const val TAG = "Kugou"
        private val KEY = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )
        private val LINE = Regex("""^\[(\d+),\d+](.*)$""")
        private val WORD_TAG = Regex("""<\d+,\d+,\d+>""")
        private const val LANGUAGE_PREFIX = "[language:"
        private const val TRANSLATION_TYPE = 1

        fun pickCandidate(track: Track, candidates: List<KugouCandidate>): KugouCandidate? =
            SongMatcher.pick(track, candidates, { it.song.orEmpty() }, { it.duration }, { listOfNotNull(it.singer) })

        /** Base64, then a 4-byte "krc1" header, a repeating XOR key and zlib. */
        fun decodeKrc(content: String): String {
            val bytes = Base64.getDecoder().decode(content).drop(4).mapIndexed { i, b ->
                (b.toInt() xor KEY[i % KEY.size].toInt()).toByte()
            }.toByteArray()
            return InflaterInputStream(bytes.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        }

        /**
         * KRC lines look like "[startMs,durationMs]<offset,duration,0>word<...>word". The translation sits in a
         * "[language:<Base64 JSON>]" line, one row per lyrics line. Returns null if there are no lyrics lines.
         */
        fun parseKrc(krc: String): Lyrics? {
            val lines = krc.lines().mapNotNull { raw ->
                LINE.find(raw.trim())?.let { LyricsLine(it.groupValues[1].toLong(), it.groupValues[2].replace(WORD_TAG, "").trim()) }
            }
            if (lines.isEmpty()) {
                AppLog.d(TAG, "KRC had no lyrics lines; first 200 chars: ${krc.take(200)}")
                return null
            }
            val rows = krc.lines().map { it.trim() }.firstOrNull { it.startsWith(LANGUAGE_PREFIX) }
                ?.removePrefix(LANGUAGE_PREFIX)?.removeSuffix("]")
                ?.takeIf { it.isNotEmpty() }
                ?.let { AppJson.decodeFromString<KrcLanguage>(String(Base64.getDecoder().decode(it), Charsets.UTF_8)) }
                ?.content.orEmpty()
                .firstOrNull { it.type == TRANSLATION_TYPE }
                ?.lyricContent.orEmpty()
                .map { it.firstOrNull().orEmpty() }
            if (rows.isNotEmpty() && rows.size != lines.size) {
                AppLog.w(TAG, "Ignoring translation: ${rows.size} rows vs ${lines.size} lines")
            }
            val translation = rows.takeIf { it.size == lines.size }?.let(ProvidedTranslations::of)
            AppLog.d(TAG, "${lines.size} lines, ${translation?.lines.orEmpty().count { it.isNotBlank() }} translated")
            return Lyrics(lines, synced = true, source = LyricsSource.KUGOU, translation = translation)
        }
    }
}

@Serializable
data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val song: String? = null,
    val singer: String? = null,
    val duration: Long? = null,
)

@Serializable
private data class SearchResponse(val candidates: List<KugouCandidate>? = null)

@Serializable
private data class DownloadResponse(val content: String? = null)

@Serializable
private data class KrcLanguage(val content: List<KrcLanguageItem>? = null)

/** [type] 0 is romanization (one entry per word), 1 is the translation (one entry per line). */
@Serializable
private data class KrcLanguageItem(val type: Int? = null, val lyricContent: List<List<String>>? = null)
