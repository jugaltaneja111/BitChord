package com.music.bitchord.ios.sources

import com.music.bitchord.data.sources.StreamFormat
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHDMI
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortLineOut
import platform.AVFAudio.AVAudioSessionPortUSBAudio
import platform.AVFAudio.currentRoute

/**
 * What this phone's player can actually decode, decided before a URL is handed
 * to it.
 *
 * The phone's `DeviceCodecs` asks one question — whether a Dolby decoder ships
 * with this particular vendor image — because on Android everything else a
 * source returns is in the platform's own set. On iOS the question is the other
 * way round. Every iPhone has the same decoders, Dolby included, and the gap is
 * in the *ordinary* codecs: AVPlayer has no Opus and no Vorbis, and no WebM or
 * Ogg demuxer to find them in. The phone plays those all day — YouTube's best
 * rendition is Opus — so a source handing one over is routine, not exotic.
 *
 * And it matters for the same reason it matters on Android: the player is the
 * wrong place to find out. AVPlayer fails an undecodable item as a generic
 * "cannot open", by which point the resolver has already chosen it over a
 * stream that would have played. Refusing it where the source answers lets
 * [SourceResolver] step to the next candidate, the next source, and finally to
 * YouTube without the listener hearing anything go wrong.
 *
 * ### What plays
 *
 * AVPlayer: AAC and HE-AAC (in MP4/M4A, or ADTS), ALAC, FLAC, MP3, WAV and
 * AIFF, and E-AC-3 including the JOC extension that carries Atmos, in MP4 or
 * HLS. The FFmpeg player (iosKit/…/ffmpeg) takes what AVPlayer doesn't: Opus
 * and Vorbis, WebM and Ogg, and DASH manifests (marked by [AddonSource], not
 * refused). What neither decodes — APE, WavPack, DSD — is refused here.
 */
internal object DeviceCodecs {

    /**
     * Whether an E-AC-3 (JOC) stream would find a decoder here.
     *
     * Always true on iOS: Dolby Digital Plus is part of the system, not a
     * vendor add-on, and AVPlayer renders the JOC objects as Atmos on the
     * iPhones that do spatial audio and as the 5.1 core downmixed everywhere
     * else — a lesser render but not a failure, the same allowance the phone
     * makes for a plain E-AC-3 decoder. Whether the listener *wants* Atmos is a
     * separate switch — see [SourcePrefs.dolbyAtmos].
     */
    val playsDolbyAtmos: Boolean get() = true

    /**
     * Whether AVPlayer can decode [format], as far as [format] says.
     *
     * An unknown codec is a yes, for the same reason [StreamFormat.isLossless]
     * answering "unknown" is information and not a no: a source that declined
     * to describe its stream has not been shown to be unplayable, and the URL's
     * own extension has already been read for a codec by the time this runs
     * (see `AddonSource.formatOf`). A *named* codec outside the set is the case
     * this exists for, and is refused.
     */
    fun decodes(format: StreamFormat): Boolean {
        val codec = format.codec?.lowercase() ?: return true
        if (codec in UNDECODABLE) return false
        if (codec in DECODABLE || codec in FFMPEG) return true
        // A spelling nobody listed. Recognisable Opus/Vorbis/WebM in a longer
        // string ("audio/webm; codecs=opus") is still a no; anything else is
        // given the benefit of the doubt, like a null.
        return UNDECODABLE.none { it in codec }
    }

    /**
     * Codecs and self-describing containers AVPlayer plays. `mp4`, `m4a` and
     * `aac` are in it because on every source this app speaks to they mean AAC
     * in an MP4 envelope — JioSaavn's `_320.mp4`, an addon's `"format": "mp4"`.
     */
    private val DECODABLE = setOf(
        "aac", "he-aac", "m4a", "mp4", "alac", "flac", "mp3", "wav", "aiff",
        "eac3", "ec3", "eac3-joc", "ec3-joc", "dolby-atmos",
    )

    /** What the FFmpeg player decodes and AVPlayer doesn't. */
    private val FFMPEG = setOf("opus", "vorbis", "ogg", "oga", "webm", "mka")

    /** What neither player decodes, by name. */
    private val UNDECODABLE = setOf("ape", "wv", "wavpack", "dsf", "dff", "dsd")
}

/**
 * Whether the audio is leaving this iPhone by a route that can carry lossless
 * audio — the question an addon's `checkValidLossless` asks.
 *
 * The phone's `LosslessOutput`, reduced to what iOS can answer. A cable is the
 * only yes: the headphone adapter, a USB DAC or interface, HDMI, or a line out.
 * Bluetooth is never one on iOS — every Bluetooth route is AAC whatever the
 * headphones are, so there is no LDAC or aptX Lossless to look for, and no
 * codec permission to ask for either (the phone's "Check Bluetooth codec" row
 * has nothing to do here). AirPlay and CarPlay are left out, as the phone
 * leaves out casting: their real codec is not ours to read.
 *
 * Read on demand rather than observed: the route changes with every pair of
 * headphones, and the two places that ask — the source walk, and the Sources
 * page when it is drawn — both want the answer as it stands at that moment.
 */
internal object LosslessOutput {

    val capable: Boolean
        get() = runCatching {
            AVAudioSession.sharedInstance().currentRoute.outputs.any {
                (it as? AVAudioSessionPortDescription)?.portType in CABLED
            }
        }.getOrDefault(false)

    /** What the audio is going to now, for the Sources page's "needs a lossless output" line. */
    val routeName: String?
        get() = runCatching {
            (AVAudioSession.sharedInstance().currentRoute.outputs.firstOrNull() as? AVAudioSessionPortDescription)
                ?.portName
        }.getOrNull()

    private val CABLED = setOf(
        AVAudioSessionPortHeadphones,
        AVAudioSessionPortUSBAudio,
        AVAudioSessionPortHDMI,
        AVAudioSessionPortLineOut,
    )
}
