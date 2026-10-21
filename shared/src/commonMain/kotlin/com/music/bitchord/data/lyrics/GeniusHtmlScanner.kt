package com.music.bitchord.data.lyrics

/**
 * [geniusLyricsText] without an HTML library, for platforms that have no Jsoup: one pass over the
 * page's tags, the same containers and the same exclusions. Genius' markup is machine-written and
 * well formed, which is all this relies on; a close tag that matches nothing open is ignored.
 */
internal object GeniusHtmlScanner {

    private val VOID = setOf("br", "img", "hr", "input", "meta", "link", "wbr", "area", "col", "source", "base", "embed")
    private val RAW_TEXT = setOf("script", "style")
    private val EXCLUDED_TAGS = setOf("button", "script", "style")
    private val EXCLUDED_CLASSES = listOf(
        "LyricsHeader__Container",
        "SongBioPreview__Container",
        "InreadAd__Container",
    )

    private class Tag(val name: String, val attributes: Map<String, String>, val closing: Boolean, val selfClosing: Boolean, val end: Int)

    fun lyricsText(html: String): String? {
        var starts = containerStarts(html) { it["data-lyrics-container"] == "true" }
        if (starts.isEmpty()) starts = containerStarts(html) { "lyrics" in classes(it) }
        if (starts.isEmpty()) return null
        val out = StringBuilder()
        var after = 0
        for (start in starts) {
            // A container inside one already read is part of it.
            if (start < after) continue
            val text = StringBuilder()
            after = readContainer(html, start, text)
            if (text.isNotBlank()) out.append(text).append('\n')
        }
        return out.toString()
    }

    private fun containerStarts(html: String, matches: (Map<String, String>) -> Boolean): List<Int> {
        val found = mutableListOf<Int>()
        var i = html.indexOf("<div")
        while (i >= 0) {
            val tag = readTag(html, i)
            if (tag != null && !tag.closing && tag.name == "div" && matches(tag.attributes)) found += i
            i = html.indexOf("<div", i + 4)
        }
        return found
    }

    /** Appends the container's words to [out]; returns the index just past its close tag. */
    private fun readContainer(html: String, start: Int, out: StringBuilder): Int {
        val open = ArrayList<String>()
        var excludedAt = -1
        var i = start
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            val textEnd = if (lt < 0) n else lt
            if (textEnd > i && excludedAt < 0 && open.isNotEmpty()) out.append(decode(html.substring(i, textEnd)))
            if (lt < 0) return n
            if (html.startsWith("<!--", lt)) {
                i = html.indexOf("-->", lt + 4).let { if (it < 0) n else it + 3 }
                continue
            }
            val tag = readTag(html, lt)
            if (tag == null) {
                if (excludedAt < 0 && open.isNotEmpty()) out.append('<')
                i = lt + 1
                continue
            }
            i = tag.end
            if (tag.closing) {
                val at = open.lastIndexOf(tag.name)
                if (at < 0) continue
                while (open.size > at) open.removeAt(open.lastIndex)
                if (excludedAt >= 0 && open.size <= excludedAt) excludedAt = -1
                if (open.isEmpty()) return i
                continue
            }
            if (tag.name in RAW_TEXT && !tag.selfClosing) {
                // Script and style bodies are never markup, and never lyrics.
                i = html.indexOf("</${tag.name}", i, ignoreCase = true).let { if (it < 0) n else html.indexOf('>', it).let { e -> if (e < 0) n else e + 1 } }
                continue
            }
            if (excludedAt < 0 && open.isNotEmpty()) {
                if (tag.name == "br") out.append('\n')
                if (tag.name == "p") out.append('\n')
            }
            if (tag.selfClosing || tag.name in VOID) continue
            if (excludedAt < 0 && open.isNotEmpty() && isExcluded(tag)) excludedAt = open.size
            open += tag.name
        }
        return n
    }

    private fun isExcluded(tag: Tag): Boolean =
        tag.name in EXCLUDED_TAGS ||
            tag.attributes["data-exclude-from-selection"] == "true" ||
            classes(tag.attributes).any { cls -> EXCLUDED_CLASSES.any { cls.startsWith(it) } }

    private fun classes(attributes: Map<String, String>): List<String> =
        attributes["class"]?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }.orEmpty()

    /** The tag starting at [lt], or null when what follows `<` is not a tag. */
    private fun readTag(html: String, lt: Int): Tag? {
        val n = html.length
        var i = lt + 1
        val closing = i < n && html[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < n && (html[i].isLetterOrDigit() || html[i] == '-' || html[i] == ':')) i++
        if (i == nameStart) return null
        val name = html.substring(nameStart, i).lowercase()
        val attributes = HashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            val c = html[i]
            when {
                c == '>' -> return Tag(name, attributes, closing, selfClosing, i + 1)
                c == '/' -> { selfClosing = true; i++ }
                c.isWhitespace() -> i++
                else -> {
                    selfClosing = false
                    val keyStart = i
                    while (i < n && html[i] != '=' && html[i] != '>' && !html[i].isWhitespace() && html[i] != '/') i++
                    val key = html.substring(keyStart, i).lowercase()
                    while (i < n && html[i].isWhitespace()) i++
                    var value = ""
                    if (i < n && html[i] == '=') {
                        i++
                        while (i < n && html[i].isWhitespace()) i++
                        if (i < n && (html[i] == '"' || html[i] == '\'')) {
                            val quote = html[i]
                            val end = html.indexOf(quote, i + 1).let { if (it < 0) n else it }
                            value = decode(html.substring(i + 1, end))
                            i = end + 1
                        } else {
                            val start = i
                            while (i < n && !html[i].isWhitespace() && html[i] != '>') i++
                            value = html.substring(start, i)
                        }
                    }
                    if (key.isNotEmpty()) attributes[key] = value
                }
            }
        }
        return null
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "zwj" to "‍", "zwnj" to "‌",
    )

    private fun decode(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val end = if (raw[i] == '&') raw.indexOf(';', i + 1) else -1
            val entity = if (end in (i + 2)..(i + 10)) raw.substring(i + 1, end) else null
            val decoded = entity?.let {
                when {
                    it.startsWith("#x") || it.startsWith("#X") -> it.substring(2).toIntOrNull(16)?.let(::codePoint)
                    it.startsWith("#") -> it.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> NAMED[it]
                }
            }
            if (decoded == null) {
                out.append(raw[i])
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? = when {
        value <= 0 || value > 0x10FFFF -> null
        value < 0x10000 -> value.toChar().toString()
        else -> {
            val v = value - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}
