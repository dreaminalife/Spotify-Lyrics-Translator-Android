package com.dreaminalife.lyricstranslator.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsIndexTest {
    private val lyrics = Lyrics(
        lines = listOf(LyricsLine(1_000, "a"), LyricsLine(2_000, "b"), LyricsLine(3_000, "c")),
        synced = true,
        source = LyricsSource.LRCLIB,
    )

    @Test
    fun `before the first line there is no current line`() {
        assertEquals(-1, lyrics.indexAt(0))
        assertEquals(-1, lyrics.indexAt(999))
    }

    @Test
    fun `a line becomes current exactly at its start time`() {
        assertEquals(0, lyrics.indexAt(1_000))
        assertEquals(0, lyrics.indexAt(1_999))
        assertEquals(1, lyrics.indexAt(2_000))
    }

    @Test
    fun `after the last line it stays current`() {
        assertEquals(2, lyrics.indexAt(3_000))
        assertEquals(2, lyrics.indexAt(999_999))
    }

    @Test
    fun `unsynced lyrics never have a current line`() {
        assertEquals(-1, lyrics.copy(synced = false).indexAt(2_000))
    }

    @Test
    fun `position is extrapolated while playing and capped at the duration`() {
        val track = Track("k", "t", "a", "al", durationMs = 10_000)
        val playing = NowPlaying(track, PlaybackSnapshot(positionMs = 1_000, updatedAtElapsedMs = 5_000, speed = 1f, isPlaying = true))
        assertEquals(3_000, playing.positionAt(7_000))
        assertEquals(10_000, playing.positionAt(60_000))

        val paused = playing.copy(playback = playing.playback.copy(isPlaying = false))
        assertEquals(1_000, paused.positionAt(7_000))
    }
}
