package com.music.bitchord.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSUserDefaults

/**
 * The language picked in Settings › App language, or null to follow the iPhone's — read from the
 * setting itself rather than from what iOS made of `AppleLanguages` at launch, so every screen
 * (iosKit's words, the shared screens' resources, YouTube's own text) agrees on it and a change
 * applies at once, without reopening the app.
 */
object IosAppLanguage {
    /** The App language setting's key, as iosKit's settings store it. */
    const val KEY = "bitchord.settings.app_language"

    private val _chosen = MutableStateFlow(read())
    val chosen: StateFlow<String?> = _chosen

    /** The chosen tag ("hi", "zh-Hant"), or null for the iPhone's language. */
    fun tag(): String? = _chosen.value

    /** Called when the setting changes. */
    fun changed() {
        _chosen.value = read()
    }

    private fun read(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY)?.ifBlank { null }
}
