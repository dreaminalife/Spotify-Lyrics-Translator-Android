package com.dreaminalife.lyricstranslator.data.lyrics.spotify

import com.dreaminalife.lyricstranslator.data.net.AppJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The TOTP the Spotify web player sends when asking for an access token. Ported from syrics' totp.py. */
object SpotifyTotp {
    /** Maintained by https://github.com/xyloflake/spot-secrets-go/, as in syrics. */
    const val SECRET_DICT_URL = "https://github.com/xyloflake/spot-secrets-go/blob/main/secrets/secretDict.json?raw=true"

    private const val PERIOD_SECONDS = 30
    private const val DIGITS = 6

    data class Secret(val key: ByteArray, val version: String)

    /** Uses the last (newest) version in the dict, whose values are XOR-obfuscated character codes. */
    fun parseSecretDict(json: String): Secret {
        val (version, codes) = AppJson.parseToJsonElement(json).jsonObject.entries.last()
        val transformed = codes.jsonArray.mapIndexed { i, code -> code.jsonPrimitive.int xor ((i % 33) + 9) }
        return Secret(transformed.joinToString("").toByteArray(Charsets.UTF_8), version)
    }

    /** RFC 6238 TOTP (HMAC-SHA1, 30 s period, 6 digits). */
    fun generate(key: ByteArray, timestampMs: Long): String {
        val counter = timestampMs / 1000 / PERIOD_SECONDS
        val hash = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            .doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }
}
