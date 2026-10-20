package com.music.bitchord.data

/** The device's (or app's) current locale as a BCP 47 tag, e.g. "en-US". May be empty. */
internal expect fun systemLanguageTag(): String

/** The device's region as an ISO 3166 country code ("IN"), or "" when none is set. */
internal expect fun systemRegion(): String

/**
 * The primary language subtag of [tag], lowercased ("en-US" -> "en"), or "" when [tag] is not a
 * well-formed language tag.
 */
expect fun primaryLanguage(tag: String): String
