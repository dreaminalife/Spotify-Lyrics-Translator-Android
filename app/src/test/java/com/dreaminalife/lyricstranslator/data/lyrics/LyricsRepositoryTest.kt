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
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val track = Track("spotify:track:1", "Title", "Artist", "", 1000)
    private val calls = mutableListOf<LyricsSource>()
    private val searches = mutableListOf<String>()

    private inner class FakeProvider(override val source: LyricsSource, var result: suspend (Track) -> Lyrics?) : LyricsProvider {
        override suspend fun fetch(track: Track): Lyrics? {
            calls += source
            searches += "${source.name} ${track.title}"
            return result(track)
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

    // Native names

    private val usseewa = Track("spotify:track:2", "Usseewa", "Ado", "Usseewa", 204_920)
    private val lookups = mutableListOf<String>()
    private var nativeResult: suspend (Track) -> NativeNames? = { NativeNames("うっせぇわ", "Ado") }

    private val nativeLookup by lazy { newNativeLookup() }

    private fun newNativeLookup() =
        NativeNameLookup(
            { lookups += it.title; nativeResult(it) },
            JsonFileCache(folder.newFolder(), NativeNames.serializer()),
            JsonFileCache(folder.newFolder(), Boolean.serializer()),
        )

    private fun repositoryWithNativeNames(vararg providers: LyricsProvider, lookup: NativeNameLookup = nativeLookup) =
        LyricsRepository(providers.toList(), JsonFileCache(folder.newFolder(), Lyrics.serializer()), lookup)

    private fun foundOnlyAs(source: LyricsSource, title: String): suspend (Track) -> Lyrics? =
        { if (it.title == title) lyricsFrom(source) else null }

    @Test
    fun `after the first miss the source retries with native names, and later sources search them first`() = runTest {
        netEase.result = { null }
        qq.result = foundOnlyAs(QQ_MUSIC, "うっせぇわ")
        val lyrics = repositoryWithNativeNames(netEase, qq).get(usseewa, default)

        assertEquals(QQ_MUSIC, lyrics?.source)
        assertEquals(listOf("NETEASE Usseewa", "NETEASE うっせぇわ", "QQ_MUSIC うっせぇわ"), searches)
        assertEquals(listOf("Usseewa"), lookups)
    }

    @Test
    fun `native names are searched without Spotify's album`() = runTest {
        val albums = mutableListOf<String>()
        netEase.result = { albums += it.album; null }
        repositoryWithNativeNames(netEase).get(usseewa, default)

        assertEquals(listOf("Usseewa", ""), albums)
    }

    @Test
    fun `a source still finds Spotify's names after missing native ones`() = runTest {
        netEase.result = { null }
        qq.result = foundOnlyAs(QQ_MUSIC, "Usseewa")

        assertEquals(QQ_MUSIC, repositoryWithNativeNames(netEase, qq).get(usseewa, default)?.source)
        assertEquals(listOf("NETEASE Usseewa", "NETEASE うっせぇわ", "QQ_MUSIC うっせぇわ", "QQ_MUSIC Usseewa"), searches)
    }

    @Test
    fun `native names are not looked up when the first source finds the song`() = runTest {
        repositoryWithNativeNames(netEase, qq).get(usseewa, default)

        assertEquals(listOf("NETEASE Usseewa"), searches)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `cached native names go first from the first source on`() = runTest {
        val repository = repositoryWithNativeNames(netEase)
        netEase.result = { null }
        repository.get(usseewa, default)
        searches.clear()
        netEase.result = foundOnlyAs(NETEASE, "うっせぇわ")

        assertEquals(NETEASE, repository.get(usseewa, default)?.source)
        assertEquals(listOf("NETEASE うっせぇわ"), searches)
        assertEquals(listOf("Usseewa"), lookups)
    }

    @Test
    fun `Spotify searches only by its own names`() = runTest {
        val spotify = FakeProvider(LyricsSource.SPOTIFY) { null }
        netEase.result = { null }
        repositoryWithNativeNames(spotify, netEase).get(usseewa, default)

        assertEquals(listOf("SPOTIFY Usseewa", "NETEASE うっせぇわ", "NETEASE Usseewa"), searches)
    }

    @Test
    fun `names that differ only in case are not searched again`() = runTest {
        netEase.result = { null }
        qq.result = { null }
        nativeResult = { NativeNames("USSEEWA", "ADO") }

        assertNull(repositoryWithNativeNames(netEase, qq).get(usseewa, default))
        assertEquals(listOf("NETEASE Usseewa", "QQ_MUSIC Usseewa"), searches)
    }

    @Test
    fun `a failed lookup searches Spotify's names and is retried next time`() = runTest {
        val repository = repositoryWithNativeNames(netEase, qq)
        netEase.result = { null }
        qq.result = { null }
        nativeResult = { throw IOException("HTTP 403") }
        repository.get(usseewa, default)
        assertEquals(listOf("NETEASE Usseewa", "QQ_MUSIC Usseewa"), searches)

        nativeResult = { NativeNames("うっせぇわ", "Ado") }
        repository.get(usseewa, default)
        assertEquals(listOf("Usseewa", "Usseewa"), lookups)
    }

    // The artists a source that finds nothing searches, starting from empty caches.
    private suspend fun artistsSearched(track: Track, native: NativeNames, before: Track? = null): List<String> {
        val repository = repositoryWithNativeNames(netEase, lookup = newNativeLookup())
        before?.let { repository.get(it, default) }
        val artists = mutableListOf<String>()
        netEase.result = { artists += it.artist; null }
        nativeResult = { native }
        repository.get(track, default)
        return artists
    }

    @Test
    fun `a katakana-only artist is not searched for a song without kana`() = runTest {
        val antiHero = Track("spotify:track:3", "Anti-Hero", "Taylor Swift", "", 200_000)

        assertEquals(listOf("Taylor Swift"), artistsSearched(antiHero, NativeNames("Anti-Hero", "テイラー・スウィフト")))
        assertEquals(listOf("Taylor Swift"), artistsSearched(antiHero, NativeNames("Anti-Hero", "テイラー・スウィフト & ILLENIUM")))
    }

    @Test
    fun `an artist in kanji is searched`() = runTest {
        val lemon = Track("spotify:track:4", "Lemon", "Kenshi Yonezu", "", 255_000)

        assertEquals(listOf("Kenshi Yonezu", "米津玄師"), artistsSearched(lemon, NativeNames("Lemon", "米津玄師")))
    }

    @Test
    fun `a katakana-only artist is searched for a song with a kana title`() = runTest {
        val hitchcock = Track("spotify:track:5", "ヒッチコック", "Yorushika", "", 250_000)

        assertEquals(listOf("Yorushika", "ヨルシカ"), artistsSearched(hitchcock, NativeNames("ヒッチコック", "ヨルシカ")))
    }

    @Test
    fun `a katakana-only artist is searched once the artist's lyrics were found in Japanese`() = runTest {
        val sayIt = Track("spotify:track:6", "Say It", "Yorushika", "", 250_000)
        val names = NativeNames("Say It", "ヨルシカ")
        val japanese = Lyrics(listOf(LyricsLine(0, "言って")), synced = true, source = NETEASE)
        netEase.result = { japanese }
        val earlierSong = Track("spotify:track:5", "ヒッチコック", "Yorushika", "", 250_000)
        assertEquals(listOf("Yorushika", "ヨルシカ"), artistsSearched(sayIt, names, before = earlierSong))

        netEase.result = { lyricsFrom(NETEASE) }
        val englishSong = Track("spotify:track:7", "Shape of You", "Yorushika", "", 250_000)
        assertEquals(listOf("Yorushika"), artistsSearched(sayIt, names, before = englishSong))
    }

    @Test
    fun `lyrics with a quarter of their lines in kana are Japanese, Chinese ones are not`() = runTest {
        val sayIt = Track("spotify:track:6", "Say It", "Yorushika", "", 250_000)
        val names = NativeNames("Say It", "ヨルシカ")
        val earlierSong = Track("spotify:track:5", "Hitchcock", "Yorushika", "", 250_000)
        fun lyricsOf(vararg lines: String) = Lyrics(lines.map { LyricsLine(0, it) }, synced = true, source = NETEASE)

        netEase.result = { lyricsOf("作词 : n-buna", "言って", "言ってよ", "Say it", "Say it again", "Oh", "Oh oh", "Yeah") }
        assertEquals(listOf("Yorushika", "ヨルシカ"), artistsSearched(sayIt, names, before = earlierSong))

        netEase.result = { lyricsOf("说吧", "再说一次") }
        assertEquals(listOf("Yorushika"), artistsSearched(sayIt, names, before = earlierSong))
    }

    @Test
    fun `a track without a length is not looked up`() = runTest {
        netEase.result = { null }
        repositoryWithNativeNames(netEase).get(usseewa.copy(durationMs = 0), default)

        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `titles and artists already in native script are not looked up`() = runTest {
        netEase.result = { null }
        repositoryWithNativeNames(netEase).get(Track("k", "うっせぇわ", "米津玄師", "", 1000), default)

        assertTrue(lookups.isEmpty())
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
