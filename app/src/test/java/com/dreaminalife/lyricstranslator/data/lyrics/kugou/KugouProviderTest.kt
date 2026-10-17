package com.dreaminalife.lyricstranslator.data.lyrics.kugou

import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class KugouProviderTest {
    private val key = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    /** The reverse of Kugou's scrambling: zlib, XOR with the key, "krc1" header, Base64. */
    private fun encodeKrc(text: String): String {
        val deflated = java.io.ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val xored = deflated.mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()).toByte() }.toByteArray()
        return Base64.getEncoder().encodeToString("krc1".toByteArray() + xored)
    }

    private fun language(rows: List<String>): String {
        val json = """{"content":[{"type":0,"lyricContent":[["x"]]},{"type":1,"lyricContent":[${rows.joinToString { """["$it"]""" }}]}]}"""
        return "[language:${Base64.getEncoder().encodeToString(json.toByteArray())}]"
    }

    @Test
    fun `decodeKrc undoes the scrambling`() {
        val text = "[ti:Song]\n[0,100]<0,50,0>夢<50,50,0>ならば"
        assertEquals(text, KugouProvider.decodeKrc(encodeKrc(text)))
    }

    @Test
    fun `word tags are stripped and translation rows line up, notices blanked`() {
        val krc = listOf(
            "[ti:Blinding Lights]",
            language(listOf("TME享有本翻译作品的著作权", "以下歌词翻译由文曲大模型提供", "我一直心存向往")),
            "[0,1757]<0,476,0>Blinding<476,95,0> <571,190,0>Lights",
            "[1757,4830]<0,159,0>Yeah",
            "[27672,2000]<0,300,0>I've<300,100,0> <400,200,0>been<600,100,0> <700,300,0>tryna<1000,100,0> <1100,500,0>call",
        ).joinToString("\r\n")
        val lyrics = KugouProvider.parseKrc(krc)!!

        assertEquals(listOf("Blinding Lights", "Yeah", "I've been tryna call"), lyrics.lines.map { it.text })
        assertEquals(listOf(0L, 1757L, 27672L), lyrics.lines.map { it.startTimeMs })
        assertEquals(LyricsSource.KUGOU, lyrics.source)
        assertEquals(listOf("", "", "我一直心存向往"), lyrics.translation!!.lines)
    }

    @Test
    fun `translation is dropped when its rows don't match the lines`() {
        val krc = listOf(language(listOf("一", "二")), "[0,100]<0,100,0>one").joinToString("\n")
        assertNull(KugouProvider.parseKrc(krc)!!.translation)
    }

    @Test
    fun `no lyrics lines gives null`() {
        assertNull(KugouProvider.parseKrc("[ti:Song]\n[ar:Artist]"))
    }

    @Test
    fun `candidate must match title and duration`() {
        val track = Track("key", "Dynamite", "BTS", "", 199_000)
        val candidates = listOf(
            KugouCandidate("1", "a", "Dynamite (Remix)", "BTS", 199_000),
            KugouCandidate("2", "b", "Dynamite", "BTS", 204_826),
            KugouCandidate("3", "c", "Dynamite", "BTS", 198_974),
        )
        assertEquals("3", KugouProvider.pickCandidate(track, candidates)?.id)
    }
}
