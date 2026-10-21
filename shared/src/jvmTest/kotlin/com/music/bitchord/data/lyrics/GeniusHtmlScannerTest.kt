package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The scanner iOS reads Genius pages with, held to what Jsoup reads from the same page. */
class GeniusHtmlScannerTest {

    private val page = """
        <!DOCTYPE html>
        <html>
        <head><title>Queen - Bohemian Rhapsody Lyrics | Genius Lyrics</title>
        <script>var x = "<div data-lyrics-container=\"true\">not lyrics</div>";</script></head>
        <body>
          <div data-lyrics-container="true" class="Lyrics__Container">
            <div data-exclude-from-selection="true" class="LyricsHeader__Container">
              <button>523 Contributors</button>
              <div class="SongBioPreview__Container">Song Bio</div>
            </div>
            [Intro]<br>
            Is this the real life?<br/>
            Is this <i>just</i> fantasy?<br>
            <br>
            [Verse 1]<br>
            Mama, just killed a man &amp; it&#x27;s over<br>
            <a href="/x"><span class="ReferentFragment">Put a gun against his head</span></a>, pulled my trigger<br>
            <div class="InreadAd__Container"><div>Advert</div></div>
            15You might also like<br>
          </div>
          <div data-lyrics-container="true" class="Lyrics__Container">
            [Chorus]<br>
            Mama, life had just begun<br>
            42Embed
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun lines(text: String?) = Genius.textToLyricLines(Genius.stripArtifacts(text.orEmpty())).map { it.text }

    @Test
    fun `reads the same lines as Jsoup`() {
        assertEquals(lines(geniusLyricsText(page)), lines(GeniusHtmlScanner.lyricsText(page)))
    }

    @Test
    fun `falls back to div lyrics`() {
        val old = """<div class="song lyrics"><p>One<br>Two</p></div>"""
        assertEquals(lines(geniusLyricsText(old)), lines(GeniusHtmlScanner.lyricsText(old)))
    }

    @Test
    fun `no container is no lyrics`() {
        assertNull(GeniusHtmlScanner.lyricsText("<html><body><div>nothing</div></body></html>"))
    }
}
