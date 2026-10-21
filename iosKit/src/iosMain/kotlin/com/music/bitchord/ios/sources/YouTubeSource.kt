package com.music.bitchord.ios.sources

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.StreamFormat

/**
 * YouTube Music, wrapped so it can be ordered alongside everything else.
 *
 * A thin adapter over machinery that already exists and stays where it is:
 * [InnerTubeXResolver] keeps its client walk and its refusal handling, and
 * nothing about how a YouTube track resolves changes by virtue of this class.
 * What changes is that YouTube is *one of* the sources rather than the
 * assumption underneath all of them.
 *
 * Tracks keep their bare video ids rather than being wrapped in a
 * [SourceRegistry.trackKey]. Half the app knows what a YouTube video id is —
 * the like button, the lyrics lookup keyed on it, the radio endpoint, the
 * canvas lookup, the scrobbler — and re-keying them here would break every one
 * of those for no gain.
 */
internal class YouTubeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.YOUTUBE
    override val displayName: String get() = config.label.ifBlank { SourceKind.YOUTUBE.label }

    /**
     * Always Ok. There is no cheap probe worth making: the endpoints that would
     * answer are the same ones a search is about to hit anyway, and a failing
     * YouTube shows up as a failed resolve with a real reason attached rather
     * than as a red line on a settings row.
     */
    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    /**
     * [waitForAll] is moot: there is one endpoint here, and it is always waited
     * for. So is [request] — YouTube publishes one set of rows and the tier is
     * decided per stream, long after this.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> =
        YtMusicRepository.search(query, SearchFilter.SONGS)
            .getOrDefault(emptyList())
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .take(limit)

    /**
     * Always AAC in MP4, whatever [request] says.
     *
     * The phone asks for YouTube's best rendition, which is Opus in WebM, and
     * AVPlayer decodes neither — so on iOS this is the same call the player
     * already makes for every YouTube track, `requireM4a = true`, which takes
     * the best AAC-in-MP4 rung (itag 140 at 128kbps, or 141 at 256 where the
     * account reaches it). [StreamRequest.Lossless] has no answer on this
     * source at all, which is why [SourceKind.YOUTUBE] declares
     * `canServeLossless = false`.
     *
     * The headers come back with the URL because googlevideo binds the stream
     * to the request that minted it; the player has to send them.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val stream = InnerTubeXResolver.extract(trackId, maxKbps = 320, requireM4a = true) ?: return null
        return SourceStream(
            url = stream.url,
            format = StreamFormat(codec = "aac", kbps = stream.kbps.takeIf { it > 0 }),
            headers = stream.headers,
        )
    }
}
