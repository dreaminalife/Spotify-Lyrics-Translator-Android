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

    /** A client that answers the n-th request with codes[n] (the last code repeats). */
    private fun client(vararg codes: Int, calls: AtomicInteger) = OkHttpClient.Builder()
        .addInterceptor { chain ->
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
    fun doesNotRetry404() = runTest {
        val calls = AtomicInteger()
        assertNull(LrcLibProvider(client(404, calls = calls)).fetch(track))
        assertEquals(1, calls.get())
    }
}
