package com.music.bitchord.data

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun decomposed(text: String): String =
    (text as NSString).decomposedStringWithCanonicalMapping
