package com.dreaminalife.lyricstranslator.data.translation

import com.dreaminalife.lyricstranslator.data.cache.JsonFileCache
import com.dreaminalife.lyricstranslator.domain.Translator
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog

/**
 * Translates the song title and lyrics in one request, caching per (track, lyrics source, translator, target language).
 * Failures are thrown and never cached.
 */
class TranslationRepository(private val cache: JsonFileCache<TranslatedLyrics>) {

    /** The cached translation, if it still matches [lyrics] line for line. */
    suspend fun cached(track: Track, lyrics: Lyrics, translator: Translator, targetLanguage: String): TranslatedLyrics? =
        cache.get(group(track, lyrics.source), cacheKey(translator, targetLanguage))
            // Lyrics and translations are trimmed separately; refetched lyrics may differ from what was translated.
            ?.let { cached ->
                if (cached.lines.size == lyrics.lines.size) {
                    AppLog.d(TAG, "Cache hit for '${track.title}' (${translator.id}, $targetLanguage)")
                    cached
                } else {
                    AppLog.w(TAG, "Ignoring cached translation: ${cached.lines.size} lines vs ${lyrics.lines.size} lyrics lines")
                    null
                }
            }

    suspend fun translate(track: Track, lyrics: Lyrics, translator: Translator, targetLanguage: String): TranslatedLyrics {
        cached(track, lyrics, translator, targetLanguage)?.let { return it }

        // A translation shipped with the lyrics (NetEase's Chinese) is used as is; the translator fills its gaps.
        val provided = lyrics.providedTranslation(targetLanguage)
        // The title goes first in the same request: one (possibly paid) call per song, and the title gets the lyrics as context.
        val missing = lyrics.lines.withIndex()
            .filter { it.value.text.isNotBlank() && provided?.get(it.index).isNullOrBlank() }
        AppLog.i(
            TAG,
            "Translating '${track.title}' with ${translator.id} to $targetLanguage: title + ${missing.size} lines" +
                (provided?.let { " (${lyrics.source.displayName} provided the rest)" } ?: ""),
        )
        val started = System.currentTimeMillis()
        val translated = translator.translate(listOf(track.title) + missing.map { it.value.text }, targetLanguage)
        AppLog.i(TAG, "Translated '${track.title}' -> '${translated.first()}' in ${System.currentTimeMillis() - started} ms")
        val lines = MutableList(lyrics.lines.size) { provided?.get(it).orEmpty() }
        missing.forEachIndexed { i, line -> lines[line.index] = translated[i + 1] }
        val name = if (provided != null) "${lyrics.source.displayName} + ${translator.displayName}" else translator.displayName

        return TranslatedLyrics(lines, translated.first(), name)
            .also { cache.put(group(track, lyrics.source), cacheKey(translator, targetLanguage), it) }
    }

    /** Drops the cached translations of [source]'s lyrics for [track]; other sources' stay. */
    suspend fun clear(track: Track, source: LyricsSource) {
        AppLog.i(TAG, "Clearing cached translations of ${source.displayName} lyrics for '${track.title}'")
        cache.removeGroup(group(track, source))
    }

    // Grouped by source: another source's lyrics can have the same line count but different lines.
    private fun group(track: Track, source: LyricsSource) = "${track.key}|${source.name}"

    private fun cacheKey(translator: Translator, targetLanguage: String) = "${translator.id}|$targetLanguage"

    private companion object {
        const val TAG = "Translation"
    }
}
