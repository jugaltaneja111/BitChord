package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniXmlTest {

    @Test
    fun `keeps prefixed names and decodes text`() {
        val root = MiniXml.parse(
            """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY x "boom">]><!-- c --><tt xmlns:ttm="t">""" +
                """<head><ttm:agent type="person" xml:id="v1"/></head>""" +
                """<body><p begin="1.0" ttm:agent="v1"><span begin="1.0" end="1.5">R&amp;B &#233;&#x1F600;</span> <![CDATA[<raw>]]>&x;</p></body></tt>""",
        )
        assertEquals("tt", root.name)
        val agent = root.elementsNamed("ttm:agent").single()
        assertEquals("v1", agent.attribute("xml:id"))
        val p = root.elementsNamed("p").single()
        assertEquals("v1", p.attribute("ttm:agent"))
        assertEquals("v1", p.attribute("agent"))
        // A declared entity is never expanded: it stays as written.
        assertEquals("R&B é😀 <raw>&x;", p.textContent)
    }

    @Test
    fun `an unclosed element is closed by its parent`() {
        val root = MiniXml.parse("<a><b><c>x</b><d/></a>")
        assertEquals(listOf("b", "d"), root.children.filterIsInstance<MiniXml.Element>().map { it.name })
        assertEquals("x", root.textContent)
    }
}
