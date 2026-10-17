package com.dreaminalife.lyricstranslator.domain

interface Translator {
    /** Stable identity used in cache keys, e.g. "google" or "openrouter:deepseek/deepseek-v3.1-terminus". */
    val id: String

    /** Short label shown in the UI. */
    val displayName: String

    /**
     * Translates each line. Returns exactly one result per input line, in order, or throws.
     * Callers pass only non-blank lines.
     */
    suspend fun translate(lines: List<String>, targetLanguage: String): List<String>
}

class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)
