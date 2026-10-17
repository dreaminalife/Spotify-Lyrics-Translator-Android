package com.dreaminalife.lyricstranslator.domain.model

/** What the floating window shows for each line. */
enum class FloatingLyricsMode {
    /** The original line, with its translation underneath. */
    BOTH,

    /** The translation alone, or the original where there is none. */
    TRANSLATION_ONLY,
}

/** What the floating window does with a line too long for its width. */
enum class LongLines(val displayName: String) {
    /** Makes the text smaller until the line fits, then cuts it with "…"; every row keeps the same height. */
    SHRINK("Shrink"),

    /** Wraps the line onto as many lines as it takes; the window grows and shrinks with it. */
    WRAP("Wrap"),
}

/** How the floating window looks. */
data class FloatingLyricsSettings(
    val mode: FloatingLyricsMode = FloatingLyricsMode.BOTH,
    /** One of [LINE_COUNTS]. */
    val lineCount: Int = 1,
    val longLines: LongLines = LongLines.SHRINK,
    /** Multiplies every text size; from [MIN_TEXT_SCALE] to [MAX_TEXT_SCALE]. */
    val textScale: Float = 1f,
) {
    companion object {
        /** Odd, so the line playing now can sit in the middle. */
        val LINE_COUNTS = listOf(1, 3, 5)
        const val MIN_TEXT_SCALE = 0.6f
        const val MAX_TEXT_SCALE = 2f
    }
}

/** Where the floating window was moved and resized to, in pixels from the top-start of the screen. */
data class FloatingWindowPlacement(val x: Int, val y: Int, val width: Int)
