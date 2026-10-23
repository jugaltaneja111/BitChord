package com.music.bitchord.ios

import platform.Foundation.NSBundle

/**
 * iosKit's own words in the app's language. The shared screens carry theirs in Compose resources;
 * these are iOS's own Localizable.strings (iosApp/Localization), keyed by the English itself, so
 * iOS picks the language exactly as it does for the rest of the app — Settings › App language, or
 * the iPhone's — and an English key with no translation reads as it is.
 *
 * [args] fill `{0}`, `{1}`… in order, after the lookup, so a translation can move them around.
 * Plain, not @Composable: toasts, errors and labels made away from any screen need it too.
 */
internal fun tr(english: String, vararg args: Any?): String {
    val text = NSBundle.mainBundle.localizedStringForKey(english, value = english, table = null)
    if (args.isEmpty()) return text
    var out = text
    args.forEachIndexed { i, arg -> out = out.replace("{$i}", arg.toString()) }
    return out
}

/**
 * A count's words in the app's language: [one] or [other] (English, `{0}` for [formatted]) picked
 * by the language's own plural rule, then looked up as [tr] does. Russian and Polish have a few and
 * a many form as well, stored under [other] with `|few` and `|many` after it.
 */
internal fun trPlural(count: Long, one: String, other: String, formatted: String = count.toString(), vararg more: Any?): String {
    val form = when (pluralCategory(count)) {
        Plural.ONE -> one
        Plural.FEW -> "$other|few"
        Plural.MANY -> "$other|many"
        Plural.OTHER -> other
    }
    val text = NSBundle.mainBundle.localizedStringForKey(form, value = null, table = null)
        .takeIf { it != form }
        // An English form, or a language with no few/many entry, reads its other.
        ?: NSBundle.mainBundle.localizedStringForKey(if (form == one) one else other, value = if (form == one) one else other, table = null)
    var out = text.replace("{0}", formatted)
    more.forEachIndexed { i, arg -> out = out.replace("{${i + 1}}", arg.toString()) }
    return out
}

internal fun trPlural(count: Int, one: String, other: String, formatted: String = count.toString(), vararg more: Any?): String =
    trPlural(count.toLong(), one, other, formatted, *more)

private enum class Plural { ONE, FEW, MANY, OTHER }

/** CLDR's cardinal rule for the language the app is showing, for whole numbers. */
private fun pluralCategory(n: Long): Plural {
    val language = (NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String).orEmpty().substringBefore('-')
    val mod10 = n % 10
    val mod100 = n % 100
    return when (language) {
        "ja", "zh", "th", "vi", "id" -> Plural.OTHER
        "fr", "pt", "hi" -> if (n == 0L || n == 1L) Plural.ONE else Plural.OTHER
        "ru" -> when {
            mod10 == 1L && mod100 != 11L -> Plural.ONE
            mod10 in 2..4 && mod100 !in 12..14 -> Plural.FEW
            else -> Plural.MANY
        }
        "pl" -> when {
            n == 1L -> Plural.ONE
            mod10 in 2..4 && mod100 !in 12..14 -> Plural.FEW
            else -> Plural.MANY
        }
        else -> if (n == 1L) Plural.ONE else Plural.OTHER
    }
}
