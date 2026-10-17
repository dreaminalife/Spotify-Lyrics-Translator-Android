package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.LyricsLine

/** Parses LRC text ("[mm:ss.xx]words") into lines sorted by start time. */
object LrcParser {
    private val TIME_TAG = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<\d+:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): List<LyricsLine> {
        var offsetMs = 0L
        val result = mutableListOf<LyricsLine>()
        for (raw in lrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            OFFSET_TAG.find(line)?.let {
                offsetMs = it.groupValues[1].toLong()
                continue
            }
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue // metadata such as [ar:...] or untimed text
            val text = line.replace(TIME_TAG, "").replace(WORD_TAG, "").trim()
            tags.forEach { result += LyricsLine(toMillis(it), text) }
        }
        // A positive [offset:] makes lyrics appear sooner.
        return result
            .map { it.copy(startTimeMs = (it.startTimeMs - offsetMs).coerceAtLeast(0)) }
            .sortedBy { it.startTimeMs }
    }

    private fun toMillis(match: MatchResult): Long {
        val (minutes, seconds, fraction) = match.destructured
        // "5" = 500 ms, "45" = 450 ms, "456" = 456 ms
        val fractionMs = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').toLong()
        return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + fractionMs
    }
}
