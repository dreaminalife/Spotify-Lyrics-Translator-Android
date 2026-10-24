package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.cache.JsonFileCache
import com.dreaminalife.lyricstranslator.domain.LyricsProvider
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.LyricsSourcePreference
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * Goes through the providers in the user's order; the first with lyrics, cached or fetched, wins.
 * Each provider's lyrics are cached separately. "Not found" and errors are not cached, so a provider without lyrics
 * is asked again every time the track plays, before any provider after it. A provider that takes longer than
 * [SOURCE_TIME_LIMIT_MS] counts as failed.
 *
 * When a provider first misses, the track's native names are looked up with [nativeNames]. If they differ from
 * Spotify's, that provider searches again with them, and every provider after it searches them first, then Spotify's.
 * Found lyrics tell [nativeNames] which artists sing in Japanese.
 */
class LyricsRepository(
    private val providers: List<LyricsProvider>,
    private val cache: JsonFileCache<Lyrics>,
    private val nativeNames: NativeNameLookup? = null,
) {
    /** The order providers are tried in unless the user changes it. */
    val defaultOrder: List<LyricsSource> = providers.map { it.source }

    /** The first provider's cached lyrics: the only ones that can be shown without asking a provider first. */
    suspend fun cached(track: Track, preference: LyricsSourcePreference): Lyrics? =
        cached(track, preference.resolve(defaultOrder).first())

    /** Returns null if no provider has lyrics. Throws only if every provider failed with an error. */
    suspend fun get(track: Track, preference: LyricsSourcePreference): Lyrics? {
        val order = preference.resolve(defaultOrder)
        AppLog.i(TAG, "Looking up '${track.title}' by '${track.artist}' (album '${track.album}', ${track.durationMs} ms, key ${track.key})")

        var lastError: Exception? = null
        var anyAnswered = false

        // A provider's searches share its time limit.
        suspend fun search(provider: LyricsProvider, tracks: List<Track>): Lyrics? {
            val name = provider.source.displayName
            val started = System.currentTimeMillis()
            return try {
                withTimeout(SOURCE_TIME_LIMIT_MS) {
                    tracks.firstNotNullOfOrNull { provider.fetch(it)?.takeIf { lyrics -> lyrics.lines.isNotEmpty() } }
                }.also {
                    anyAnswered = true
                    if (it == null) {
                        AppLog.i(TAG, "$name has no lyrics (${System.currentTimeMillis() - started} ms)")
                    } else {
                        AppLog.i(TAG, "$name found ${describe(it)} in ${System.currentTimeMillis() - started} ms")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Caught before CancellationException, which it extends: only this source gave up, not the lookup.
                // Kept as a plain exception, so if it is rethrown, callers don't take it for a cancellation.
                val error = Exception("$name gave no answer within ${SOURCE_TIME_LIMIT_MS / 1000} s", e)
                AppLog.w(TAG, error.message!!)
                lastError = error
                null
            } catch (e: CancellationException) {
                AppLog.d(TAG, "$name cancelled (track changed or screen left)")
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "$name failed after ${System.currentTimeMillis() - started} ms", e)
                lastError = e
                null
            }
        }

        // The track under its native names (うっせぇわ for "Usseewa"), or itself if they are the same; null until known.
        var native = nativeNames?.cached(track)
        // Spotify finds the song by its id, so other names find nothing new.
        fun nativeFor(provider: LyricsProvider) = native?.takeIf { it != track && provider.source != LyricsSource.SPOTIFY }

        for (provider in providers.sortedBy { order.indexOf(it.source) }) {
            cached(track, provider.source)?.let {
                nativeNames?.found(track, it)
                return it
            }
            var lyrics = search(provider, listOfNotNull(nativeFor(provider), track))
            if (lyrics == null && native == null && nativeNames != null) {
                native = nativeNames.lookUp(track)
                nativeFor(provider)?.let { lyrics = search(provider, listOf(it)) }
            }
            lyrics?.let {
                cache.put(group(track, provider.source), value = it)
                nativeNames?.found(track, it)
                return it
            }
        }
        val error = lastError
        if (!anyAnswered && error != null) {
            AppLog.e(TAG, "Every source failed for '${track.title}'")
            throw error
        }
        AppLog.i(TAG, "No lyrics found for '${track.title}' by '${track.artist}'")
        return null
    }

    /** Drops [source]'s cached lyrics for [track]; other sources' stay. */
    suspend fun clear(track: Track, source: LyricsSource) {
        AppLog.i(TAG, "Clearing cached ${source.displayName} lyrics for '${track.title}'")
        cache.removeGroup(group(track, source))
    }

    private suspend fun cached(track: Track, source: LyricsSource): Lyrics? =
        cache.get(group(track, source))?.also {
            AppLog.d(TAG, "Cache hit for '${track.title}': ${describe(it)}")
        }

    private fun group(track: Track, source: LyricsSource) = "${track.key}|${source.name}"

    private fun describe(lyrics: Lyrics) =
        "${lyrics.lines.size} ${if (lyrics.synced) "synced" else "unsynced"} lines from ${lyrics.source.displayName}"

    private companion object {
        const val TAG = "Lyrics"

        /** How long one source may take before the next is tried. */
        const val SOURCE_TIME_LIMIT_MS = 15_000L
    }
}
