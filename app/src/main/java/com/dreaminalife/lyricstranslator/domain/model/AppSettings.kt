package com.dreaminalife.lyricstranslator.domain.model

enum class TranslatorType(val displayName: String) {
    GOOGLE("Google"),
    OPENROUTER("OpenRouter"),
}

/** User settings, except the OpenRouter key, which is stored separately and encrypted. */
data class AppSettings(
    val translatorType: TranslatorType = TranslatorType.GOOGLE,
    val targetLanguage: String = "en",
    val openRouterModel: String = DEFAULT_OPENROUTER_MODEL,
    val promptTemplate: String = DEFAULT_PROMPT_TEMPLATE,
    /** Off: songs aren't sent to OpenRouter unless refreshed on the lyrics screen; cached translations still show. */
    val openRouterAutoTranslate: Boolean = true,
) {
    companion object {
        const val DEFAULT_OPENROUTER_MODEL = "deepseek/deepseek-v3.1-terminus"

        /** Same default as the desktop app. */
        const val DEFAULT_PROMPT_TEMPLATE =
            "Translate the following song lyrics to {target_language}. " +
                "Keep line breaks, return exactly the same number of lines in the same order. " +
                "Output ONLY the translations, one line per input line, no numbering, no extra text.\n\n{lyrics}"
    }
}

/**
 * The order lyrics sources are tried in. [order] is the user's order from Settings (empty for the default);
 * [pinned] is a source picked on the lyrics screen, tried before all others.
 */
data class LyricsSourcePreference(
    val order: List<LyricsSource> = emptyList(),
    val pinned: LyricsSource? = null,
) {
    /** [pinned], then [order], then the rest of [defaultOrder]; only sources in [defaultOrder], each once. */
    fun resolve(defaultOrder: List<LyricsSource>): List<LyricsSource> =
        (listOfNotNull(pinned) + order + defaultOrder).distinct().filter { it in defaultOrder }
}

data class Language(val code: String, val name: String)

val TARGET_LANGUAGES = listOf(
    Language("en", "English"),
    Language("zh-CN", "Chinese (Simplified)"),
    Language("zh-TW", "Chinese (Traditional)"),
    Language("ja", "Japanese"),
    Language("ko", "Korean"),
    Language("es", "Spanish"),
    Language("fr", "French"),
    Language("de", "German"),
    Language("it", "Italian"),
    Language("pt", "Portuguese"),
    Language("ru", "Russian"),
    Language("ar", "Arabic"),
    Language("hi", "Hindi"),
    Language("id", "Indonesian"),
    Language("th", "Thai"),
    Language("vi", "Vietnamese"),
    Language("tr", "Turkish"),
    Language("nl", "Dutch"),
    Language("pl", "Polish"),
    Language("uk", "Ukrainian"),
)
