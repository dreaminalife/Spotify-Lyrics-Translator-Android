package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.cache.JsonFileCache
import com.dreaminalife.lyricstranslator.domain.LyricsProvider
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cache first, then each provider in order; the first non-empty result wins and is cached.
 * "Not found" is not cached, so it is retried the next time the track plays.
 */
class LyricsRepository(
    private val providers: List<LyricsProvider>,
    private val cache: JsonFileCache<Lyrics>,
) {
    suspend fun cached(track: Track): Lyrics? = cache.get(track.key)?.also {
        AppLog.d(TAG, "Cache hit for '${track.title}': ${describe(it)}")
    }

    /** Returns null if no provider has lyrics. Throws only if every provider failed with an error. */
    suspend fun get(track: Track): Lyrics? {
        cached(track)?.let { return it }
        AppLog.i(TAG, "Looking up '${track.title}' by '${track.artist}' (album '${track.album}', ${track.durationMs} ms, key ${track.key})")

        var lastError: Exception? = null
        var anyAnswered = false
        for (provider in providers) {
            val name = provider.source.displayName
            val started = System.currentTimeMillis()
            val lyrics = try {
                provider.fetch(track).also {
                    anyAnswered = true
                    if (it == null || it.lines.isEmpty()) {
                        AppLog.i(TAG, "$name has no lyrics (${System.currentTimeMillis() - started} ms)")
                    }
                }
            } catch (e: CancellationException) {
                AppLog.d(TAG, "$name cancelled (track changed or screen left)")
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "$name failed after ${System.currentTimeMillis() - started} ms", e)
                lastError = e
                null
            }
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                AppLog.i(TAG, "$name found ${describe(lyrics)} in ${System.currentTimeMillis() - started} ms")
                cache.put(track.key, value = lyrics)
                return lyrics
            }
        }
        if (!anyAnswered && lastError != null) {
            AppLog.e(TAG, "Every source failed for '${track.title}'")
            throw lastError
        }
        AppLog.i(TAG, "No lyrics found for '${track.title}' by '${track.artist}'")
        return null
    }

    suspend fun clear(track: Track) {
        AppLog.i(TAG, "Clearing cached lyrics for '${track.title}'")
        cache.removeGroup(track.key)
    }

    private fun describe(lyrics: Lyrics) =
        "${lyrics.lines.size} ${if (lyrics.synced) "synced" else "unsynced"} lines from ${lyrics.source.displayName}"

    private companion object {
        const val TAG = "Lyrics"
    }
}
