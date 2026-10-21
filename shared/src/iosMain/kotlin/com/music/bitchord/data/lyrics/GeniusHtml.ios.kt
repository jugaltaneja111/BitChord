package com.music.bitchord.data.lyrics

internal actual fun geniusLyricsText(html: String): String? = GeniusHtmlScanner.lyricsText(html)
