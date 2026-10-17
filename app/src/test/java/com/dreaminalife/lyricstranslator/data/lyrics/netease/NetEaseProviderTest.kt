package com.dreaminalife.lyricstranslator.data.lyrics.netease

import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
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

class NetEaseProviderTest {
    private fun song(id: Long, name: String, dt: Long, vararg artists: NetEaseArtist) =
        NetEaseSong(id, name, dt, artists.toList())

    @Test
    fun `translation is paired by timestamp across precisions and gaps stay blank`() {
        val json = """{"code":200,
            "lrc":{"lyric":"[00:00.000] 作词 : 米津玄師\n[00:01.000]one\n[00:02.000]two\n[00:03.000]three"},
            "tlyric":{"lyric":"[by:someone]\n[00:01.00]一\n[00:03.000]三"}}"""
        val lyrics = NetEaseProvider.parseLyrics(json)!!

        assertEquals(listOf("作词 : 米津玄師", "one", "two", "three"), lyrics.lines.map { it.text })
        assertTrue(lyrics.synced)
        assertEquals(LyricsSource.NETEASE, lyrics.source)
        assertEquals("zh-CN", lyrics.translation!!.language)
        assertEquals(listOf("", "一", "", "三"), lyrics.translation!!.lines)
    }

    @Test
    fun `no translation when tlyric is missing or empty`() {
        val lrc = """"lrc":{"lyric":"[00:01.00]one"}"""
        assertNull(NetEaseProvider.parseLyrics("""{$lrc}""")!!.translation)
        assertNull(NetEaseProvider.parseLyrics("""{$lrc,"tlyric":{"lyric":""}}""")!!.translation)
    }

    @Test
    fun `nolyric, uncollected and untimed lyrics give null`() {
        assertNull(NetEaseProvider.parseLyrics("""{"nolyric":true,"lrc":{"lyric":"[00:01.00]x"}}"""))
        assertNull(NetEaseProvider.parseLyrics("""{"uncollected":true}"""))
        assertNull(NetEaseProvider.parseLyrics("""{"lrc":{"lyric":"plain text"}}"""))
    }

    @Test
    fun `live, cover and remix versions and wrong durations are rejected`() {
        val track = Track("key", "Dynamite", "BTS", "", 199_000)
        val songs = listOf(
            song(1, "Dynamite (Live)", 199_000, NetEaseArtist("BTS (防弹少年团)")),
            song(2, "Dynamite (Cover）", 196_115, NetEaseArtist("Ailee")),
            song(3, "Dynamite", 210_000, NetEaseArtist("BTS (防弹少年团)")),
        )
        assertNull(NetEaseProvider.pickSong(track, songs))
    }

    @Test
    fun `a matching artist wins over search order`() {
        val track = Track("key", "Hype Boy", "NewJeans", "", 179_000)
        val songs = listOf(
            song(1, "HYPE BOY", 177_934, NetEaseArtist("Cazutub")),
            song(2, "Hype Boy", 179_500, NetEaseArtist("뉴진스", listOf("NewJeans"))),
        )
        assertEquals(2L, NetEaseProvider.pickSong(track, songs)?.id)
    }

    @Test
    fun `an artist written in another script is accepted in search order`() {
        val track = Track("key", "Lemon", "Kenshi Yonezu", "", 255_000)
        val songs = listOf(
            song(1, "Lemon", 256_000, NetEaseArtist("米津玄師", listOf("ハチ"))),
            song(2, "Lemon", 255_883, NetEaseArtist("米津玄師", listOf("ハチ"))),
        )
        assertEquals(1L, NetEaseProvider.pickSong(track, songs)?.id)
    }

    @Test
    fun `a different artist in the same script is rejected`() {
        val home = Track("key", "Home", "Artist X", "", 201_000)
        assertNull(NetEaseProvider.pickSong(home, listOf(song(1, "Home", 201_000, NetEaseArtist("Artist Y")))))

        val lemon = Track("key", "Lemon", "米津玄師", "", 255_000)
        assertNull(NetEaseProvider.pickSong(lemon, listOf(song(1, "Lemon", 255_000, NetEaseArtist("秋绘")))))

        // A name mixing scripts counts as Latin, so it is not taken for a transliteration of "Kenshi Yonezu".
        val mixed = Track("key", "Lemon", "Kenshi Yonezu", "", 255_000)
        assertNull(NetEaseProvider.pickSong(mixed, listOf(song(1, "Lemon", 255_000, NetEaseArtist("安亚尔Anyar")))))
    }

    @Test
    fun `fetch searches then loads the chosen song's lyrics`() = runTest {
        val urls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                urls += url.toString()
                val body = if (url.encodedPath.endsWith("/cloudsearch/pc")) {
                    """{"code":200,"result":{"songs":[{"id":42,"name":"Song","dt":200000,"ar":[{"name":"Artist","alia":null}]}]}}"""
                } else {
                    """{"code":200,"lrc":{"lyric":"[00:01.00]one"}}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

        val lyrics = NetEaseProvider(client).fetch(Track("key", "Song", "Artist", "Album", 200_000, null))

        assertEquals(listOf("one"), lyrics!!.lines.map { it.text })
        assertEquals(2, urls.size)
        assertTrue(urls[1], urls[1].contains("id=42"))
    }

    @Test
    fun `a miss is searched again with the Simplified title`() = runTest {
        val queries = mutableListOf<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                val body = if (url.encodedPath.endsWith("/cloudsearch/pc")) {
                    queries += url.queryParameter("s")
                    if (url.queryParameter("s") == "干杯 Mayday") {
                        """{"code":200,"result":{"songs":[{"id":22197007,"name":"干杯","dt":289050,"ar":[{"name":"五月天","alia":["Mayday"]}]}]}}"""
                    } else {
                        """{"code":200,"result":{"songs":[{"id":3052219,"name":"Pilot","dt":211160,"ar":[{"name":"Mayday"}]}]}}"""
                    }
                } else {
                    """{"code":200,"lrc":{"lyric":"[00:01.00]one"}}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

        val lyrics = NetEaseProvider(client).fetch(Track("key", "乾杯", "Mayday", "Album", 289_000, null))

        assertEquals(listOf("one"), lyrics!!.lines.map { it.text })
        assertEquals(listOf("乾杯 Mayday", "干杯 Mayday"), queries)
    }

    @Test
    fun `a miss is not searched again when the title has nothing to simplify`() = runTest {
        var searches = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                searches++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body("""{"code":200,"result":{"songs":[]}}""".toResponseBody())
                    .build()
            }
            .build()

        assertNull(NetEaseProvider(client).fetch(Track("key", "Lemon", "Kenshi Yonezu", "Album", 255_000, null)))
        assertEquals(1, searches)
    }

    @Test(expected = java.io.IOException::class)
    fun `a refused search throws`() {
        NetEaseProvider.parseSearch("""{"code":-462,"message":"blocked"}""")
    }
}
