package com.dreaminalife.lyricstranslator.data.lyrics.utanet

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Searches Uta-Net by title, picks the row whose artist matches, then reads the full lyrics from the song page.
 * If the song page can't be read, falls back to the desktop method: the search row's text split on spaces.
 * Uta-Net lyrics are never time-synced.
 */
class UtaNetProvider(
    private val http: OkHttpClient,
    private val artistMatcher: ArtistMatcher,
) : LyricsProvider {
    override val source = LyricsSource.UTA_NET

    override suspend fun fetch(track: Track): Lyrics? {
        val searchUrl = "https://www.uta-net.com/search/".toHttpUrl().newBuilder()
            .addQueryParameter("Keyword", track.title)
            .addQueryParameter("Aselect", "2")
            .addQueryParameter("Bselect", "3")
            .build()
        // Uta-Net answers 404 when the search has no results.
        val search = getWithRetry(searchUrl.toString())
        if (search == null) {
            AppLog.d(TAG, "No search results (404) for '${track.title}'")
            return null
        }
        val rows = UtaNetParser.parseSearchResults(search.body)
        AppLog.d(TAG, "${rows.size} search rows: " + rows.joinToString { "${it.songId}:${it.artistName}" })
        if (rows.isEmpty() && search.body.isNotEmpty() && !search.body.contains("songlist-table-body")) {
            AppLog.w(TAG, "Search page has no results table; the page layout may have changed")
        }
        val row = artistMatcher.pick(track.artist, rows)
        if (row == null) {
            AppLog.i(TAG, "No row matches artist '${track.artist}'")
            return null
        }
        AppLog.i(TAG, "Picked song ${row.songId} '${row.title}' by '${row.artistName}' (artist ${row.artistId})")

        val songPageLines = row.songId?.let { fetchSongPage(it) }.orEmpty()
        val lines = songPageLines.ifEmpty {
            UtaNetParser.splitUtaidashi(row.utaidashi).also {
                AppLog.w(TAG, "Using the search-result text instead of the song page (${it.size} phrases)")
            }
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines.map { LyricsLine(0, it) }, synced = false, source = source, language = "ja")
    }

    private suspend fun fetchSongPage(songId: String): List<String> = try {
        val page = getWithRetry("https://www.uta-net.com/song/$songId/")
        val lines = page?.let { UtaNetParser.parseSongPage(it.body) }.orEmpty()
        if (page == null) {
            AppLog.w(TAG, "Song page $songId returned 404")
        } else if (lines.isEmpty()) {
            AppLog.w(TAG, "Song page $songId has no #kashi_area lyrics; the page layout may have changed")
        } else {
            AppLog.d(TAG, "Song page $songId: ${lines.size} lines")
        }
        lines
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "Song page $songId failed", e)
        emptyList()
    }

    /** Up to 3 attempts for network errors and 5xx. Returns null on 404. */
    private suspend fun getWithRetry(url: String): HttpResponse? {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        var lastError: IOException? = null
        repeat(3) { attempt ->
            if (attempt > 0) {
                AppLog.d(TAG, "Retry ${attempt + 1}/3 after: $lastError")
                delay(300L * attempt)
            }
            try {
                val response = http.send(request)
                when {
                    response.code == 404 -> return null
                    response.isSuccessful -> return response
                    response.code < 500 -> throw HttpException(response.code, "Uta-Net returned HTTP ${response.code}")
                    else -> lastError = HttpException(response.code, "Uta-Net returned HTTP ${response.code}")
                }
            } catch (e: HttpException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Uta-Net request failed")
    }

    private companion object {
        const val TAG = "Uta-Net"
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Lyrics-Translator/0.1)"
    }
}
