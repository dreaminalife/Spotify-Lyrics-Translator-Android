package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.lyrics.utanet.ExactArtistMatcher
import com.dreaminalife.lyricstranslator.domain.model.Track
import java.util.Collections
import kotlin.math.abs

/** Picks the search result that is the playing track, for sources whose search is fuzzy (NetEase, QQ Music, Kugou). */
object SongMatcher {
    private const val DURATION_TOLERANCE_MS = 3000
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Searches also return live versions, remixes and covers, some within seconds of the original's length,
     * so both the title and the duration must match. A matching artist is preferred. Artists are often listed under
     * their native name (米津玄師 for "Kenshi Yonezu"), so a non-matching artist is still accepted, in search order,
     * if it is written in a different script; a different name in the same script is another artist's song.
     */
    fun <T> pick(
        track: Track,
        songs: List<T>,
        title: (T) -> String,
        durationMs: (T) -> Long?,
        artists: (T) -> List<String>,
    ): T? {
        val wantedTitle = normalizeTitle(track.title)
        val candidates = songs.filter {
            normalizeTitle(title(it)) == wantedTitle && abs((durationMs(it) ?: 0) - track.durationMs) <= DURATION_TOLERANCE_MS
        }
        val wantedArtists = track.artist.split(',').map(::words).filter { it.isNotEmpty() }
        return candidates.firstOrNull { song ->
            artists(song)
                .map(::words)
                .any { name -> name.isNotEmpty() && wantedArtists.any { containsWords(name, it) || containsWords(it, name) } }
        } ?: candidates.firstOrNull { song -> artists(song).any { writtenDifferently(it, track.artist) } }
    }

    // Whole words, so "BTS" matches "BTS (防弹少年团)" but "IU" does not match "Julius".
    private fun words(name: String) = ExactArtistMatcher.normalize(name).split(NON_WORD).filter { it.isNotEmpty() }

    private fun containsWords(name: List<String>, part: List<String>) = Collections.indexOfSubList(name, part) >= 0

    private fun hasLatin(name: String) = name.any { it in 'a'..'z' || it in 'A'..'Z' }

    private fun writtenDifferently(a: String, b: String) = a.isNotBlank() && hasLatin(a) != hasLatin(b)

    // Traditional and Simplified spellings are the same title (乾杯 and 干杯).
    private fun normalizeTitle(title: String) =
        ExactArtistMatcher.normalize(ChineseScript.toSimplified(title).replace('（', '(').replace('）', ')'))
}
