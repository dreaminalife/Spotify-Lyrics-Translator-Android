package com.dreaminalife.lyricstranslator.domain.model

/**
 * Playback position as last reported by the player.
 *
 * @property updatedAtElapsedMs `SystemClock.elapsedRealtime()` when [positionMs] was measured.
 */
data class PlaybackSnapshot(
    val positionMs: Long,
    val updatedAtElapsedMs: Long,
    val speed: Float,
    val isPlaying: Boolean,
)

data class NowPlaying(val track: Track, val playback: PlaybackSnapshot) {
    /** Current position, extrapolated from the last report while playing and capped at the track length. */
    fun positionAt(nowElapsedMs: Long): Long {
        val position = if (playback.isPlaying) {
            playback.positionMs + ((nowElapsedMs - playback.updatedAtElapsedMs) * playback.speed).toLong()
        } else {
            playback.positionMs
        }
        return if (track.durationMs > 0) position.coerceIn(0, track.durationMs) else position.coerceAtLeast(0)
    }
}
