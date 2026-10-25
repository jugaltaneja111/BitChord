package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.music.bitchord.data.IosAppLanguage

/**
 * [content] in the language picked in Settings › App language, rebuilt when it changes. The shared
 * screens' resources follow `NSLocale.preferredLanguages`, which reads the `AppleLanguages` the
 * setting writes as soon as it is written (Compose's own recipe for an in-app language on iOS);
 * iosKit's words read the setting itself. Either way only a fresh composition asks again.
 */
@Composable
fun ProvideAppLanguage(content: @Composable () -> Unit) {
    val tag by IosAppLanguage.chosen.collectAsState()
    key(tag) { content() }
}
