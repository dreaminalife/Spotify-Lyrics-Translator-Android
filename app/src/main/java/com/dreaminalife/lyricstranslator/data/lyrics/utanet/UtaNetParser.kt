package com.dreaminalife.lyricstranslator.data.lyrics.utanet

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** One row of the Uta-Net search results table. [utaidashi] holds the lyrics text without line breaks. */
data class UtaNetSearchRow(
    val songId: String?,
    val title: String,
    val artistId: String,
    val artistName: String,
    val utaidashi: String,
)

object UtaNetParser {
    private val SONG_ID = Regex("""/song/(\d+)/""")
    private val ARTIST_ID = Regex("""/artist/(\d+)/""")
    private val WHITESPACE = Regex("""\s+""")

    /** Same table layout and selectors as the desktop app. */
    fun parseSearchResults(html: String): List<UtaNetSearchRow> {
        val body = Jsoup.parse(html).selectFirst(".songlist-table-body") ?: return emptyList()
        return body.select("tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 6) return@mapNotNull null
            val songLink = cells[0].selectFirst("a")
            val artistLink = cells[1].selectFirst("a")
            val title = cells[0].selectFirst("span.songlist-title")?.text()?.trim().orEmpty()
            val artistId = artistLink?.attr("href")?.let { ARTIST_ID.find(it)?.groupValues?.get(1) }.orEmpty()
            val artistName = artistLink?.text()?.trim().orEmpty()
            val utaidashi = cells[5].selectFirst("span.pc-utaidashi")?.text()?.replace('　', ' ')?.trim().orEmpty()
            if (title.isEmpty() || artistId.isEmpty() || artistName.isEmpty() || utaidashi.isEmpty()) {
                return@mapNotNull null
            }
            UtaNetSearchRow(
                songId = songLink?.attr("href")?.let { SONG_ID.find(it)?.groupValues?.get(1) },
                title = title,
                artistId = artistId,
                artistName = artistName,
                utaidashi = utaidashi,
            )
        }
    }

    /** Lyrics lines from a song page's `#kashi_area`, split on `<br>`; empty if the element is missing. */
    fun parseSongPage(html: String): List<String> {
        val area = Jsoup.parse(html).getElementById("kashi_area") ?: return emptyList()
        val text = StringBuilder()
        appendText(area, text)
        return text.lines()
            .map { it.replace('　', ' ').replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
    }

    /** Desktop fallback: the search-result text has no line breaks, so every space-separated phrase becomes a line. */
    fun splitUtaidashi(utaidashi: String): List<String> =
        utaidashi.split(WHITESPACE).filter { it.isNotEmpty() }

    private fun appendText(element: Element, out: StringBuilder) {
        for (node in element.childNodes()) {
            when {
                node is TextNode -> out.append(node.wholeText.replace('\n', ' '))
                node is Element && node.normalName() == "br" -> out.append('\n')
                // Ruby annotations (furigana) would duplicate the reading; keep only the base text.
                node is Element && node.normalName() in setOf("rt", "rp") -> Unit
                node is Element -> appendText(node, out)
            }
        }
    }
}
