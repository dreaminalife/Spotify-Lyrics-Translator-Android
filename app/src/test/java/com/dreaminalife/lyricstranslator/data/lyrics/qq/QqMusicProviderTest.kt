package com.dreaminalife.lyricstranslator.data.lyrics.qq

import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class QqMusicProviderTest {
    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun lyricJson(lyric: String, trans: String = "") =
        """{"code":0,"req_1":{"code":0,"data":{"lyric":"${base64(lyric)}","trans":"${base64(trans)}"}}}"""

    @Test(expected = IOException::class)
    fun `a refused search throws`() {
        QqMusicProvider.parseSearch("""{"code":500001,"data":{}}""")
    }

    @Test
    fun `the full title decides, so live versions are rejected`() {
        val songs = QqMusicProvider.parseSearch(
            """{"code":0,"data":{"song":{"list":[
                {"id":1,"name":"Lemon","title":"Lemon (Live)","interval":255,"singer":[{"name":"米津玄師","title":"米津玄師"}]},
                {"id":2,"name":"Lemon","title":"Lemon","interval":256,"singer":[{"name":"米津玄師","title":"米津玄師"}]}
            ]}}}""",
        )
        val picked = QqMusicProvider.pickSong(Track("key", "Lemon", "Kenshi Yonezu", "", 255_000), songs)
        assertEquals(2L, picked?.id)
    }

    @Test
    fun `a Simplified title matches the Traditional one, by the alias in the singer title`() {
        val songs = QqMusicProvider.parseSearch(
            """{"code":0,"data":{"song":{"list":[
                {"id":232577687,"title":"干杯 (Life Live)","interval":252,"singer":[{"name":"五月天","title":"五月天 (Mayday)"}]},
                {"id":1056382,"title":"干杯","interval":289,"singer":[{"name":"五月天","title":"五月天 (Mayday)"}]}
            ]}}}""",
        )
        assertEquals(1056382L, QqMusicProvider.pickSong(Track("key", "乾杯", "Mayday", "", 289_000), songs)?.id)
    }

    @Test
    fun `lyrics and translation are decoded and paired, placeholders and notices blanked`() {
        val lyrics = QqMusicProvider.parseLyrics(
            lyricJson(
                lyric = "[ti:Lemon]\n[00:00.00]Lemon - 米津玄師\n[00:01.54]夢ならば\n[00:02.88]どれほど",
                trans = "[ti:Lemon]\n[00:00.00]QQ音乐享有本翻译作品的著作权\n[00:01.54]如果只是一场梦\n[00:02.88]//",
            ),
        )!!

        assertEquals(listOf("Lemon - 米津玄師", "夢ならば", "どれほど"), lyrics.lines.map { it.text })
        assertEquals(LyricsSource.QQ_MUSIC, lyrics.source)
        assertEquals(listOf("", "如果只是一场梦", ""), lyrics.translation!!.lines)
    }

    @Test
    fun `no translation and no lyrics`() {
        assertNull(QqMusicProvider.parseLyrics(lyricJson("[00:01.00]one"))!!.translation)
        assertNull(QqMusicProvider.parseLyrics(lyricJson("")))
    }

    @Test
    fun `fetch searches with the web search, then posts the desktop identity for the chosen song's lyrics`() = runTest {
        val urls = mutableListOf<okhttp3.HttpUrl>()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                urls += chain.request().url
                val answer = if (chain.request().method == "GET") {
                    """{"code":0,"data":{"song":{"list":[{"id":42,"title":"Song","interval":200,"singer":[{"name":"Artist"}]}]}}}"""
                } else {
                    bodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                    lyricJson("[00:01.00]one")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(answer.toResponseBody())
                    .build()
            }
            .build()

        val lyrics = QqMusicProvider(client).fetch(Track("key", "Song", "Artist", "Album", 200_000, null))

        assertEquals(listOf("one"), lyrics!!.lines.map { it.text })
        assertEquals(2, urls.size)
        assertEquals("/soso/fcgi-bin/client_search_cp", urls[0].encodedPath)
        assertEquals("Song Artist", urls[0].queryParameter("w"))
        assertEquals("1", urls[0].queryParameter("new_json"))
        assertEquals(1, bodies.size)
        assertTrue(bodies[0], bodies[0].contains(""""comm":{"ct":"19""""))
        assertTrue(bodies[0], bodies[0].contains(""""songID":42"""))
    }

    @Test(expected = IOException::class)
    fun `a refused lyrics request throws`() {
        QqMusicProvider.parseLyrics("""{"code":0,"req_1":{"code":2001}}""")
    }

    @Test
    fun `missing lyric data gives null`() {
        assertNull(QqMusicProvider.parseLyrics("""{"code":0,"req_1":{"code":0,"data":{}}}"""))
        assertNull(QqMusicProvider.parseLyrics("""{"code":0,"req_1":{"code":0}}"""))
    }

    @Test
    fun `an empty result list and missing fields are handled`() {
        assertEquals(emptyList<QqSong>(), QqMusicProvider.parseSearch("""{"code":0,"data":{"song":{"list":[]}}}"""))
        assertEquals(emptyList<QqSong>(), QqMusicProvider.parseSearch("""{"code":0,"data":{}}"""))
        val songs = QqMusicProvider.parseSearch("""{"code":0,"data":{"song":{"list":[{"id":1,"title":"Song"}]}}}""")
        assertNull(QqMusicProvider.pickSong(Track("key", "Song", "Artist", "", 200_000), songs))
    }

    @Test(expected = IOException::class)
    fun `a response without a code throws`() {
        QqMusicProvider.parseSearch("""{}""")
    }

    @Test
    fun `singer title (the alias) can match the Spotify artist`() {
        val songs = listOf(
            QqSong(1, "Song", 200, listOf(QqSinger("Someone", "Someone"))),
            QqSong(2, "Song", 200, listOf(QqSinger("아이유", "IU"))),
        )
        assertEquals(2L, QqMusicProvider.pickSong(Track("key", "Song", "IU", "", 200_000), songs)?.id)
    }

    @Test
    fun `seconds-precision durations still match within 3 s`() {
        val songs = listOf(QqSong(1, "Song", 203, listOf(QqSinger("Artist"))))
        assertEquals(1L, QqMusicProvider.pickSong(Track("key", "Song", "Artist", "", 200_400), songs)?.id)
    }
}
