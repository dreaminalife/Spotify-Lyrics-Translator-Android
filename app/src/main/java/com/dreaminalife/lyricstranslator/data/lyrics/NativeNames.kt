package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.cache.JsonFileCache
import com.dreaminalife.lyricstranslator.data.lyrics.utanet.ExactArtistMatcher
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** A song's title and artist as written in its own language. */
@Serializable
data class NativeNames(val title: String, val artist: String)

/**
 * Spotify shows many Japanese songs under English or romanized names ("Usseewa" by Ado), while NetEase, QQ Music,
 * Kugou and Uta-Net know them only by their native ones (うっせぇわ). This finds the native names with [find] and
 * caches them per track, also when they are Spotify's own, so each track is looked up once. Failed lookups are not
 * cached, so they are retried the next time the track plays.
 *
 * The Japan store writes foreign artists in katakana (テイラー・スウィフト for Taylor Swift), a name the other
 * sources don't use, so a katakana-only artist counts only for a Japanese song: one with a kana title, or by an
 * artist whose lyrics were found in Japanese before, such as ヨルシカ (Yorushika).
 */
class NativeNameLookup(
    private val find: suspend (Track) -> NativeNames?,
    private val cache: JsonFileCache<NativeNames>,
    private val japaneseArtists: JsonFileCache<Boolean>,
) {
    /** [track] under its native names, or [track] itself if they are the same; null if it was never looked up. */
    suspend fun cached(track: Track): Track? = cache.get(track.key)?.let { renamed(track, it) }

    /** [track] under its native names, or [track] itself if they are the same or can't be found. */
    suspend fun lookUp(track: Track): Track {
        if (hasNativeScript(track.title) && hasNativeScript(track.artist)) return track
        // Songs are matched by length too: without it nothing matches, and that would be cached as "same names".
        if (track.durationMs <= 0) return track
        val names = try {
            withTimeout(TIME_LIMIT_MS) { find(track) } ?: NativeNames(track.title, track.artist)
        } catch (e: TimeoutCancellationException) {
            AppLog.w(TAG, "No answer within ${TIME_LIMIT_MS / 1000} s")
            return track
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Lookup failed for '${track.title}'", e)
            return track
        }
        cache.put(track.key, value = names)
        return renamed(track, names)
    }

    /** Notes [track]'s artist as Japanese if [lyrics], found for it, are in Japanese. */
    suspend fun found(track: Track, lyrics: Lyrics) {
        val artist = ExactArtistMatcher.normalize(track.artist)
        if (!inJapanese(lyrics) || japaneseArtists.get(artist) == true) return
        AppLog.d(TAG, "'${track.artist}' sings in Japanese")
        japaneseArtists.put(artist, value = true)
    }

    // Spotify's album is dropped: it is in Spotify's language too, and LRCLib would require it to match.
    private suspend fun renamed(track: Track, names: NativeNames): Track {
        val newArtist = !same(names.artist, track.artist) && (
            names.artist.codePoints().anyMatch { Character.UnicodeScript.of(it) in KANJI_OR_HIRAGANA } ||
                hasKana(track.title) ||
                japaneseArtists.get(ExactArtistMatcher.normalize(track.artist)) == true
            )
        return if (same(names.title, track.title) && !newArtist) track
        else track.copy(title = names.title, artist = names.artist, album = "")
    }

    private companion object {
        const val TAG = "NativeNames"
        const val TIME_LIMIT_MS = 10_000L
        const val MIN_KANA_SHARE = 0.25

        val NATIVE_SCRIPTS = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
        )

        val KANJI_OR_HIRAGANA = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA)
        val KANA = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

        fun hasNativeScript(name: String) = name.codePoints().anyMatch { Character.UnicodeScript.of(it) in NATIVE_SCRIPTS }

        fun hasKana(text: String) = text.codePoints().anyMatch { Character.UnicodeScript.of(it) in KANA }

        // A quarter of the lines: J-pop mixes in a lot of English, while Chinese and English lyrics have no kana.
        fun inJapanese(lyrics: Lyrics): Boolean {
            val lettered = lyrics.lines.map { it.text }.filter { line -> line.any { it.isLetter() } }
            return lettered.isNotEmpty() && lettered.count(::hasKana) >= lettered.size * MIN_KANA_SHARE
        }

        fun same(a: String, b: String) = ExactArtistMatcher.normalize(a) == ExactArtistMatcher.normalize(b)
    }
}
