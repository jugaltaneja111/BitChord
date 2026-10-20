package com.music.bitchord.data.model

/** Unicode canonical decomposition (NFD) of [text]: "é" becomes "e" plus a combining accent. */
internal expect fun canonicalDecomposition(text: String): String
