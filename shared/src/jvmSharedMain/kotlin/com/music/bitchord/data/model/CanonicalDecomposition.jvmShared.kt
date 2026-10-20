package com.music.bitchord.data.model

import java.text.Normalizer

internal actual fun canonicalDecomposition(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
