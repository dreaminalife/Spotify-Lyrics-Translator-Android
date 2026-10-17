package com.dreaminalife.lyricstranslator.data.lyrics.spotify

import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyricsProviderTest {
    private val track = Track("key", "Song", "Artist", "Album", 200_000, "trackid")
    private val synced = """{"lyrics":{"syncType":"LINE_SYNCED","language":"ja","lines":[
        {"startTimeMs":"1000","words":"one"},{"startTimeMs":"2500","words":"two"}]}}"""
    private val validToken = """{"accessToken":"tok","accessTokenExpirationTimestampMs":${Long.MAX_VALUE},"isAnonymous":false}"""

    /** Fakes every endpoint; the lyrics endpoint answers the n-th request with lyricsCodes[n] (the last repeats). */
    private class FakeSpotify(
        val tokenBody: String,
        val lyricsBody: String,
        vararg val lyricsCodes: Int,
    ) {
        val requests = mutableListOf<Request>()
        fun count(pathPart: String) = requests.count { pathPart in it.url.toString() }

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val url = request.url.toString()
                val (code, body) = when {
                    "secretDict" in url -> 200 to """{"1":[8,29]}"""
                    "server-time" in url -> 200 to """{"serverTime":1700000000}"""
                    "/api/token" in url -> 200 to tokenBody
                    else -> {
                        val code = lyricsCodes[minOf(count("color-lyrics") - 1, lyricsCodes.lastIndex)]
                        code to (if (code == 200) lyricsBody else "")
                    }
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()
    }

    private fun provider(fake: FakeSpotify, cookie: String? = "cookie") = SyricsProvider(fake.client, spDc = { cookie })

    @Test
    fun parsesSyncedLyrics() = runTest {
        val fake = FakeSpotify(validToken, synced, 200)
        val lyrics = provider(fake).fetch(track)!!
        assertTrue(lyrics.synced)
        assertEquals(LyricsSource.SPOTIFY, lyrics.source)
        assertEquals("ja", lyrics.language)
        assertEquals(listOf(1000L, 2500L), lyrics.lines.map { it.startTimeMs })
        assertEquals(listOf("one", "two"), lyrics.lines.map { it.text })
        assertEquals("sp_dc=cookie", fake.requests.single { "/api/token" in it.url.toString() }.header("Cookie"))
        assertEquals("Bearer tok", fake.requests.single { "color-lyrics" in it.url.toString() }.header("Authorization"))
    }

    @Test
    fun stripsWhitespaceFromPastedCookie() = runTest {
        val fake = FakeSpotify(validToken, synced, 200)
        provider(fake, cookie = " coo\nkie\n").fetch(track)
        assertEquals("sp_dc=cookie", fake.requests.single { "/api/token" in it.url.toString() }.header("Cookie"))
    }

    @Test
    fun unsyncedLyricsAreNotMarkedSynced() {
        val lyrics = SyricsProvider.parseResponse(
            """{"lyrics":{"syncType":"UNSYNCED","lines":[{"startTimeMs":"0","words":"one"}]}}""",
        )!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("one"), lyrics.lines.map { it.text })
    }

    @Test
    fun returnsNullOn404() = runTest {
        assertNull(provider(FakeSpotify(validToken, synced, 404)).fetch(track))
    }

    @Test
    fun skipsWithoutTrackIdOrCookie() = runTest {
        val fake = FakeSpotify(validToken, synced, 200)
        assertNull(provider(fake).fetch(track.copy(spotifyTrackId = null)))
        assertNull(provider(fake, cookie = null).fetch(track))
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun reusesTokenAcrossFetches() = runTest {
        val fake = FakeSpotify(validToken, synced, 200)
        val provider = provider(fake)
        provider.fetch(track)
        provider.fetch(track)
        assertEquals(1, fake.count("/api/token"))
        assertEquals(1, fake.count("secretDict"))
    }

    @Test
    fun logsInAgainAfter401() = runTest {
        val fake = FakeSpotify(validToken, synced, 401, 200)
        val lyrics = provider(fake).fetch(track)
        assertEquals(2, fake.count("/api/token"))
        assertEquals(2, fake.count("color-lyrics"))
        assertEquals(2, lyrics!!.lines.size)
    }

    @Test
    fun anonymousTokenThrows() = runTest {
        val fake = FakeSpotify("""{"accessToken":"anon","isAnonymous":true}""", synced, 200)
        val error = runCatching { provider(fake).fetch(track) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, fake.count("color-lyrics"))
    }
}
