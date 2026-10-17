package com.dreaminalife.lyricstranslator.data.translation

import com.dreaminalife.lyricstranslator.data.cache.JsonFileCache
import com.dreaminalife.lyricstranslator.domain.Translator
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.ProvidedTranslation
import com.dreaminalife.lyricstranslator.domain.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeTranslator : Translator {
        val requests = mutableListOf<List<String>>()
        override val id = "fake"
        override val displayName = "Fake"
        override suspend fun translate(lines: List<String>, targetLanguage: String): List<String> {
            requests += lines
            return lines.map { "<$it>" }
        }
    }

    private val track = Track("spotify:track:1", "Title", "Artist", "", 1000)
    private val lyrics = Lyrics(
        listOf(LyricsLine(0, "a"), LyricsLine(1, ""), LyricsLine(2, "b")),
        synced = true,
        source = LyricsSource.LRCLIB,
    )

    private fun repository() = TranslationRepository(JsonFileCache(folder.root, TranslatedLyrics.serializer()))

    @Test
    fun `title and non-blank lines go in one request and blank lines stay in place`() = runTest {
        val translator = FakeTranslator()
        val result = repository().translate(track, lyrics, translator, "en")

        assertEquals(listOf(listOf("Title", "a", "b")), translator.requests)
        assertEquals("<Title>", result.translatedTitle)
        assertEquals(listOf("<a>", "", "<b>"), result.lines)
    }

    @Test
    fun `a provided translation in the target language is used and only its gaps are sent`() = runTest {
        val translator = FakeTranslator()
        val withTranslation = lyrics.copy(
            lines = lyrics.lines + LyricsLine(3, "c"),
            source = LyricsSource.NETEASE,
            translation = ProvidedTranslation("zh-CN", listOf("甲", "", "", "丙")),
        )
        val result = repository().translate(track, withTranslation, translator, "zh-CN")

        assertEquals(listOf(listOf("Title", "b")), translator.requests)
        assertEquals("<Title>", result.translatedTitle)
        assertEquals(listOf("甲", "", "<b>", "丙"), result.lines)
        assertEquals("NetEase + Fake", result.translatorName)
    }

    @Test
    fun `a provided translation in another language is ignored`() = runTest {
        val translator = FakeTranslator()
        val withTranslation = lyrics.copy(translation = ProvidedTranslation("zh-CN", listOf("甲", "", "乙")))
        val result = repository().translate(track, withTranslation, translator, "en")

        assertEquals(listOf(listOf("Title", "a", "b")), translator.requests)
        assertEquals(listOf("<a>", "", "<b>"), result.lines)
        assertEquals("Fake", result.translatorName)
    }

    @Test
    fun `second call is served from cache, but not when the lyrics changed`() = runTest {
        val repository = repository()
        val translator = FakeTranslator()
        repository.translate(track, lyrics, translator, "en")
        repository.translate(track, lyrics, translator, "en")
        assertEquals(1, translator.requests.size)

        val changed = lyrics.copy(lines = lyrics.lines + LyricsLine(3, "c"))
        assertNull(repository.cached(track, changed, translator, "en"))
        repository.translate(track, changed, translator, "en")
        assertEquals(2, translator.requests.size)
    }

    @Test
    fun `a translation of one source's lyrics is not used for another source's lyrics with as many lines`() = runTest {
        val repository = repository()
        val translator = FakeTranslator()
        repository.translate(track, lyrics.copy(source = LyricsSource.NETEASE), translator, "en")

        assertNull(repository.cached(track, lyrics.copy(source = LyricsSource.QQ_MUSIC), translator, "en"))
    }

    @Test
    fun `clearing a source keeps the translations of other sources' lyrics`() = runTest {
        val repository = repository()
        val translator = FakeTranslator()
        val netEase = lyrics.copy(source = LyricsSource.NETEASE)
        val qq = lyrics.copy(source = LyricsSource.QQ_MUSIC)
        repository.translate(track, netEase, translator, "en")
        repository.translate(track, qq, translator, "en")
        repository.clear(track, LyricsSource.NETEASE)

        assertNull(repository.cached(track, netEase, translator, "en"))
        assertEquals(listOf("<a>", "", "<b>"), repository.cached(track, qq, translator, "en")?.lines)
    }

    @Test
    fun `when the provided translation covers every line only the title is sent`() = runTest {
        val translator = FakeTranslator()
        val withTranslation = lyrics.copy(translation = ProvidedTranslation("zh-CN", listOf("甲", "", "乙")))
        val result = repository().translate(track, withTranslation, translator, "zh-CN")

        assertEquals(listOf(listOf("Title")), translator.requests)
        assertEquals(listOf("甲", "", "乙"), result.lines)
        assertEquals("LRCLib + Fake", result.translatorName)
    }

    @Test
    fun `a whitespace-only provided line counts as missing`() = runTest {
        val translator = FakeTranslator()
        val withTranslation = lyrics.copy(translation = ProvidedTranslation("zh-CN", listOf("甲", "", "  ")))
        val result = repository().translate(track, withTranslation, translator, "zh-CN")

        assertEquals(listOf(listOf("Title", "b")), translator.requests)
        assertEquals(listOf("甲", "", "<b>"), result.lines)
    }

    @Test
    fun `a provided translation that no longer lines up is ignored`() = runTest {
        val translator = FakeTranslator()
        val withTranslation = lyrics.copy(translation = ProvidedTranslation("zh-CN", listOf("甲", "乙")))
        val result = repository().translate(track, withTranslation, translator, "zh-CN")

        assertEquals(listOf(listOf("Title", "a", "b")), translator.requests)
        assertEquals("Fake", result.translatorName)
    }

    @Test
    fun `a similar but different target language does not use the provided translation`() = runTest {
        val translator = FakeTranslator()
        val withTranslation = lyrics.copy(translation = ProvidedTranslation("zh-CN", listOf("甲", "", "乙")))
        repository().translate(track, withTranslation, translator, "zh-TW")

        assertEquals(listOf(listOf("Title", "a", "b")), translator.requests)
    }
}
