package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class ITunesCatalogTest {
    // Trimmed from real US store answers for "Usseewa Ado".
    private val usSearch = """{"resultCount":4,"results":[
        {"wrapperType":"artist","artistName":"ADO","artistId":1492604670},
        {"wrapperType":"track","kind":"song","trackId":1544173502,"artistName":"ADO","trackName":"Usseewa (Piano Version)","collectionName":"Usseewa (Piano Version) - Single","trackTimeMillis":223053},
        {"wrapperType":"track","kind":"song","trackId":1535123742,"artistName":"ADO","trackName":"Usseewa","collectionName":"Usseewa - Single","trackTimeMillis":204920},
        {"wrapperType":"track","kind":"song","trackId":1603820308,"artistName":"ADO","trackName":"Usseewa","collectionName":"Kyougen","trackTimeMillis":206893}]}"""

    @Test
    fun `the matching song is picked by title and duration, skipping non-songs and other versions`() {
        val songs = ITunesCatalog.parseResults(usSearch)

        assertEquals(3, songs.size)
        assertEquals(1535123742L, ITunesCatalog.pickSong(Track("k", "Usseewa", "Ado", "", 205_000), songs)?.trackId)
        assertEquals(1603820308L, ITunesCatalog.pickSong(Track("k", "Usseewa", "Ado", "", 209_000), songs)?.trackId)
    }

    @Test
    fun `native names come from the Japan store's song`() {
        val jpLookup = """{"resultCount":1,"results":[{"wrapperType":"track","kind":"song","trackId":1535123742,
            "artistName":"Ado","trackName":"うっせぇわ","collectionName":"うっせぇわ - Single","trackTimeMillis":204920}]}"""

        assertEquals(NativeNames("うっせぇわ", "Ado"), ITunesCatalog.namesOf(ITunesCatalog.parseResults(jpLookup).single()))
    }
}
