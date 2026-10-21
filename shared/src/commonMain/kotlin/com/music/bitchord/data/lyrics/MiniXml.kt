package com.music.bitchord.data.lyrics

/**
 * Just enough XML to read a lyrics document on every platform: elements, their attributes under
 * the names written (`ttm:agent` stays `ttm:agent`), text, CDATA, and the five named entities plus
 * numeric ones. Declarations, comments, processing instructions and any DOCTYPE are skipped, and
 * no entity a document declares is ever expanded — lyrics arrive from third-party hosts, so
 * nothing a document asks for is resolved.
 *
 * Lenient where a strict parser would give up: an end tag that closes something further up closes
 * everything in between, and one that matches nothing is ignored.
 */
internal object MiniXml {

    sealed interface Node

    class Text(val text: String) : Node

    class Element(
        val name: String,
        val attributes: Map<String, String>,
        val children: List<Node>,
    ) : Node {
        /** The attribute under [name], or under its local part, or `""`, as DOM's getAttribute answers. */
        fun attribute(name: String): String {
            attributes[name]?.let { return it }
            val local = name.substringAfter(':')
            return attributes.entries.firstOrNull { (key, _) -> key == local || key.substringAfter(':') == local }
                ?.value.orEmpty()
        }

        /** Every text below this element, in document order. */
        val textContent: String
            get() = buildString { appendText(this@Element) }

        /** Every element below this one named [name], in document order. */
        fun elementsNamed(name: String): List<Element> = buildList { collectNamed(this@Element, name) }

        private fun StringBuilder.appendText(element: Element) {
            element.children.forEach { child ->
                when (child) {
                    is Text -> append(child.text)
                    is Element -> appendText(child)
                }
            }
        }

        private fun MutableList<Element>.collectNamed(element: Element, name: String) {
            element.children.forEach { child ->
                if (child is Element) {
                    if (child.name == name) add(child)
                    collectNamed(child, name)
                }
            }
        }
    }

    /** The document's root element; throws on input with no element at all. */
    fun parse(xml: String): Element {
        val root = Builder("#document")
        val stack = ArrayDeque<Builder>().apply { addLast(root) }
        var i = 0
        val n = xml.length
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                stack.last().children += Text(decode(text.toString()))
                text.setLength(0)
            }
        }

        while (i < n) {
            val c = xml[i]
            if (c != '<') {
                text.append(c)
                i++
                continue
            }
            when {
                xml.startsWith("<!--", i) -> {
                    flushText()
                    i = xml.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                }
                xml.startsWith("<![CDATA[", i) -> {
                    flushText()
                    val end = xml.indexOf("]]>", i + 9).let { if (it < 0) n else it }
                    stack.last().children += Text(xml.substring(i + 9, end))
                    i = minOf(n, end + 3)
                }
                xml.startsWith("<?", i) -> {
                    flushText()
                    i = xml.indexOf("?>", i + 2).let { if (it < 0) n else it + 2 }
                }
                xml.startsWith("<!", i) -> {
                    // DOCTYPE, with any internal subset in brackets: skipped whole.
                    flushText()
                    var depth = 0
                    var j = i + 2
                    while (j < n) {
                        val d = xml[j]
                        if (d == '[') depth++
                        if (d == ']') depth--
                        if (d == '>' && depth <= 0) break
                        j++
                    }
                    i = j + 1
                }
                xml.startsWith("</", i) -> {
                    flushText()
                    val end = xml.indexOf('>', i + 2).let { if (it < 0) n else it }
                    val name = xml.substring(i + 2, end).trim()
                    val open = stack.indexOfLast { it.name == name }
                    if (open > 0) {
                        while (stack.size > open) {
                            val done = stack.removeLast()
                            stack.last().children += done.build()
                        }
                    }
                    i = end + 1
                }
                else -> {
                    flushText()
                    i = readStartTag(xml, i + 1, stack)
                }
            }
        }
        flushText()
        while (stack.size > 1) {
            val done = stack.removeLast()
            stack.last().children += done.build()
        }
        return root.children.firstOrNull { it is Element } as? Element
            ?: throw IllegalArgumentException("no element in document")
    }

    /** Reads a start tag from just after its `<`; returns the index after its `>`. */
    private fun readStartTag(xml: String, from: Int, stack: ArrayDeque<Builder>): Int {
        val n = xml.length
        var i = from
        while (i < n && !xml[i].isWhitespace() && xml[i] != '>' && xml[i] != '/') i++
        val element = Builder(xml.substring(from, i))
        var selfClosing = false
        while (i < n) {
            val c = xml[i]
            when {
                c == '>' -> { i++; break }
                c == '/' -> { selfClosing = true; i++ }
                c.isWhitespace() -> i++
                else -> {
                    val nameStart = i
                    while (i < n && xml[i] != '=' && !xml[i].isWhitespace() && xml[i] != '>' && xml[i] != '/') i++
                    val name = xml.substring(nameStart, i)
                    while (i < n && xml[i].isWhitespace()) i++
                    var value = ""
                    if (i < n && xml[i] == '=') {
                        i++
                        while (i < n && xml[i].isWhitespace()) i++
                        if (i < n && (xml[i] == '"' || xml[i] == '\'')) {
                            val quote = xml[i]
                            val end = xml.indexOf(quote, i + 1).let { if (it < 0) n else it }
                            value = decode(xml.substring(i + 1, end))
                            i = end + 1
                        } else {
                            val start = i
                            while (i < n && !xml[i].isWhitespace() && xml[i] != '>') i++
                            value = decode(xml.substring(start, i))
                        }
                    }
                    if (name.isNotEmpty()) element.attributes[name] = value
                }
            }
        }
        if (selfClosing) stack.last().children += element.build() else stack.addLast(element)
        return i
    }

    private fun decode(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val end = if (c == '&') raw.indexOf(';', i + 1) else -1
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val entity = raw.substring(i + 1, end)
            val decoded: String? = when {
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "amp" -> "&"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? = when {
        value < 0 || value > 0x10FFFF -> null
        value < 0x10000 -> value.toChar().toString()
        else -> {
            val v = value - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    private class Builder(val name: String) {
        val attributes = LinkedHashMap<String, String>()
        val children = mutableListOf<Node>()
        fun build() = Element(name, attributes, children)
    }
}
