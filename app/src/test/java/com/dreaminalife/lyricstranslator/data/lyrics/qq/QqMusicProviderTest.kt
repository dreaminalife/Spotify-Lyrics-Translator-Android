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
    fun `a throttled search throws`() {
        QqMusicProvider.parseSearch("""{"code":0,"req_1":{"code":2001,"data":{}}}""")
    }

    @Test
    fun `the full title decides, so live versions are rejected`() {
        val songs = QqMusicProvider.parseSearch(
            """{"code":0,"req_1":{"code":0,"data":{"body":{"song":{"list":[
                {"id":1,"name":"Lemon","title":"Lemon (Live)","interval":255,"singer":[{"name":"米津玄師","title":"米津玄師"}]},
                {"id":2,"name":"Lemon","title":"Lemon","interval":256,"singer":[{"name":"米津玄師","title":"米津玄師"}]}
            ]}}}}}""",
        )
        val picked = QqMusicProvider.pickSong(Track("key", "Lemon", "Kenshi Yonezu", "", 255_000), songs)
        assertEquals(2L, picked?.id)
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
    fun `fetch posts the desktop identity, then loads the chosen song's lyrics`() = runTest {
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                bodies += body
                val answer = if (body.contains("DoSearchForQQMusicDesktop")) {
                    """{"code":0,"req_1":{"code":0,"data":{"body":{"song":{"list":[{"id":42,"title":"Song","interval":200,"singer":[{"name":"Artist"}]}]}}}}}"""
                } else {
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
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { it.contains(""""comm":{"ct":"19"""") })
        assertTrue(bodies[1], bodies[1].contains(""""songID":42"""))
    }
}
