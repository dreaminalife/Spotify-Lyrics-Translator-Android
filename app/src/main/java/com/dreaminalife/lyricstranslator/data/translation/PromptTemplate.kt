package com.dreaminalife.lyricstranslator.data.translation

object PromptTemplate {
    private val PLACEHOLDER = Regex("""\{(target_language|lyrics)\}""")

    /** Fills both placeholders in one pass, so placeholder-like text inside the lyrics is left alone. */
    fun fill(template: String, targetLanguage: String, lyrics: String): String =
        PLACEHOLDER.replace(template) { match ->
            if (match.groupValues[1] == "lyrics") lyrics else targetLanguage
        }

    fun hasLyricsPlaceholder(template: String): Boolean = "{lyrics}" in template
}
