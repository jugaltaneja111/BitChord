package com.music.bitchord.ios

import com.music.bitchord.data.IosAppLanguage
import platform.Foundation.NSBundle

/**
 * iosKit's own words in the app's language. The shared screens carry theirs in Compose resources;
 * these are iOS's own Localizable.strings (iosApp/Localization), keyed by the English itself, and an
 * English key with no translation reads as it is. The language is Settings › App language's, read
 * from the setting (see [IosAppLanguage]) and looked up in that language's own `.lproj`, so it
 * doesn't wait on iOS taking `AppleLanguages` in at the next launch; else the iPhone's.
 *
 * [args] fill `{0}`, `{1}`… in order, after the lookup, so a translation can move them around.
 * Plain, not @Composable: toasts, errors and labels made away from any screen need it too.
 */
internal fun tr(english: String, vararg args: Any?): String {
    val text = strings().localizedStringForKey(english, value = english, table = null)
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
    val bundle = strings()
    val text = bundle.localizedStringForKey(form, value = null, table = null)
        .takeIf { it != form }
        // An English form, or a language with no few/many entry, reads its other.
        ?: bundle.localizedStringForKey(if (form == one) one else other, value = if (form == one) one else other, table = null)
    var out = text.replace("{0}", formatted)
    more.forEachIndexed { i, arg -> out = out.replace("{${i + 1}}", arg.toString()) }
    return out
}

internal fun trPlural(count: Int, one: String, other: String, formatted: String = count.toString(), vararg more: Any?): String =
    trPlural(count.toLong(), one, other, formatted, *more)

private enum class Plural { ONE, FEW, MANY, OTHER }

private var stringsFor: Pair<String?, NSBundle>? = null

/**
 * The bundle [tr] reads: the chosen language's `.lproj` ("zh-Hant", else just "hi" of "hi-IN"),
 * or the app's own, which iOS localises into the iPhone's language. English has no `.lproj`: its
 * keys are its words.
 */
private fun strings(): NSBundle {
    val tag = IosAppLanguage.tag()
    stringsFor?.let { (cached, bundle) -> if (cached == tag) return bundle }
    val main = NSBundle.mainBundle
    val bundle = tag?.let {
        listOf(it, it.substringBefore('-')).firstNotNullOfOrNull { name ->
            main.pathForResource(name, ofType = "lproj")?.let { path -> NSBundle.bundleWithPath(path) }
        } ?: if (it.substringBefore('-') == "en") englishOnly else null
    } ?: main
    stringsFor = tag to bundle
    return bundle
}

/** A bundle with no strings, so English chosen on a phone in another language reads the keys. */
private val englishOnly: NSBundle by lazy {
    NSBundle.bundleWithPath(platform.Foundation.NSTemporaryDirectory()) ?: NSBundle.mainBundle
}

/** CLDR's cardinal rule for the language the app is showing, for whole numbers. */
private fun pluralCategory(n: Long): Plural {
    val language = (IosAppLanguage.tag() ?: NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String)
        .orEmpty().substringBefore('-')
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
