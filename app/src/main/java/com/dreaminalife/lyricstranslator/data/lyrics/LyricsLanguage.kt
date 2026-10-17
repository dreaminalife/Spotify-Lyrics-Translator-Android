package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.Lyrics

/**
 * Tells whether lyrics are already in the target language, so translating them would be a wasted request.
 * [identify] names the language of text in Latin and other scripts shared by many languages (ML Kit in the app).
 */
class LyricsLanguage(private val identify: suspend (String) -> String?) {

    /**
     * True only when the lyrics are clearly in [targetLanguage]. zh-CN and zh-TW both count as Chinese.
     * Mixed-language songs are translated.
     */
    suspend fun isIn(lyrics: Lyrics, targetLanguage: String): Boolean = of(lyrics) == base(targetLanguage)

    /**
     * Base language code of [lyrics] ("zh", "en", ...), or null if it can't be told. When some lines are in a script
     * that names a language, nearly every line must be; a song mixing, say, Chinese and English is left to the
     * translator. When none are, the source's tag (Spotify's) if any, else [identify].
     */
    suspend fun of(lyrics: Lyrics): String? {
        // NetEase, QQ Music and Kugou add Chinese credit lines (作词 : Paul Simon) even to English songs.
        val lettered = lyrics.lines.map { it.text }
            .filter { line -> line.any { it.isLetter() } && !CREDIT.containsMatchIn(line) }
        val counts = lettered.mapNotNull(::lineLanguage).groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value }?.key
        if (best == null) {
            lyrics.language?.let { return base(it) }
            if (lettered.isEmpty()) return null
            return identify(lettered.joinToString("\n"))?.let(::base)
        }
        // Kanji-only lines are normal in Japanese lyrics.
        val count = counts.getValue(best) + if (best == "ja") counts["zh"] ?: 0 else 0
        return best.takeIf { count >= lettered.size * MIN_SHARE }
    }

    /** Language of a line by its script; null for Latin and other scripts shared by many languages. */
    private fun lineLanguage(line: String): String? {
        val scripts = line.codePoints().toArray().map(Character.UnicodeScript::of).toSet()
        return when {
            Character.UnicodeScript.HIRAGANA in scripts || Character.UnicodeScript.KATAKANA in scripts -> "ja"
            Character.UnicodeScript.HANGUL in scripts -> "ko"
            Character.UnicodeScript.HAN in scripts -> "zh"
            Character.UnicodeScript.THAI in scripts -> "th"
            Character.UnicodeScript.ARABIC in scripts -> "ar"
            Character.UnicodeScript.DEVANAGARI in scripts -> "hi"
            else -> null
        }
    }

    private fun base(code: String) = code.substringBefore('-').lowercase()

    private companion object {
        const val MIN_SHARE = 0.9

        /** A short Chinese label and a colon: 作词 : ..., 词：... */
        val CREDIT = Regex("""^\s*\p{IsHan}{1,6}\s*[:：]""")
    }
}
