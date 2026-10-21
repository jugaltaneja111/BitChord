package com.music.bitchord.data.lyrics

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

internal actual fun geniusLyricsText(html: String): String? {
    val doc = Jsoup.parse(html)

    // Modern Genius uses data-lyrics-container="true", older pages use div.lyrics
    var containers = doc.select("div[data-lyrics-container=true]")
    if (containers.isEmpty()) {
        containers = doc.select("div.lyrics")
    }
    if (containers.isEmpty()) return null

    val fullTextBuilder = StringBuilder()

    for (container in containers) {
        // Remove headers, contributors, translation links, buttons, ads
        container.select(
            "[data-exclude-from-selection=true], " +
                ".LyricsHeader__Container, " +
                ".SongBioPreview__Container, " +
                ".InreadAd__Container, " +
                "button, " +
                "script, " +
                "style",
        ).remove()

        // Turn <br> into explicit newlines before extracting text
        container.select("br").forEach { it.replaceWith(TextNode("\n")) }
        container.select("p").forEach { it.prepend("\n") }

        val text = container.wholeText()
        if (text.isNotBlank()) {
            fullTextBuilder.append(text).append("\n")
        }
    }
    return fullTextBuilder.toString()
}
