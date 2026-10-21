package com.music.bitchord.data

/** [text] in Unicode canonical decomposition (NFD): "é" becomes "e" and a combining accent. */
internal expect fun decomposed(text: String): String
