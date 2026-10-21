package com.music.bitchord.data

import java.text.Normalizer

internal actual fun decomposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
