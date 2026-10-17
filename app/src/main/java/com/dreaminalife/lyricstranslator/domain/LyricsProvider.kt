package com.dreaminalife.lyricstranslator.domain

import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track

/**
 * A lyrics source (Syrics, LRCLib, Uta-Net...).
 *
 * To add a source: implement this, add a [LyricsSource] entry, and register it in AppContainer's provider list.
 */
interface LyricsProvider {
    val source: LyricsSource

    /** Returns lyrics, or null if this source has none for [track]. Throws on network/parse errors. */
    suspend fun fetch(track: Track): Lyrics?
}
