package com.music.bitchord.data.model

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping

internal actual fun canonicalDecomposition(text: String): String =
    NSString.create(string = text).decomposedStringWithCanonicalMapping

internal actual fun compatibilityComposition(text: String): String =
    NSString.create(string = text).precomposedStringWithCompatibilityMapping
