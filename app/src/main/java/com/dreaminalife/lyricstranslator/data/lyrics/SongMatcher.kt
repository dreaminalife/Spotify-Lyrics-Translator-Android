package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.lyrics.utanet.ExactArtistMatcher
import com.dreaminalife.lyricstranslator.domain.model.Track
import kotlin.math.abs

/** Picks the search result that is the playing track, for sources whose search is fuzzy (NetEase, QQ Music, Kugou). */
object SongMatcher {
    private const val DURATION_TOLERANCE_MS = 3000

    /**
     * Searches also return live versions, remixes and covers, some within seconds of the original's length,
     * so both the title and the duration must match. Search order breaks ties after preferring a matching artist;
     * artists are often listed under their native name (米津玄師 for "Kenshi Yonezu"), so that is not required.
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
        val wantedArtists = track.artist.split(',').map(ExactArtistMatcher::normalize).filter { it.isNotEmpty() }
        return candidates.firstOrNull { song ->
            artists(song)
                .map(ExactArtistMatcher::normalize)
                .any { name -> name.isNotEmpty() && wantedArtists.any { name.contains(it) || it.contains(name) } }
        } ?: candidates.firstOrNull()
    }

    private fun normalizeTitle(title: String) = ExactArtistMatcher.normalize(title.replace('（', '(').replace('）', ')'))
}
