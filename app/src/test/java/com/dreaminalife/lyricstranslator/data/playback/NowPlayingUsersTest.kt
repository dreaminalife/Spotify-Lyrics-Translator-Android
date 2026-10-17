package com.dreaminalife.lyricstranslator.data.playback

import com.dreaminalife.lyricstranslator.domain.NowPlayingSource
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingUsersTest {
    private class FakeSource : NowPlayingSource {
        var starts = 0
        var stops = 0
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
    }

    private val source = FakeSource()
    private val users = NowPlayingUsers(source)

    @Test
    fun `stops only when the last user releases`() {
        users.acquire()
        users.acquire()
        users.release()
        assertEquals(0, source.stops)
        users.release()
        assertEquals(1, source.stops)
    }

    @Test
    fun `an extra release does nothing`() {
        users.release()
        users.acquire()
        users.release()
        users.release()
        assertEquals(1, source.stops)
    }

    @Test
    fun `retry starts only while in use`() {
        users.retry()
        assertEquals(0, source.starts)
        users.acquire()
        users.retry()
        assertEquals(2, source.starts)
        users.release()
        users.retry()
        assertEquals(2, source.starts)
    }
}
