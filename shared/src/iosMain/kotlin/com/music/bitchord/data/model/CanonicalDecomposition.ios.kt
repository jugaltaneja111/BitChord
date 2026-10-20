package com.music.bitchord.data.model

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

internal actual fun canonicalDecomposition(text: String): String =
    NSString.create(string = text).decomposedStringWithCanonicalMapping
