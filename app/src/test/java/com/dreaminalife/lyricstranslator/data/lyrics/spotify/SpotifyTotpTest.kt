package com.dreaminalife.lyricstranslator.data.lyrics.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyTotpTest {
    // RFC 6238 appendix B, SHA-1 secret; the RFC's 8-digit codes truncated to 6 digits.
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun matchesRfc6238Vectors() {
        assertEquals("287082", SpotifyTotp.generate(rfcSecret, 59_000))
        assertEquals("081804", SpotifyTotp.generate(rfcSecret, 1_111_111_109_000))
    }

    @Test
    fun usesLastVersionAndUndoesXor() {
        // 8 = 1 xor 9 and 29 = 23 xor 10, so version "61" decodes to the digits "1" + "23".
        val secret = SpotifyTotp.parseSecretDict("""{"59":[0,0],"61":[8,29]}""")
        assertEquals("61", secret.version)
        assertEquals("123", String(secret.key))
    }
}
