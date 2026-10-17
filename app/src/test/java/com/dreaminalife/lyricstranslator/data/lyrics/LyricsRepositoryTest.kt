package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.data.cache.JsonFileCache
import com.dreaminalife.lyricstranslator.domain.LyricsProvider
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsLine
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.KUGOU
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.NETEASE
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource.QQ_MUSIC
import com.dreaminalife.lyricstranslator.domain.model.LyricsSourcePreference
import com.dreaminalife.lyricstranslator.domain.model.Track
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val track = Track("spotify:track:1", "Title", "Artist", "", 1000)
    private val calls = mutableListOf<LyricsSource>()

    private inner class FakeProvider(override val source: LyricsSource, var result: suspend () -> Lyrics?) : LyricsProvider {
        override suspend fun fetch(track: Track): Lyrics? {
            calls += source
            return result()
        }
    }

    private fun lyricsFrom(source: LyricsSource) = Lyrics(listOf(LyricsLine(0, source.name)), synced = true, source = source)

    private val netEase = FakeProvider(NETEASE) { lyricsFrom(NETEASE) }
    private val qq = FakeProvider(QQ_MUSIC) { lyricsFrom(QQ_MUSIC) }
    private val kugou = FakeProvider(KUGOU) { lyricsFrom(KUGOU) }

    // Lazy: the temporary folder only exists once the test runs.
    private val repository by lazy {
        LyricsRepository(listOf(netEase, qq, kugou), JsonFileCache(folder.root, Lyrics.serializer()))
    }

    private val default = LyricsSourcePreference()
    private val qqFirst = LyricsSourcePreference(pinned = QQ_MUSIC)

    @Test
    fun `providers are asked in the custom order and a source without lyrics falls through`() = runTest {
        qq.result = { null }
        val lyrics = repository.get(track, LyricsSourcePreference(listOf(QQ_MUSIC, KUGOU, NETEASE)))

        assertEquals(KUGOU, lyrics?.source)
        assertEquals(listOf(QQ_MUSIC, KUGOU), calls)
    }

    @Test
    fun `a source without lyrics is asked again every time, before the cached source after it`() = runTest {
        netEase.result = { null }
        assertEquals(QQ_MUSIC, repository.get(track, default)?.source)
        assertEquals(QQ_MUSIC, repository.get(track, default)?.source)

        assertEquals(listOf(NETEASE, QQ_MUSIC, NETEASE), calls)
    }

    @Test
    fun `a source's cached lyrics are used under any order`() = runTest {
        repository.get(track, default)
        qq.result = { null }
        assertEquals(NETEASE, repository.get(track, qqFirst)?.source)

        assertEquals(listOf(NETEASE, QQ_MUSIC), calls)
    }

    @Test
    fun `only the first source's cached lyrics show without a lookup`() = runTest {
        repository.get(track, default)

        assertEquals(NETEASE, repository.cached(track, default)?.source)
        assertNull(repository.cached(track, qqFirst))
    }

    @Test
    fun `a source that takes too long is skipped`() = runTest {
        netEase.result = { awaitCancellation() }

        assertEquals(QQ_MUSIC, repository.get(track, default)?.source)
    }

    @Test
    fun `when every source takes too long the error is not a cancellation`() = runTest {
        listOf(netEase, qq, kugou).forEach { it.result = { awaitCancellation() } }

        val error = runCatching { repository.get(track, default) }.exceptionOrNull()
        assertTrue(error != null && error !is CancellationException)
    }

    @Test
    fun `clearing a source keeps the other sources' cached lyrics`() = runTest {
        repository.get(track, default)
        repository.get(track, qqFirst)
        repository.clear(track, NETEASE)

        assertNull(repository.cached(track, default))
        assertEquals(QQ_MUSIC, repository.cached(track, qqFirst)?.source)
    }
}
