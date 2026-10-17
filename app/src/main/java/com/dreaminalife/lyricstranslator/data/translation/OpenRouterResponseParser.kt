package com.dreaminalife.lyricstranslator.data.translation

object OpenRouterResponseParser {
    private val BULLET = Regex("""^[-*•]\s+""")
    private val NUMBERING = Regex("""^\d+[.)]\s+""")

    /** Splits the model's reply into lines, dropping blank lines, code fences, bullets and "12." / "3)" numbering. */
    fun parse(content: String): List<String> =
        content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .map { it.replace(BULLET, "").replace(NUMBERING, "").trim() }
}
