package com.dreaminalife.lyricstranslator.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class LyricsSource(val displayName: String) {
    SPOTIFY("Spotify"),
    LRCLIB("LRCLib"),
    UTA_NET("Uta-Net"),
    NETEASE("NetEase"),
    QQ_MUSIC("QQ Music"),
    KUGOU("Kugou"),
}

/** One lyric line. [startTimeMs] is meaningless (0) when the lyrics are not synced. */
@Serializable
data class LyricsLine(val startTimeMs: Long, val text: String)

/** A translation shipped by the lyrics source: one entry per lyrics line, "" where it has none. */
@Serializable
data class ProvidedTranslation(val language: String, val lines: List<String>)

@Serializable
data class Lyrics(
    val lines: List<LyricsLine>,
    val synced: Boolean,
    val source: LyricsSource,
    val language: String? = null,
    val translation: ProvidedTranslation? = null,
) {
    /** The source's own translation lines, if it shipped one in [language] that still lines up with [lines]. */
    fun providedTranslation(language: String): List<String>? =
        translation?.takeIf { it.language == language && it.lines.size == lines.size }?.lines

    /** Index of the line playing at [positionMs], or -1 before the first line or when not synced. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        // Equivalent to bisect_right(startTimes, position) - 1; lines are sorted by start time.
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startTimeMs <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }
}
