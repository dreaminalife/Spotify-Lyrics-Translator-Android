package com.dreaminalife.lyricstranslator.data.lyrics

/**
 * Folds Traditional Chinese characters to Simplified. Spotify often has the Traditional title (乾杯) while NetEase,
 * QQ Music and Kugou list the Simplified one (干杯). Character by character, so it is for matching, not for display.
 */
object ChineseScript {
    private val simplified: Map<Char, Char> by lazy {
        ChineseScript::class.java.getResourceAsStream("/traditional_to_simplified.txt")!!.bufferedReader().useLines { lines ->
            lines.filter { it.length == 2 && !it.startsWith("#") }.associate { it[0] to it[1] }
        }
    }

    fun toSimplified(text: String): String = String(CharArray(text.length) { simplified[text[it]] ?: text[it] })
}
