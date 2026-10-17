package com.dreaminalife.lyricstranslator.data.playback

import com.dreaminalife.lyricstranslator.domain.NowPlayingSource

/**
 * Keeps [source] listening while anything uses it (the lyrics screen, the floating window),
 * so one of them going away doesn't stop it for the other.
 */
class NowPlayingUsers(private val source: NowPlayingSource) {
    private var count = 0

    fun acquire() {
        count++
        source.start()
    }

    fun release() {
        if (count == 0) return
        if (--count == 0) source.stop()
    }

    /** Starts listening if it couldn't before (notification access was granted since); does nothing while unused. */
    fun retry() {
        if (count > 0) source.start()
    }
}
