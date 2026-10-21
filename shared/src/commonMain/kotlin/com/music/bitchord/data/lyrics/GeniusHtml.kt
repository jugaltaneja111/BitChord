package com.music.bitchord.data.lyrics

/**
 * The words in a Genius song page: the text of every `data-lyrics-container`
 * (older pages: `div.lyrics`), with `<br>` and paragraph starts as line breaks
 * and the page furniture inside them — headers, ads, buttons — left out.
 * Null when the page has no lyrics container at all.
 */
internal expect fun geniusLyricsText(html: String): String?
