package com.music.bitchord.ios.sources

import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults

/**
 * The quality vocabulary and the URL checks the phone keeps in `ModuleSource`'s
 * companion, lifted out on their own.
 *
 * On the phone they live there for history's sake — the module source came
 * first and the addon source borrowed them — and the module source is not
 * ported (see [SourceKind]). Everything here is what [AddonSource],
 * [JioSaavnSource] and [SourceResolver] still need from it.
 */
internal object StreamChecks {

    /**
     * The three tiers every compatible catalogue speaks, whatever it calls
     * them on the way out: `LOSSLESS`, `FLAC 16-bit / 44.1kHz` and `hires-96`
     * are one tier; `320kbps` and `HIGH` are another. Stored on a row as
     * [Song.sourceQuality][com.music.bitchord.data.model.Song.sourceQuality].
     */
    const val LOSSLESS = "LOSSLESS"
    const val HIGH = "HIGH"
    const val LOW = "LOW"

    /**
     * A row that is the immersive mix, whatever bitrate label it carries.
     *
     * Deliberately not a rung on the same ladder as the other three. Atmos is a
     * different *mix*, not a better or worse copy of the same one, and the
     * catalogues that publish it do not agree on where it sits: Tidal files its
     * immersive rows under `audioQuality: LOW`, which read as a bitrate would
     * rank the mix a listener switched Atmos on for below every stereo row in
     * the list. Tagging it as what it is lets the one place that decides —
     * [SourceResolver.preferred] — ask the listener's preference instead of a
     * number.
     */
    const val DOLBY = "DOLBY"

    /** Below this, a capped connection is better served by a catalogue's own small tier. */
    const val LOW_CEILING_KBPS = 128

    /**
     * Which tier a free-text quality label describes, or null when it doesn't
     * describe one.
     *
     * Order matters: `FLAC 16-bit / 44.1kHz` contains a bit depth *and* a
     * codec, and it is the codec that settles it.
     */
    fun qualityTier(label: String): String? {
        val text = label.uppercase()
        return when {
            text.isBlank() -> null
            LOSSLESS_HINTS.any { it in text } -> LOSSLESS
            LOW_HINTS.any { it in text } -> LOW
            HIGH_HINTS.any { it in text } -> HIGH
            else -> null
        }
    }

    private val LOSSLESS_HINTS =
        listOf("LOSSLESS", "FLAC", "ALAC", "HI-RES", "HI_RES", "HIRES", "24-BIT", "16-BIT", "WAV")
    private val LOW_HINTS = listOf("LOW", "128", "96KBPS", "64")
    private val HIGH_HINTS = listOf("HIGH", "320", "MP3", "AAC", "M4A", "OPUS", "OGG")

    /**
     * Whether a stream URL is one no server could answer, decided without
     * asking one.
     *
     * Two halves, both from the phone. The first is the gate the player itself
     * will apply, asked here where refusing is free: on Android that is
     * OkHttp's own parser, and on iOS it is [NSURL]'s, which is what AVPlayer is
     * handed — a string `NSURL` refuses never becomes a request at all.
     * Restricting it to http(s) is not unwelcome: a catalogue cannot hand the
     * player `file:///…` and have it read local storage on the catalogue's
     * behalf.
     *
     * The second catches a URL that parses and is dead anyway: one carrying a
     * second copy of its own origin, which is what a concatenation against the
     * wrong base produces —
     *
     * ```
     *   https://sp-ad-fa.audio.tidal.com/mediatracks/<blob>/https://sp-ad-fa.audio.tidal.com/…
     * ```
     *
     * — seen on the phone in August 2026, and answered 404 every time.
     * Deliberately narrower than "contains two schemes": a URL that passes a
     * target to a proxy holds two schemes and is perfectly good. Repair is
     * possible and deliberately not done: reconstructing the intent behind
     * somebody else's broken output is a liability the app would then own
     * forever, and the price of refusing is one quality tier on affected tracks
     * instead of silence.
     */
    fun malformed(url: String): Boolean {
        val parsed = NSURL.URLWithString(url) ?: return true
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return true
        if (parsed.host.isNullOrBlank()) return true
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return false
        val originEnd = url.indexOf('/', schemeEnd + 3)
        if (originEnd < 0) return false
        return url.indexOf(url.substring(0, originEnd), originEnd) >= 0
    }

    /**
     * Whether a rendition a source has just handed over is one that must not be
     * played: an Atmos mix the listener has switched off, or a codec AVPlayer
     * cannot decode at all — see [DeviceCodecs].
     *
     * Caught *here*, where the source answers, rather than by ranking it lower
     * in [SourceResolver]: an Atmos rendition — or an Opus one — is routinely
     * the only one a catalogue will give for a track, so there is no
     * lower-ranked sibling of it to fall to and a ranking has nothing to choose
     * between. Refusing is what lets the resolver move to a source that can be
     * heard.
     */
    fun unplayable(format: StreamFormat, atmosAllowed: Boolean): Boolean =
        (format.isDolbyAtmos && !atmosAllowed) || !DeviceCodecs.decodes(format)
}

/**
 * The one source setting the phone keeps in `AppSettings` that the source walk
 * itself reads: whether an immersive mix is wanted.
 *
 * Kept in this package's own defaults keys rather than among the app's
 * settings, so that a later Settings row can bind to it without this package
 * reaching into anyone else's file.
 *
 * Off by default on iOS, where the phone defaults it on. AVPlayer plays E-AC-3
 * JOC, but the gain is a different mix, not a better copy, and on most of what
 * an iPhone is listened through — AirPods aside — Atmos arrives as the 5.1 core
 * folded down to two channels, which is a worse stereo mix than the stereo one.
 * Switched on, the phone's rules apply unchanged: an Atmos row is preferred, an
 * addon is offered `?atmos=auto`, and an Atmos answer outranks a FLAC.
 */
internal object SourcePrefs {
    private const val KEY_DOLBY_ATMOS = "bitchord.sources.dolby_atmos"
    private val defaults = NSUserDefaults.standardUserDefaults

    private val _dolbyAtmos = MutableStateFlow(
        if (defaults.objectForKey(KEY_DOLBY_ATMOS) != null) defaults.boolForKey(KEY_DOLBY_ATMOS) else false,
    )
    val dolbyAtmos: StateFlow<Boolean> = _dolbyAtmos.asStateFlow()

    fun setDolbyAtmos(value: Boolean) {
        defaults.setBool(value, KEY_DOLBY_ATMOS)
        _dolbyAtmos.value = value
    }

    /** Both gates at once: the device can, and the listener wants it. */
    val atmosWanted: Boolean get() = DeviceCodecs.playsDolbyAtmos && _dolbyAtmos.value
}
