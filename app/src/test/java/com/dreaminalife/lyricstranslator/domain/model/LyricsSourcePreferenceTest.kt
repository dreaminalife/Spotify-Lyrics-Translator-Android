package com.dreaminalife.lyricstranslator.domain.model

import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.KUGOU
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.LRCLIB
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.NETEASE
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.QQ_MUSIC
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.UTA_NET
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSourcePreferenceTest {
    private val default = listOf(NETEASE, QQ_MUSIC, KUGOU, LRCLIB)

    @Test
    fun `no preference gives the default order`() {
        assertEquals(default, LyricsSourcePreference().resolve(default))
    }

    @Test
    fun `a custom order is followed`() {
        val order = listOf(LRCLIB, KUGOU, NETEASE, QQ_MUSIC)
        assertEquals(order, LyricsSourcePreference(order).resolve(default))
    }

    @Test
    fun `sources missing from the custom order are added at the end in default order`() {
        assertEquals(listOf(KUGOU, NETEASE, QQ_MUSIC, LRCLIB), LyricsSourcePreference(listOf(KUGOU)).resolve(default))
    }

    @Test
    fun `duplicates and sources without a provider are dropped`() {
        val preference = LyricsSourcePreference(listOf(UTA_NET, KUGOU, KUGOU, NETEASE))
        assertEquals(listOf(KUGOU, NETEASE, QQ_MUSIC, LRCLIB), preference.resolve(default))
    }

    @Test
    fun `a pinned source goes first and the rest follow the custom order`() {
        val preference = LyricsSourcePreference(listOf(LRCLIB, KUGOU, NETEASE, QQ_MUSIC), pinned = NETEASE)
        assertEquals(listOf(NETEASE, LRCLIB, KUGOU, QQ_MUSIC), preference.resolve(default))
    }
}
