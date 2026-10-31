package com.music.bitchord.ios.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a queued track into an openable stream, using whichever source can
 * best serve it.
 *
 * Two things happen here that don't happen in any single [MusicSource]:
 *
 *  1. **The quality question is answered once.** On the phone this class reads
 *     the connection's ceiling itself (`requestForNow`); on iOS the caller
 *     hands the answer in as a [StreamRequest], and every walk here uses that
 *     one value. Sources are told what to serve; they don't each re-derive it.
 *
 *  2. **The order is applied.** A track is pinned to the source that produced
 *     it, but a pin is a starting point, not a cage: with lossless asked for, a
 *     higher-priority source that can serve the same recording bit-exact gets
 *     asked first, and a source that fails gets stepped over rather than
 *     failing the track.
 *
 * Whether another source *has* the same recording is [TrackMatcher]'s question,
 * not this one's — the same shared matcher the phone uses, so the library, a
 * playlist, radio and search all substitute on identical terms on both.
 *
 * Ported from the phone's `SourceResolver` without its download walk
 * (`forDownload`) and its mid-playback second look (`upgradeFor`), neither of
 * which the iOS player has a caller for yet; the rules they share with the
 * live path — [isBetter], [worthSwapping], [sameRecordingAs] — are here.
 */
internal object SourceResolver {

    private const val TAG = "BitChord"

    /**
     * `mix` on an addon row: which mix the row itself was, [MIX_ATMOS] or
     * [MIX_STEREO] as its search answer tagged it, or [MIX_ANY] when it said
     * neither. On the phone it rides on the playback URI; on iOS
     * [IosSources.resolve] reads the same fact off the [Song].
     *
     * Present for every addon row and nothing else. Those reach the queue one
     * way — picked by hand from the addon search, then replayed from history or
     * a playlist — and the marker makes that play strict: the row the listener
     * chose, never its Atmos sibling, another source's better copy, or
     * YouTube's AAC.
     */
    const val MIX_STEREO = "stereo"
    const val MIX_ATMOS = "atmos"
    const val MIX_ANY = "any"

    /** Which mix an addon row is, from the tier its search answer gave it — see [MIX_STEREO]. */
    fun mixOf(song: Song): String = when (song.sourceQuality) {
        null -> MIX_ANY
        StreamChecks.DOLBY -> MIX_ATMOS
        else -> MIX_STEREO
    }

    /**
     * @param target is what a cross-source match is made on. Without it the
     *   only possible behaviour is "the pinned source or nothing", which is
     *   still a correct outcome — just a worse one.
     * @param pickedMix the row's mix when it is an addon row played as itself —
     *   see [MIX_STEREO]. Null for every other source.
     * @return the source that answered alongside its stream, or null when
     *   nothing enabled could serve the track.
     */
    suspend fun resolve(
        configId: String,
        trackId: String,
        target: TrackMatcher.Target,
        request: StreamRequest,
        pickedMix: String? = null,
    ): Pair<MusicSource, SourceStream>? {
        val active = SourceRegistry.activeForPlayback(request)
        // A pin identifies where the row originally came from; it does not
        // override the source switch. Resolving the pin only from the enabled
        // list means Off is off for both newly matched tracks and source-backed
        // rows queued earlier.
        val pinned = active.firstOrNull { it.configId == configId }

        // The upgrade path: with lossless asked for and the pinned source
        // unable to serve it, anything ranked above it that can is worth
        // asking first. This is the whole reason the list is ordered — it is
        // what makes "my own FLAC of this, if I have one, else stream it"
        // expressible.
        // A row picked by hand is not up for substitution — see [MIX_STEREO].
        val picked = pickedMix != null && pinned is AddonSource
        if (!picked && request is StreamRequest.Lossless && pinned?.kind?.canServeLossless != true) {
            for (source in rankedAbove(configId, active)) {
                if (!source.kind.canServeLossless) continue
                val upgraded = matchAndStream(source, target, request) ?: continue
                // Only a genuinely lossless answer is an upgrade. A source that
                // *can* serve lossless but settled for a transcode of this
                // particular track has not beaten the pinned source at anything,
                // and returning its settle-for here would jump ahead of the
                // track's own source, which is the one the user picked.
                if (upgraded.format.isLossless != true) continue
                TrackLog.d(TAG, "lossless upgrade: '${target.title}' served by ${source.displayName}")
                return source to upgraded
            }
        }

        if (pinned != null) {
            attempt(pinned) {
                if (picked) {
                    pinned.stream(trackId, request, stereoOnly = pickedMix == MIX_STEREO)
                } else {
                    pinned.stream(trackId, request)
                }
            }?.let { return pinned to it.copy(sourceConfigId = pinned.configId) }
        }

        // Last resort. A track whose own source is down is still a track the
        // user asked for, and another source having it is not unlikely — this
        // is the difference between a dead server skipping the queue forward
        // and a dead server being invisible.
        //
        // Not YouTube, though, for a row picked from the addon search: its AAC
        // is a lesser copy of a different upload, and the listener chose this
        // addon's precisely to not be hearing it.
        val fallbacks = active.filterNot {
            it.configId == configId || (pickedMix != null && it.kind == SourceKind.YOUTUBE)
        }
        val found = bestAcross(fallbacks, target, request) ?: return null
        TrackLog.d(TAG, "fallback: '${target.title}' served by ${found.first.displayName}")
        return found
    }

    /**
     * The stream for a YouTube track from a source the user ranked above
     * YouTube, or null when none of them has the recording.
     *
     * A YouTube track keeps its bare video id rather than a
     * [SourceRegistry.trackKey] — see [YouTubeSource] for why — so it never
     * passes through [resolve]. Without this, ordering a source above YouTube
     * did nothing for anything *queued* from YouTube: the library, a playlist,
     * radio, the home feed — which is very nearly everything.
     *
     * The match is the same strict one [resolve] uses, for the same reason:
     * this substitutes something else for the track the user picked, and a
     * loose match plays the wrong song under the right title.
     *
     * Every ranked source is asked at once and the first playable answer is
     * taken — see [bestAcross]. This is racing YouTube's own resolve, and a
     * stream that arrives after that race is lost cannot start a track.
     */
    suspend fun substituteForYouTube(
        target: TrackMatcher.Target,
        request: StreamRequest,
    ): Pair<MusicSource, SourceStream>? {
        // Video rows always use YouTube's own stream. A catalogue match is
        // another recording and can be a wrong song altogether.
        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback(request)
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val found = bestAcross(rankedAbove(youtube.configId, active), target, request) ?: return null
        val (source, stream) = found
        // Says what was found, not what the caller will do with it.
        TrackLog.d(
            TAG,
            "substituted: '${target.title}' served by ${source.displayName} over YouTube" +
                " at ${stream.format.summary}" + if (stream.belowRequest) " (below request)" else "",
        )
        return found
    }

    /**
     * The reliable last rung for a source-backed queue item whose chosen stream
     * failed in the player: YouTube's own row for the recording.
     *
     * Unlike [resolve], this asks only YouTube. The failed addon/JioSaavn source
     * must not get another chance to return the identical URL, and unlike
     * [substituteForYouTube] this starts with metadata rather than an existing
     * video id, so it finds the matching YouTube Music row first — with a shared
     * artist required and, when the row knows its runtime, the length held to
     * within [SourcePrefs.lengthSlackSeconds].
     *
     * Returns the *row* rather than its stream, which is where iOS parts from
     * the phone: the iOS player already knows how to play a YouTube video id —
     * its song cache, its read-ahead and its own InnerTubeX call — and a URL
     * minted here would bypass all three.
     */
    suspend fun youtubeFallback(target: TrackMatcher.Target): Song? {
        if (target.title.isBlank() || target.isVideo) return null
        // Not budgeted by a request: whatever rung the stream was played on,
        // YouTube is permitted on it.
        val youtube = SourceRegistry.active().firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        return matchRows(
            source = youtube,
            target = target,
            request = StreamRequest.Best,
            strictLength = target.durationSec != null,
            requireSharedArtist = true,
        )?.firstOrNull()?.also {
            TrackLog.d(TAG, "YouTube fallback matched '${target.title}' after its higher-quality source failed")
        }
    }

    /**
     * The copy of [target] held by a source quick enough to ask about *before*
     * the track is played — or null when no such source is enabled, or none of
     * them has it.
     *
     * The same substitution [substituteForYouTube] makes, moved earlier, and
     * asked only of the [SourceKind.worthPrefetching] sources: this runs
     * speculatively for a track nobody has reached yet, and a slow lookup would
     * still be going when they did.
     *
     * A caller that uses the answer must play *that* stream when the track is
     * reached, rather than re-running the race: a different source winning the
     * second time would put a second file under the cache entry the first one
     * half-filled.
     */
    suspend fun prefetchSubstitute(
        target: TrackMatcher.Target,
        request: StreamRequest,
    ): Pair<MusicSource, SourceStream>? {
        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback(request)
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val quick = rankedAbove(youtube.configId, active).filter { it.kind.worthPrefetching }
        if (quick.isEmpty()) return null
        val found = bestAcross(quick, target, request) ?: return null
        TrackLog.d(TAG, "warmed: '${target.title}' from ${found.first.displayName} at ${found.second.format.summary}")
        return found
    }

    /**
     * A better copy of a track that is already playing, or null (the phone's
     * `upgradeFor`).
     *
     * Every source ranked above YouTube but [servedBy] is asked at once and all
     * of their answers are weighed, held to the strict match — length to within
     * [SourcePrefs.lengthSlackSeconds], a shared artist — because this replaces audio the
     * listener is already hearing. Only a copy [worthSwapping] over [playing]
     * qualifies; an unmeasured [playing] lets only lossless through.
     */
    suspend fun upgradeFor(
        target: TrackMatcher.Target,
        request: StreamRequest,
        playing: StreamFormat?,
        servedBy: String?,
    ): List<Pair<MusicSource, SourceStream>> {
        if (target.title.isBlank() || target.durationSec == null || target.isVideo) return emptyList()
        val active = SourceRegistry.activeForPlayback(request)
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return emptyList()
        val found = rankedAcross(
            rankedAbove(youtube.configId, active).filterNot { it.displayName == servedBy || it.configId == servedBy },
            target,
            request,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
        ) { candidate, stream ->
            worthSwapping(stream.format, playing).also { worth ->
                if (!worth) {
                    TrackLog.d(
                        TAG,
                        "${candidate.displayName}'s ${stream.format.summary} isn't worth swapping " +
                            "'${target.title}' off ${playing?.summary ?: "an unmeasured stream"}",
                    )
                }
            }
        }
        val first = found.firstOrNull() ?: return emptyList()
        TrackLog.d(
            TAG,
            "upgrade found: '${target.title}' at ${first.second.format.summary} from ${first.first.displayName}" +
                found.drop(1).takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "; then ") { "${it.first.displayName} (${it.second.format.summary})" }.orEmpty(),
        )
        return found
    }

    /**
     * Whether anything outranks YouTube under [request] — i.e. whether a
     * YouTube track is worth offering around before it is resolved. Answerable
     * from the source list alone, without a search.
     */
    fun canSubstituteForYouTube(request: StreamRequest): Boolean =
        SourceRegistry.activeForPlayback(request).indexOfFirst { it.kind == SourceKind.YOUTUBE } > 0

    /**
     * Whether [candidate] is enough better than [playing] to be worth the
     * break in the audio that swapping to it costs.
     *
     * Lossless always is. A lossy candidate has to clear [UPGRADE_MIN_GAIN_KBPS]
     * over what is already playing, which is deliberately a wide gap rather
     * than a strict improvement: bitrate compares poorly across codecs, so a
     * margin narrow enough to be codec-sensitive would buy a seam in the audio
     * for nothing.
     */
    fun worthSwapping(candidate: StreamFormat, playing: StreamFormat?): Boolean {
        // Never away from an immersive mix. A FLAC is a better *copy* and a
        // worse *mix* once Atmos is what the listener chose.
        if (playing?.isDolbyAtmos == true && !candidate.isDolbyAtmos) return false
        // Between two lossless copies only a higher resolution earns the seam: the same 16-bit
        // master from another server is no upgrade.
        if (playing?.isLossless == true && candidate.isLossless == true) return higherResolution(candidate, playing)
        if (candidate.isLossless == true || candidate.isDolbyAtmos) return true
        val gain = (candidate.kbps ?: return false) - (playing?.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }

    /**
     * Whether two runtimes are close enough to be the same recording, for a
     * swap into a track that is already playing. Either side being unknown is
     * a no: an unverifiable length is not a length that agrees.
     */
    fun sameRecordingAs(candidateSec: Int?, playingSec: Int?): Boolean {
        if (candidateSec == null || playingSec == null) return false
        return kotlin.math.abs(candidateSec - playingSec) <= SourcePrefs.lengthSlackSeconds.value
    }

    /**
     * The sources ranked above [configId], in order.
     *
     * A config that isn't in [active] ranks last: it is disabled or incomplete,
     * and everything that *is* enabled is worth trying ahead of it.
     */
    private fun rankedAbove(configId: String, active: List<MusicSource>): List<MusicSource> =
        active.indexOfFirst { it.configId == configId }
            .let { if (it < 0) active.size else it }
            .let { active.take(it) }
            // A source that keeps failing waits out its rest rather than holding every track up.
            .filterNot { SourceHealthGate.isResting(it.configId) }

    /**
     * The best stream the configured [sources] can serve for [target] — **all
     * of them asked at once** — or null if none of them has the recording.
     *
     * ### Why they race rather than queue
     *
     * Asking them in rank order is the obvious reading of an ordered list, and
     * it is wrong here, because the sources differ in speed by nearly two orders
     * of magnitude. Measured on the phone, on '9:45':
     *
     * ```
     *   JioSaavn        search 245ms + stream 131ms   ≈ 0.4s
     *   Ricky's Addon   search → settled stream       ≈ 13.5s
     * ```
     *
     * Queued behind the addon, JioSaavn's answer arrives at ~14s, nobody waits
     * that long for a song to start, and YouTube wins every time — while a
     * 320kbps copy sat four tenths of a second away.
     *
     * ### What rank still decides
     *
     * Rank decides who is *asked* — [rankedAbove] is still what builds this list
     * — and it breaks ties between answers that arrive together, since each
     * sweep folds in everything that has already crossed the line and picks the
     * best of them with [isBetter]. What it no longer does is let a slow
     * favourite hold up a fast alternative.
     *
     * Sources still running when an answer is taken are cancelled — the addon
     * client's shared calls let their requests finish and land in its cache
     * regardless. [waitForAll] instead collects every source's answer before
     * choosing.
     */
    internal suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        accept: (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? =
        rankedAcross(sources, target, request, waitForAll, strictLength, requireSharedArtist, accept).firstOrNull()

    /**
     * As [bestAcross], with every accepted answer kept, best first: by [compareQuality], and an
     * equal pair in [sources]' order — the listener's rank, not whichever server was quicker. The
     * rest are what a caller falls back on when the first one's server won't serve it.
     */
    internal suspend fun rankedAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        accept: (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): List<Pair<MusicSource, SourceStream>> = coroutineScope {
        val rank = sources.withIndex().associate { (i, source) -> source.configId to i }
        val order = Comparator<Pair<MusicSource, SourceStream>> { a, b ->
            compareQuality(b.second.format, a.second.format).takeIf { it != 0 }
                ?: ((rank[a.first.configId] ?: Int.MAX_VALUE) - (rank[b.first.configId] ?: Int.MAX_VALUE))
        }
        val running: MutableList<Deferred<Pair<MusicSource, SourceStream?>>> = sources
            .map { source ->
                async {
                    // One source throwing costs that source its answer, not every other source theirs.
                    source to attempt(source) {
                        matchAndStream(source, target, request, waitForAll, strictLength, requireSharedArtist)
                    }
                }
            }
            .toMutableList()
        val accepted = ArrayList<Pair<MusicSource, SourceStream>>()
        val best: () -> Pair<MusicSource, SourceStream>? = { accepted.minWithOrNull(order) }
        // When a lossless copy came in while others were still searching.
        var losslessAt: kotlin.time.TimeMark? = null
        try {
            while (running.isNotEmpty()) {
                // Waiting for every source is so a quick lossy answer can't shut out a slower
                // lossless one. Once a lossless copy is in hand, the rest get a short grace to
                // better it (a 24-bit beside a 16-bit) rather than as long as a dead addon takes
                // to time out, which held a found FLAC back until the song had changed.
                val grace = losslessAt?.let { LOSSLESS_GRACE_MS - it.elapsedNow().inWholeMilliseconds }
                if (grace != null && grace <= 0) {
                    TrackLog.d(TAG, "taking ${best()?.first?.displayName}'s lossless copy of '${target.title}' without waiting on ${running.size} slower source(s)")
                    break
                }
                val first = (if (grace != null) withTimeoutOrNull(grace) { awaitFirst(running) } else awaitFirst(running))
                    ?: continue
                // Anything that crossed the line while that one was being waited
                // on is already sitting there. Folding those in costs no time at
                // all and is what lets rank break a tie between two sources that
                // both answered quickly.
                val ready = listOf(first) + running.filter { it !== first && it.isCompleted }
                running.removeAll(ready.toSet())
                for (done in ready) {
                    val (source, stream) = done.await()
                    if (stream == null) continue
                    if (!accept(source, stream)) continue
                    accepted += source to stream
                }
                if (accepted.isNotEmpty() && !waitForAll) break
                if (losslessAt == null && best()?.second?.format?.let { it.isLossless == true || it.isDolbyAtmos } == true) {
                    losslessAt = kotlin.time.TimeSource.Monotonic.markNow()
                }
            }
        } finally {
            running.forEach { it.cancel() }
        }
        accepted.sortedWith(order)
    }

    private suspend fun <T> awaitFirst(running: List<Deferred<T>>): Deferred<T> = select {
        running.forEach { candidate -> candidate.onAwait { candidate } }
    }

    /**
     * Searches [source] for the recording in [target] and streams it if one of
     * the answers really is that recording — see [TrackMatcher].
     */
    private suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
    ): SourceStream? {
        val matches = matchRows(source, target, request, waitForAll, strictLength, requireSharedArtist)
            ?: return null
        val stream = streamBest(source, matches, target, request) ?: return null
        if (ThrottledHosts.isCooling(stream.url)) {
            TrackLog.d(TAG, "${source.displayName}'s copy of '${target.title}' is on a host rate-limiting this phone; passed over for now")
            return null
        }
        return stream
    }

    /**
     * The rows of [source] that really are [target], best first, or null when
     * none are.
     *
     * Each query the matcher offers is tried in turn, because the first one
     * failing is usually the catalogue disagreeing about how a track is
     * *filed*, not about whether it holds it. A source that *throws* still gets
     * no second chance: that is its server having a problem, and asking it
     * again differently won't fix it.
     *
     * @param strictLength requires a candidate's runtime to agree with
     *   [target]'s to within [SourcePrefs.lengthSlackSeconds]. Only meaningful when the
     *   target *has* a runtime: [TrackMatcher.withinSeconds] answers false for
     *   every candidate against a null one.
     */
    private suspend fun matchRows(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
    ): List<Song>? {
        for (query in TrackMatcher.queries(target)) {
            val candidates = attempt(source) {
                source.search(
                    query,
                    limit = MATCH_CANDIDATES,
                    waitForAll = waitForAll,
                    // The search and the stream are one question here: a
                    // catalogue that files its rows by tier should describe
                    // them at the tier this is about to ask for.
                    request = request,
                )
            } ?: return null
            var matches = TrackMatcher.ranked(candidates, target)
            val ranked = matches.size
            if (requireSharedArtist) {
                matches = matches.filter { TrackMatcher.sharesArtist(target.artist, it.artist) }
            }
            val sharingArtist = matches.size
            // JioSaavn can return different audio under the same title and
            // artist on different releases. With no album on the requested
            // track there is no honest way to choose between those rows;
            // duration is not enough when the wrong recording is only a
            // second away. Treat it as this source missing and retain the
            // known-correct fallback.
            if (source.kind == SourceKind.JIOSAAVN && TrackMatcher.hasConflictingAlbums(matches, target)) {
                val canonical = TrackMatcher.uniquelyMostCreditedCloseMatch(matches, target)
                if (canonical == null) {
                    TrackLog.w(
                        TAG,
                        "${source.displayName} returned conflicting albums for '${target.title}'; refusing to guess",
                    )
                    continue
                }
                TrackLog.d(
                    TAG,
                    "${source.displayName} resolved conflicting albums for '${target.title}' " +
                        "using the uniquely fullest credit: '${canonical.artist}'",
                )
                matches = listOf(canonical)
            }
            // The extra bar for standing in for one specific recording: the
            // replacement has to be the same *length*, to the second or so.
            if (strictLength) {
                matches = matches.filter { TrackMatcher.withinSeconds(it, target, SourcePrefs.lengthSlackSeconds.value) }
            }
            if (matches.isEmpty()) {
                // Why a source had nothing, so a missing upgrade reads as missing, not as silence.
                TrackLog.d(
                    TAG,
                    "${source.displayName}: no match for '${target.title}' by '${target.artist}'" +
                        " (${target.durationSec?.let { "${it}s" } ?: "length unknown"}) from \"$query\": " +
                        when {
                            candidates.isEmpty() -> "no results"
                            ranked == 0 -> "${candidates.size} results, none the same title and artist: " +
                                candidates.take(3).joinToString { "'${it.title}' by '${it.artist}' ${it.durationText ?: "?"}" }
                            sharingArtist == 0 -> "$ranked by other artists"
                            else -> "$sharingArtist the wrong length: " +
                                candidates.take(3).joinToString { "'${it.title}' ${it.durationText ?: "?"}" }
                        },
                )
                continue
            }
            return matches
        }
        return null
    }

    /**
     * The matching rows, in the order they are worth opening.
     *
     * Two rules, and the order of them is the point:
     *
     *  1. **Length decides which recording, first.** When any candidate agrees
     *     with the runtime being asked for to within a couple of seconds, only
     *     the candidates that agree are eligible at all. A catalogue holding the
     *     track under its right title and artist can still be holding a
     *     different *cut* of it, and the runtime is what separates those.
     *
     *  2. **Quality decides between equals, second.** Among rows that are the
     *     same recording, one advertising a lossless copy is the better place
     *     to ask. A declared tier is a reason to prefer one copy of a recording
     *     over another; it is not a reason to play a different recording.
     *
     * The immersive mix goes first, ahead of either tier, when the listener
     * asked for Atmos ([SourcePrefs.atmosWanted]): a catalogue may publish the
     * Atmos mix as its own row, in which case picking the row *is* the only way
     * to hear it. With the switch off — the iOS default — Atmos rows get no
     * preference, and an Atmos answer is refused at the source anyway.
     */
    internal fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
    ): List<Song> {
        // The listener's tolerance already decided which runtimes are the same recording; a fixed
        // three seconds here quietly undid a setting of five.
        val sameLengthSec = maxOf(SAME_RECORDING_SEC, SourcePrefs.lengthSlackSeconds.value)
        val sameLength = matches.filter { TrackMatcher.withinSeconds(it, target, sameLengthSec) }
        val eligible = sameLength.ifEmpty { matches }
        if (SourcePrefs.atmosWanted) {
            val immersiveFirst = eligible.sortedByDescending { it.sourceQuality == StreamChecks.DOLBY }
            if (immersiveFirst.firstOrNull()?.sourceQuality == StreamChecks.DOLBY) return immersiveFirst
        }
        if (!wantsLossless) return eligible
        // Stable, so the confidence order [TrackMatcher.ranked] produced
        // survives inside each tier. Hi-res ahead of CD quality, as the phone ranks them: with one
        // tier for the two, a 24-bit remaster and a 16-bit album cut of the same song came down to
        // whichever the catalogue listed first.
        return eligible.sortedByDescending {
            when {
                it.sourceQuality != StreamChecks.LOSSLESS -> 0
                it.sourceHiRes -> 2
                else -> 1
            }
        }
    }

    /**
     * Opens the best of [matches] that can actually serve [request].
     *
     * What comes back is checked against what was asked for. A catalogue that
     * cannot serve lossless does not always say so; some quietly walk their own
     * fallback chain and hand back a 128kbps MP3 with the right title on it.
     * Reading [StreamFormat] before accepting the URL is what turns that into
     * "this one can't, try the next" instead of into the listener's evening.
     *
     * The under-quality stream is kept rather than dropped: if nothing better
     * exists anywhere, playing the MP3 is still better than skipping the track.
     * It is a floor, not a first choice. (Anything AVPlayer cannot decode never
     * gets this far — the sources refuse it before it is a candidate.)
     */
    private suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        target: TrackMatcher.Target,
        request: StreamRequest,
    ): SourceStream? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = preferred(matches, target, wantsLossless)
        var settleFor: SourceStream? = null
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val opened = attempt(source) { source.stream(trackId, request) } ?: continue
            // The row this URL came from knows how long the recording is; the
            // URL itself doesn't. Carried along — see [SourceStream.durationSec].
            val stream = opened.copy(
                durationSec = TrackMatcher.secondsOf(match.durationText) ?: opened.durationSec,
                sourceConfigId = source.configId,
            )
            val served = stream.format
            if (!wantsLossless || served.isLossless == true || served.isDolbyAtmos || served.statesNothingLossy) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} matched '${match.title}' by '${match.artist}' " +
                        "id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                        "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"} " +
                        "→ ${served.summary}",
                )
                return stream
            }
            TrackLog.d(
                TAG,
                "${source.displayName} offered ${served.summary} for '${match.title}' " +
                    "by '${match.artist}' id='${match.videoId}'; looking further",
            )
            // The floor is the *best* of what was refused, not the first of
            // it: which one the listener ends up on if nothing better exists
            // should not come down to which row happened to be asked first.
            settleFor = betterOf(settleFor, stream.copy(belowRequest = true))
        }
        return settleFor
    }

    /**
     * The copy of [target] to keep as a file, from a configured source, or
     * null for YouTube's own AAC (the phone's `forDownload`).
     *
     * Under [StreamRequest.Lossless] the sources that can serve lossless are
     * walked in rank for a bit-exact (or Dolby Atmos) copy, while the rest
     * race for their best lossy one; under [StreamRequest.Best] everything
     * races on rendition alone. A lossy winner is kept only when it beats
     * YouTube's best AAC. [StreamRequest.Capped] is YouTube's: a source can't
     * be asked for a smaller file.
     *
     * Not budgeted by the playback list: what a download keeps is the download
     * setting's answer, so every enabled source whose manifest allows
     * downloads is asked. A DASH manifest is passed over — it can't be saved
     * as one file here.
     */
    suspend fun forDownload(
        target: TrackMatcher.Target,
        request: StreamRequest,
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        if (target.title.isBlank()) return@coroutineScope null
        val wantsLossless = when (request) {
            is StreamRequest.Lossless -> true
            is StreamRequest.Best -> false
            is StreamRequest.Capped -> return@coroutineScope null
        }
        val active = SourceRegistry.activeForDownload()
        val youtubeId = active.firstOrNull { it.kind == SourceKind.YOUTUBE }?.configId
        val ranked = rankedAbove(youtubeId.orEmpty(), active)
        val strictLength = target.durationSec != null
        val bitExact = if (wantsLossless) ranked.filter { it.kind.canServeLossless } else emptyList()
        val elsewhere = ranked - bitExact.toSet()

        val elsewhereBest = async {
            bestAcross(elsewhere, target, StreamRequest.Best, waitForAll = true, strictLength = strictLength) { source, stream ->
                // Bit-exact audio is over budget on the High rung: that listener picked the smaller file.
                val ok = !stream.dash && (wantsLossless || stream.format.isLossless != true)
                if (!ok) TrackLog.d(TAG, "${source.displayName} offered ${stream.format.summary}; not what this download asked for")
                ok
            }
        }

        var best: Pair<MusicSource, SourceStream>? = null
        for (source in bitExact) {
            val stream = matchAndStream(source, target, request, waitForAll = true, strictLength = strictLength) ?: continue
            if (stream.dash) {
                TrackLog.d(TAG, "${source.displayName} offered a DASH manifest to download; passed over")
                continue
            }
            if (stream.format.isLossless == true || stream.format.isDolbyAtmos) {
                TrackLog.d(TAG, "download: '${target.title}' from ${source.displayName} at ${stream.format.summary}")
                elsewhereBest.cancel()
                return@coroutineScope source to stream
            }
            TrackLog.d(TAG, "${source.displayName} offered ${stream.format.summary} to download; not lossless or Dolby")
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }
        elsewhereBest.await()?.let { (source, stream) ->
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }
        val (winner, chosen) = best ?: return@coroutineScope null
        if ((chosen.format.kbps ?: 0) <= YOUTUBE_BEST_AAC_KBPS) {
            TrackLog.d(TAG, "best offered for '${target.title}' is ${winner.displayName}'s ${chosen.format.summary}; taking YouTube's AAC over that")
            return@coroutineScope null
        }
        TrackLog.d(TAG, "download: '${target.title}' from ${winner.displayName} at ${chosen.format.summary}, over YouTube's AAC")
        winner to chosen
    }

    /**
     * Whether [candidate] is a better rendition than [current], by mix first,
     * codec second and bitrate third. A null [current] is beaten by anything.
     *
     * Immersive first, and ahead of lossless rather than behind it — an Atmos
     * stream is E-AC-3 and so answers `isLossless == false`, and a test in the
     * other order made the Atmos line unreachable. Anything immersive that
     * reaches here has already cleared [StreamChecks.unplayable], so it is a mix
     * the listener switched on.
     */
    internal fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean =
        current == null || compareQuality(candidate, current) > 0

    /**
     * Positive when [a] is the better rendition: mix, then lossless over lossy, then — between two
     * lossless copies — bit depth and sample rate, and between two lossy ones bitrate. A lossless
     * copy's bitrate says nothing about it (it's how well the music compressed), and one addon
     * stating it where another doesn't made a 16-bit FLAC outrank a 24-bit one. Zero is a tie,
     * which the caller breaks by the listener's source order.
     */
    internal fun compareQuality(a: StreamFormat, b: StreamFormat): Int {
        if (a.isDolbyAtmos != b.isDolbyAtmos) return if (a.isDolbyAtmos) 1 else -1
        val aLossless = a.isLossless == true
        if (aLossless != (b.isLossless == true)) return if (aLossless) 1 else -1
        if (aLossless) {
            return compareValuesBy(a, b, { it.bitDepth ?: 0 }, { it.sampleRateHz ?: 0 })
        }
        return (a.kbps ?: 0).compareTo(b.kbps ?: 0)
    }

    /** Whether lossless [candidate] is a higher resolution than lossless [playing]: the one swap between two FLACs. */
    fun higherResolution(candidate: StreamFormat, playing: StreamFormat): Boolean =
        candidate.isLossless == true && playing.isLossless == true && compareQuality(candidate, playing) > 0

    /** The higher-quality of two streams — see [isBetter]. */
    private fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream =
        if (current == null || isBetter(candidate.format, current.format)) candidate else current

    /**
     * Whether a format has said nothing that rules lossless out. Unknown is not
     * the same as lossy, and a source that reports neither a codec nor a
     * bitrate has declined to describe it; a stated bitrate is different —
     * nothing states a bitrate for a FLAC.
     */
    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null

    /**
     * Runs [block], turning any failure into null and a log line.
     *
     * Every call into a source is a call to somebody else's server, and a
     * source that throws must cost the *source* its turn, not the track its
     * playback. Cancellation is re-thrown: that is the caller giving up.
     */
    private suspend fun <T> attempt(source: MusicSource, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "${source.displayName} failed: ${e::class.simpleName}: ${e.message}")
        null
    }

    /**
     * How many answers per query are worth weighing. Wider than a
     * well-behaved catalogue needs, because [TrackMatcher] scores the whole
     * list: a backend that ranks the karaoke version above the album cut still
     * has the album cut in here somewhere.
     */
    private const val MATCH_CANDIDATES = 15

    /**
     * How many of the matching rows are worth actually opening. Each one is a
     * round trip to a stream endpoint — enough to get past a backend that is
     * down, not enough to spend a listener's patience walking a result list.
     */
    private const val STREAM_ATTEMPTS = 3


    /**
     * How close two runtimes have to be to be the same cut of a song: wide
     * enough for a catalogue rounding, narrow enough to separate the album
     * track from the DJ edit sitting next to it under the same name.
     */
    private const val SAME_RECORDING_SEC = 3

    /** How many kbps a lossy stream has to gain before it earns a seam in the audio — see [worthSwapping]. */
    private const val UPGRADE_MIN_GAIN_KBPS = 96

    /** How long the other sources may go on looking once one has offered a lossless copy. */
    private const val LOSSLESS_GRACE_MS = 4_000L

    /** The most YouTube's AAC ladder holds; a lossy download from a source has to beat it. */
    private const val YOUTUBE_BEST_AAC_KBPS = 256
}
