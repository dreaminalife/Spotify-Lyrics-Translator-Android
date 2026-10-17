package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.ProvidedTranslation

/** The Chinese (Simplified) translations NetEase, QQ Music and Kugou ship with their lyrics. */
object ProvidedTranslations {
    /** One entry per lyrics line. Placeholder and notice lines are blanked; null if nothing is left. */
    fun of(lines: List<String>): ProvidedTranslation? {
        val cleaned = lines.map { if (isNotice(it.trim())) "" else it }
        return ProvidedTranslation("zh-CN", cleaned).takeIf { cleaned.any { it.isNotBlank() } }
    }

    /** Pairs an LRC translation with [lines] by timestamp, which may differ in precision ([00:19.43] vs [00:19.430]). */
    fun pairByTimestamp(lines: List<LyricsLine>, translationLrc: String): ProvidedTranslation? {
        val translated = LrcParser.parse(translationLrc)
            .groupBy({ it.startTimeMs }, { it.text })
            .mapValues { ArrayDeque(it.value) }
        return of(lines.map { translated[it.startTimeMs]?.removeFirstOrNull().orEmpty() })
    }

    // "//" marks an untranslated QQ Music line; the others are copyright and "translated by an AI model" notices.
    private fun isNotice(text: String) =
        text == "//" || text.contains("享有本翻译作品的著作权") || text.startsWith("以下歌词翻译由")
}
