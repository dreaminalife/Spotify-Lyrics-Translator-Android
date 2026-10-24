package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.net.HttpException
import com.dreaminalife.lyricstranslator.domain.model.Track
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LrcLibProviderTest {
    private val track = Track("key", "Song", "Artist", "Album", 200_000, null)
    private val body = """{"id":1,"syncedLyrics":"[00:01.00]one\n[00:02.00]two"}"""

    private val albums = mutableListOf<String?>()

    /** A client that answers the n-th request with codes[n] (the last code repeats). */
    private fun client(vararg codes: Int, calls: AtomicInteger) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            albums += chain.request().url.queryParameter("album_name")
            val code = codes[minOf(calls.getAndIncrement(), codes.lastIndex)]
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body((if (code == 200) body else "").toResponseBody())
                .build()
        }
        .build()

    @Test
    fun retriesAfter503() = runTest {
        val calls = AtomicInteger()
        val lyrics = LrcLibProvider(client(503, 503, 200, calls = calls)).fetch(track)
        assertEquals(3, calls.get())
        assertEquals(listOf("one", "two"), lyrics!!.lines.map { it.text })
    }

    @Test
    fun givesUpAfterFiveAttempts() = runTest {
        val calls = AtomicInteger()
        val error = runCatching { LrcLibProvider(client(503, calls = calls)).fetch(track) }.exceptionOrNull()
        assertEquals(5, calls.get())
        assertTrue(error is HttpException)
    }

    @Test
    fun retries404OnceWithoutTheAlbum() = runTest {
        val calls = AtomicInteger()
        assertNull(LrcLibProvider(client(404, calls = calls)).fetch(track))
        assertEquals(listOf("Album", null), albums)
    }

    @Test
    fun findsTheSongWithoutTheAlbum() = runTest {
        val calls = AtomicInteger()
        val lyrics = LrcLibProvider(client(404, 200, calls = calls)).fetch(track)
        assertEquals(listOf("one", "two"), lyrics!!.lines.map { it.text })
    }

    @Test
    fun noAlbumIsSentOnce() = runTest {
        val calls = AtomicInteger()
        assertNull(LrcLibProvider(client(404, calls = calls)).fetch(track.copy(album = "")))
        assertEquals(listOf<String?>(null), albums)
    }
}
