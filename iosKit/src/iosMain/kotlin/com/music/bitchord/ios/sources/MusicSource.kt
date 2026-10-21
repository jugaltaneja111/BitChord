package com.music.bitchord.ios.sources

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.StreamFormat

/**
 * A URL the player can open, and what is expected to come back out of it.
 *
 * [headers] travel with the fetch because some sources bind the stream URL to
 * the request that asks for it — YouTube's googlevideo URLs are the case in
 * point, which is why [InnerTubeXResolver][com.music.bitchord.data.innertube.InnerTubeXResolver]
 * hands its own back with every stream. Sources that don't care leave it empty.
 *
 * The phone's `SourceStream`, ported as it stands.
 */
internal data class SourceStream(
    val url: String,
    val format: StreamFormat = StreamFormat(),
    /** A DASH manifest rather than a file: AVPlayer can't open one, so the FFmpeg player does. */
    val dash: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /**
     * Whether this is less than was asked for, taken because nothing better
     * turned up in time.
     *
     * Playing it is the right call — a track that plays at 128kbps beats a
     * track that doesn't play — but it is worth knowing, because the reason is
     * usually a catalogue that was slow rather than a catalogue that was
     * missing, and the same question asked again during playback gets a better
     * answer often enough to be worth asking. (The phone's `QualityUpgrade`
     * does that second look; the iOS player does not yet.)
     */
    val belowRequest: Boolean = false,
    /**
     * How long the catalogue says this recording is, when it says.
     *
     * Carried so that a stream found for a track that is *already playing* can
     * be checked against the length the decoder reports before it is cut in.
     * The match that produced this stream was made on a duration claimed by
     * whoever queued the track, which is not the same evidence: measured on the
     * phone, a 163s track was matched to a 189s cut of the same song under the
     * same title, and the only place that was catchable before the audio broke
     * was against the runtime being played.
     */
    val durationSec: Int? = null,
    /**
     * Which [MusicSource.configId] this came from, when the caller knows.
     *
     * Set by [SourceResolver] once a source has actually answered — never by a
     * source itself, since it has no reason to know its own id.
     */
    val sourceConfigId: String? = null,
)

/**
 * How much of the stream the caller is willing to pay for.
 *
 * Not a quality *setting* — the setting is the player's, and is turned into one
 * of these per request by the caller (see [IosSources.requestFor]), because the
 * answer depends on the connection in hand at the moment a track starts, not on
 * what was chosen in Settings an hour ago on a different network.
 *
 * Which sources a request reaches is decided from it too — see
 * [SourceRegistry.activeForPlayback], the iOS stand-in for the phone's
 * `AudioQuality.permits`:
 *
 *  - [Lossless]: every enabled source.
 *  - [Best]: the lossy sources and YouTube — the phone's High rung, where an
 *    addon fronting several catalogues is seconds spent to land on a transcode
 *    JioSaavn already has at 320.
 *  - [Capped]: YouTube alone — the phone's Medium and Low rungs.
 */
internal sealed interface StreamRequest {
    /** Bit-exact, whatever it costs. Sources that can't do it should say so rather than substitute. */
    data object Lossless : StreamRequest

    /** A transcode at or below [maxKbps]. What a metered connection asks for. */
    data class Capped(val maxKbps: Int) : StreamRequest

    /** Whatever the source considers its best lossy rendition. */
    data object Best : StreamRequest
}

/**
 * Whether a source is reachable and usable, asked before it is trusted with a
 * track.
 *
 * The distinction between [Unreachable] and [Rejected] is the whole point:
 * a server that is down is worth retrying and worth leaving enabled, and one
 * that refused the credentials is neither. Both look identical at the call
 * site otherwise, and a sources page that renders them the same way sends
 * people to re-type a password that was never wrong.
 */
internal sealed interface SourceHealth {
    data class Ok(val detail: String? = null) : SourceHealth
    data class Unreachable(val reason: String) : SourceHealth
    data class Rejected(val reason: String) : SourceHealth

    val isOk: Boolean get() = this is Ok
}

/**
 * One place tracks can come from.
 *
 * Deliberately small: a source is asked to search its own catalogue and to turn
 * one of its own track ids into something openable. It is not asked for home
 * feeds, related tracks or radio — those stay with YouTube, which is the only
 * source that has them, and a source interface wide enough to express them
 * would be an interface only one implementation could ever satisfy.
 *
 * Implementations are constructed by [SourceRegistry] from a stored
 * [SourceConfig] and are expected to be cheap to build and safe to hold: no
 * connections are opened until something is actually asked for.
 *
 * All three methods may throw; [SourceResolver] and the Sources page are the
 * only callers, and both treat a throw as "this one didn't work" rather than
 * letting it reach the listener as a crash.
 */
internal interface MusicSource {

    /** The configured instance this was built from — unique across the registry. */
    val configId: String

    val kind: SourceKind

    /** What the user named it, falling back to the server's own hostname. */
    val displayName: String

    /** Reachability and credentials, for the Sources page and for skipping dead sources. */
    suspend fun health(): SourceHealth

    /**
     * Tracks matching [query], as [Song]s already tagged with this source's id
     * so that playing one comes back here — see [SourceRegistry.trackKey].
     *
     * @param waitForAll whether every backend this source fans out to is worth
     *   waiting for. Kept for the shape of the phone's interface; none of the
     *   sources iOS has fans out, so none of them reads it.
     * @param request the stream this search is being made on behalf of, when it
     *   is being made on behalf of one. A catalogue that describes its rows by
     *   tier answers a different question at each one, and a search made to find
     *   something to play at 128kbps should not be ranked on FLAC rows the
     *   caller is never going to ask for. Null is a search nobody is about to
     *   stream from: the listener typing in the search box, where the best the
     *   catalogue has is exactly what should be shown.
     */
    suspend fun search(
        query: String,
        limit: Int = 25,
        waitForAll: Boolean = false,
        request: StreamRequest? = null,
    ): List<Song>

    /**
     * @param trackId this source's own id for the track, as issued by [search].
     * @return an openable stream, or null when this source turns out not to
     *   have the track after all — which is a miss, not an error, and lets
     *   [SourceResolver] move to the next source without logging a failure.
     */
    suspend fun stream(trackId: String, request: StreamRequest): SourceStream?
}
