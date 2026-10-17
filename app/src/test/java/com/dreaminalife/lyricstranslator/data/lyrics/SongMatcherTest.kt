package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMatcherTest {
    private data class Song(val id: Int, val title: String, val durationMs: Long?, val artists: List<String>)

    private fun pick(track: Track, vararg songs: Song): Int? =
        SongMatcher.pick(track, songs.toList(), { it.title }, { it.durationMs }, { it.artists })?.id

    private fun track(title: String, artist: String, durationMs: Long = 200_000) = Track("key", title, artist, "", durationMs)

    // Title

    @Test
    fun `title ignores case, repeated and full-width spaces, and surrounding spaces`() {
        val track = track("Hype Boy", "NewJeans")
        assertEquals(1, pick(track, Song(1, "HYPE BOY", 200_000, listOf("NewJeans"))))
        assertEquals(1, pick(track, Song(1, "  Hype   Boy ", 200_000, listOf("NewJeans"))))
        assertEquals(1, pick(track, Song(1, "Hype　Boy", 200_000, listOf("NewJeans"))))
    }

    @Test
    fun `full-width parentheses equal ASCII ones`() {
        assertEquals(1, pick(track("Song(Live)", "Artist"), Song(1, "Song（Live）", 200_000, listOf("Artist"))))
    }

    @Test
    fun `versions with another title are rejected`() {
        val track = track("Dynamite", "BTS")
        assertNull(
            pick(
                track,
                Song(1, "Dynamite (Live)", 200_000, listOf("BTS")),
                Song(2, "Dynamite - Remix", 200_000, listOf("BTS")),
                Song(3, "BTS-Dynamite", 200_000, listOf("BTS")),
            ),
        )
    }

    // Duration

    @Test
    fun `duration may differ by up to 3 s either way`() {
        val track = track("Song", "Artist", 200_000)
        assertEquals(1, pick(track, Song(1, "Song", 203_000, listOf("Artist"))))
        assertEquals(1, pick(track, Song(1, "Song", 197_000, listOf("Artist"))))
        assertNull(pick(track, Song(1, "Song", 203_001, listOf("Artist"))))
        assertNull(pick(track, Song(1, "Song", 196_999, listOf("Artist"))))
    }

    @Test
    fun `a missing duration counts as 0, so it never matches a real song`() {
        assertNull(pick(track("Song", "Artist"), Song(1, "Song", null, listOf("Artist"))))
    }

    // Artist preference

    @Test
    fun `a matching artist wins over search order`() {
        val track = track("Lemon", "Kenshi Yonezu")
        val other = Song(1, "Lemon", 200_000, listOf("米津玄師"))
        val exact = Song(2, "Lemon", 200_000, listOf("Kenshi Yonezu"))
        assertEquals(2, pick(track, other, exact))
    }

    @Test
    fun `artist matching ignores case and accepts containment either way`() {
        assertEquals(1, pick(track("Song", "newjeans"), Song(1, "Song", 200_000, listOf("NewJeans"))))
        assertEquals(1, pick(track("Dynamite", "BTS"), Song(1, "Dynamite", 200_000, listOf("BTS (防弹少年团)"))))
        assertEquals(1, pick(track("Dynamite", "BTS (방탄소년단)"), Song(1, "Dynamite", 200_000, listOf("BTS"))))
    }

    @Test
    fun `artist names match on whole words, not letters inside another name`() {
        // Each pair: same title and length, unrelated artists in the same script, so nothing may match.
        listOf("IU" to "Julius", "Ado" to "Shadow", "Sia" to "Asia", "Asia" to "Sia", "Eve" to "Steve", "Rain" to "Rainbow")
            .forEach { (spotify, source) ->
                assertNull("$spotify vs $source", pick(track("Song", spotify), Song(1, "Song", 200_000, listOf(source))))
            }
    }

    @Test
    fun `whole-word matches still work with decorations and extra artists`() {
        assertEquals(1, pick(track("Song", "IU"), Song(1, "Song", 200_000, listOf("IU (아이유)"))))
        assertEquals(1, pick(track("Song", "Kenshi Yonezu"), Song(1, "Song", 200_000, listOf("Kenshi Yonezu & Someone"))))
        assertEquals(1, pick(track("Song", "Jay-Z"), Song(1, "Song", 200_000, listOf("JAY Z"))))
        assertEquals(1, pick(track("Song", "米津玄師"), Song(1, "Song", 200_000, listOf("米津玄師"))))
    }

    @Test
    fun `a whole-word match wins over an earlier letters-only coincidence`() {
        val track = track("Song", "IU")
        assertEquals(2, pick(track, Song(1, "Song", 200_000, listOf("Julius")), Song(2, "Song", 200_000, listOf("IU"))))
    }

    @Test
    fun `any of several Spotify artists or source artists can match`() {
        val track = track("Song", "YOASOBI, Ikura")
        assertEquals(1, pick(track, Song(1, "Song", 200_000, listOf("幾田りら", "ikura"))))
    }

    @Test
    fun `blank source artist names never match`() {
        val track = track("Song", "Artist")
        assertNull(pick(track, Song(1, "Song", 200_000, listOf(""))))
        assertNull(pick(track, Song(1, "Song", 200_000, emptyList())))
    }

    // Fallback when no artist matches

    @Test
    fun `an artist written in another script is accepted, in both directions`() {
        assertEquals(1, pick(track("Lemon", "Kenshi Yonezu"), Song(1, "Lemon", 200_000, listOf("米津玄師"))))
        assertEquals(1, pick(track("Lemon", "米津玄師"), Song(1, "Lemon", 200_000, listOf("Kenshi Yonezu"))))
        assertEquals(1, pick(track("Song", "IU"), Song(1, "Song", 200_000, listOf("아이유"))))
    }

    @Test
    fun `full-width Latin letters count as another script`() {
        assertEquals(1, pick(track("Idol", "YOASOBI"), Song(1, "Idol", 200_000, listOf("ＹＯＡＳＯＢＩ"))))
    }

    @Test
    fun `the first song in search order wins among fallbacks`() {
        val track = track("Lemon", "Kenshi Yonezu")
        assertEquals(1, pick(track, Song(1, "Lemon", 200_000, listOf("米津玄師")), Song(2, "Lemon", 200_000, listOf("米津玄師"))))
    }

    @Test
    fun `a different artist in the same script is rejected`() {
        assertNull(pick(track("Home", "Artist X"), Song(1, "Home", 200_000, listOf("Artist Y"))))
        assertNull(pick(track("Lemon", "米津玄師"), Song(1, "Lemon", 200_000, listOf("秋绘"))))
        assertNull(pick(track("Song", "아이유"), Song(1, "Song", 200_000, listOf("태연"))))
    }

    @Test
    fun `a name mixing scripts counts as Latin`() {
        assertNull(pick(track("Lemon", "Kenshi Yonezu"), Song(1, "Lemon", 200_000, listOf("安亚尔Anyar"))))
    }

    @Test
    fun `accented spellings of the same name are rejected, so the next source is tried`() {
        assertNull(pick(track("Song", "Beyoncé"), Song(1, "Song", 200_000, listOf("Beyonce"))))
    }

    @Test
    fun `a same-script fallback is skipped for a later other-script one`() {
        val track = track("Lemon", "Kenshi Yonezu")
        val cover = Song(1, "Lemon", 200_000, listOf("Someone Else"))
        val native = Song(2, "Lemon", 200_000, listOf("米津玄師"))
        assertEquals(2, pick(track, cover, native))
    }

    @Test
    fun `no songs gives null`() {
        assertNull(pick(track("Song", "Artist")))
    }
}
