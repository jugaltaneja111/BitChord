package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Runs on the JVM, where primaryLanguage is java.util.Locale, and on iOS, where it is parsed by
// hand: the same expectations hold both implementations to one behaviour.
class LocaleTagsTest {

    @Test
    fun `primary language is the lowercased first subtag`() {
        assertEquals("en", primaryLanguage("en-US"))
        assertEquals("pt", primaryLanguage("PT-br"))
        assertEquals("fil", primaryLanguage("fil"))
        assertEquals("sr", primaryLanguage("sr-Latn-RS"))
    }

    @Test
    fun `malformed tags have no primary language`() {
        assertEquals("", primaryLanguage(""))
        assertEquals("", primaryLanguage("e"))
        assertEquals("", primaryLanguage("en_US"))
        assertEquals("", primaryLanguage("123"))
    }

    @Test
    fun `app tags keep the Chinese script and drop other regions`() {
        assertEquals(LocaleTags.ZH_HANT, LocaleTags.normalizeAppTag("zh_TW"))
        assertEquals(LocaleTags.ZH_HANT, LocaleTags.normalizeAppTag("zh-Hant-HK"))
        assertEquals(LocaleTags.ZH_HANS, LocaleTags.normalizeAppTag("zh"))
        assertEquals("en", LocaleTags.normalizeAppTag("en-GB"))
        assertEquals("pt", LocaleTags.normalizeAppTag("pt_BR"))
        assertEquals("en", LocaleTags.normalizeAppTag(null))
        assertEquals("en", LocaleTags.normalizeAppTag("  "))
    }

    @Test
    fun `legacy language codes are mapped to current ones`() {
        assertEquals("he", LocaleTags.normalizeAppTag("iw-IL"))
        assertEquals("id", LocaleTags.normalizeAppTag("in"))
        assertEquals("yi", LocaleTags.normalizeAppTag("ji"))
    }

    @Test
    fun `script aware comparison`() {
        assertTrue(LocaleTags.sameLanguageScriptAware("en-US", "en_GB"))
        assertTrue(!LocaleTags.sameLanguageScriptAware("zh-TW", "zh-CN"))
        assertTrue(LocaleTags.sameLanguageScriptAware("zh-Hant", "zh-HK"))
    }

    @Test
    fun `the system tag is normalised`() {
        val tag = LocaleTags.systemTag()
        assertTrue(tag.isNotBlank())
        assertEquals(tag, LocaleTags.normalizeAppTag(tag))
    }

    @Test
    fun `the feed's region is the device's when it has one`() {
        assertEquals("IN", LocaleTags.ytGl("en", "IN"))
        assertEquals("DE", LocaleTags.ytGl("en", "de"))
        // Region beats the language's own guess, Chinese included.
        assertEquals("HK", LocaleTags.ytGl(LocaleTags.ZH_HANT, "HK"))
    }

    @Test
    fun `without a usable device region the language decides`() {
        assertEquals("US", LocaleTags.ytGl("en", null))
        assertEquals("US", LocaleTags.ytGl("en", ""))
        assertEquals("US", LocaleTags.ytGl("es", "419"))
        assertEquals("TW", LocaleTags.ytGl(LocaleTags.ZH_HANT, ""))
        assertEquals("CN", LocaleTags.ytGl(LocaleTags.ZH_HANS, null))
    }
}
