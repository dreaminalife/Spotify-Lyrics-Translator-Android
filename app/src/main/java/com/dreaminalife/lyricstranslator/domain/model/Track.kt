package com.dreaminalife.lyricstranslator.domain.model

/**
 * A track as reported by the media session.
 *
 * @property key stable identity used for caching: the Spotify track URI when the player exposes one,
 *   otherwise "artist|title|durationSeconds".
 * @property spotifyTrackId bare Spotify track ID, if known. Needed by the Syrics (Spotify) lyrics source.
 */
data class Track(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val spotifyTrackId: String? = null,
)
