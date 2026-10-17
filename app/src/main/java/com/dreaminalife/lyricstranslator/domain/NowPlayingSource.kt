package com.dreaminalife.lyricstranslator.domain

import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import kotlinx.coroutines.flow.StateFlow

/** Reports what is playing. v1 uses Android media sessions; a Spotify Web API source could be added later. */
interface NowPlayingSource {
    /** The current track and playback state, or null when nothing is playing (or access isn't granted). */
    val nowPlaying: StateFlow<NowPlaying?>

    fun start()
    fun stop()
}
