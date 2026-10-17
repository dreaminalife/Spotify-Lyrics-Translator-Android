package com.dreaminalife.lyricstranslator.data.translation

import kotlinx.serialization.Serializable

/**
 * A translated song: [lines] has one entry per lyrics line (blank lines stay blank).
 * [translatorName] is shown as the translation source, e.g. "Google" or "deepseek-v3.1-terminus".
 */
@Serializable
data class TranslatedLyrics(
    val lines: List<String>,
    val translatedTitle: String?,
    val translatorName: String,
)
