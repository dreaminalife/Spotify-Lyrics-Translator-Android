package com.dreaminalife.lyricstranslator.data.lyrics.utanet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures are trimmed copies of real uta-net.com markup (checked October 2026). */
class UtaNetParserTest {
    private val searchHtml = """
        <table><tbody class="songlist-table-body">
        <tr class="border-bottom"><td class="sp-w-100"><a href="/song/319082/"><span class="fw-bold songlist-title">夜に駆ける</span><span class="d-block d-lg-none utaidashi">YOASOBI</span></a></td>
          <td class="sp-none fw-bold"><a href="/artist/12586/">YOASOBI</a></td><td><a href="/lyricist/43927/">Ayase</a></td><td><a href="/composer/44366/">Ayase</a></td><td><a href="/arranger/0/"></a></td>
          <td class="sp-none fw-bold"><span class="d-block pc-utaidashi">沈むように溶けてゆくように　二人だけの空が広がる夜に</span></td></tr>
        <tr class="border-bottom"><td><a href="/song/335169/"><span class="fw-bold songlist-title">夜に駆ける</span></a></td>
          <td><a href="/artist/34274/">Cover　Artist</a></td><td></td><td></td><td></td>
          <td><span class="d-block pc-utaidashi">沈むように</span></td></tr>
        <tr><td>broken row</td></tr>
        </tbody></table>
    """.trimIndent()

    @Test
    fun `parses search rows and skips incomplete ones`() {
        val rows = UtaNetParser.parseSearchResults(searchHtml)
        assertEquals(2, rows.size)
        assertEquals(
            UtaNetSearchRow("319082", "夜に駆ける", "12586", "YOASOBI", "沈むように溶けてゆくように 二人だけの空が広がる夜に"),
            rows[0],
        )
        assertEquals("Cover Artist", rows[1].artistName.replace('　', ' '))
    }

    @Test
    fun `no results table gives no rows`() {
        assertEquals(emptyList<UtaNetSearchRow>(), UtaNetParser.parseSearchResults("<html><body>404</body></html>"))
    }

    @Test
    fun `song page splits on br, keeps full-width spaces inside a line, drops blank lines`() {
        val html = """
            <div id="kashi_area" itemprop="text">沈むように溶けてゆくように<br />二人だけの空が広がる夜に<br /><br />日が沈み出した空と君の姿　フェンス越しに重なっていた<br /><ruby>夜<rt>よる</rt></ruby>に</div>
        """.trimIndent()
        assertEquals(
            listOf("沈むように溶けてゆくように", "二人だけの空が広がる夜に", "日が沈み出した空と君の姿 フェンス越しに重なっていた", "夜に"),
            UtaNetParser.parseSongPage(html),
        )
    }

    @Test
    fun `song page without lyrics gives nothing so the provider falls back`() {
        assertEquals(emptyList<String>(), UtaNetParser.parseSongPage("<html><body>changed layout</body></html>"))
        assertEquals(listOf("沈むように", "二人だけの", "Hello", "world"), UtaNetParser.splitUtaidashi("沈むように 二人だけの  Hello world"))
    }

    @Test
    fun `exact artist match ignores case and full-width spaces`() = runTest {
        val rows = UtaNetParser.parseSearchResults(searchHtml)
        assertEquals("12586", ExactArtistMatcher().pick("yoasobi", rows)?.artistId)
        assertEquals("34274", ExactArtistMatcher().pick(" cover  artist ", rows)?.artistId)
        assertNull(ExactArtistMatcher().pick("Someone Else", rows))
    }

    @Test
    fun `llm artist id is read from fenced or chatty replies`() {
        assertEquals("1234", OpenRouterArtistMatcher.extractArtistId("```json\n{\"artist_id\": \"1234\"}\n```"))
        assertEquals("5678", OpenRouterArtistMatcher.extractArtistId("Sure! {\"artist_id\": 5678}"))
        assertNull(OpenRouterArtistMatcher.extractArtistId("{\"artist_id\": null}"))
        assertNull(OpenRouterArtistMatcher.extractArtistId("no json"))
    }
}
