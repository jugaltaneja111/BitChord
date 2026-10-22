package com.music.bitchord.ios.party

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.partyQueueIndexOf
import com.music.bitchord.data.listentogether.partyUpcomingAfter
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What [IosPartySync] needs of the iOS player, and nothing more.
 *
 * The split matters: everything the sync does through [loadParty], [replaceUpcoming], [play],
 * [pause] and [seekTo] is *inbound* — the party moving this device — and must never be reported
 * back to [IosPartySync.onLocalIntent], or the device would republish its own corrections around
 * the party forever. Only the listener's own actions go out.
 */
internal interface IosPartyPlayer {
    val song: StateFlow<Song?>
    val isPlaying: StateFlow<Boolean>
    val isReady: Boolean            // a stream is loaded and not resolving/buffering
    val positionMs: Long
    val durationMs: Long
    val queue: StateFlow<List<Song>>
    val index: StateFlow<Int>
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    /** Puts the party's queue on the player at [index], paused unless [play], at [positionMs]; never reported back as a local intent. */
    fun loadParty(songs: List<Song>, index: Int, positionMs: Long, play: Boolean)
    /** Replaces everything after the current track, without reporting it. */
    fun replaceUpcoming(songs: List<Song>)
    fun setAutoplay(enabled: Boolean)
}

/**
 * Keeps this device's playback and the party's in step, ported from the desktop's
 * `DesktopPartySync` and, where the desktop is thinner, Android's `PartySync`.
 *
 * The two directions are deliberately separate, and never meet:
 *
 *  - **Outbound** is [onLocalIntent]: something the listener asked for here becomes a control the
 *    party is told about.
 *  - **Inbound** is [reconcile]: it writes to the player directly, so nothing this class does to
 *    playback can come back round as an intent and be republished.
 *
 * Three details carry most of the weight, and each is a correction of something that reads as a
 * bug without it:
 *
 *  - **Resuming waits for the scheduled start.** The server anchors a resume slightly in the
 *    future so every device has one instant to aim at. Playing the moment the frame lands would
 *    start this device early by exactly that lead — and being *consistently* early is worse than
 *    occasionally late, because it sits under the drift limit and is never corrected. So a local
 *    play is held back ([shouldDeferPlay]) and performed at that instant.
 *  - **Drift is corrected by seeking, and only when it is real.** A seek is audible, so
 *    [DRIFT_LIMIT_MS] sits well above the jitter of two decoders running independently, and a
 *    correction needs [DRIFT_STRIKES] readings in a row before it fires.
 *  - **Position is only trusted once the clock is.** Before the first ping/pong there is no
 *    measured offset, so the track is loaded and play/pause applied, and the playhead is left
 *    alone rather than seeked to a guess.
 *
 * Main thread only: [IosListenTogether.state] is written there, and the player is driven there.
 *
 * What Android has and this does not is audio-focus detachment. An interruption on iOS (a call)
 * pauses the session itself; if that ever needs to stop *following* as well, Android's
 * `focusLost` is the reference.
 */
internal class IosPartySync(
    private val scope: CoroutineScope,
    private val player: IosPartyPlayer,
    /** The party has just been entered: stash the personal queue, to be put back on leaving. */
    private val onEnteredParty: () -> Unit = {},
    /** The party has been left: restore what [onEnteredParty] stashed. */
    private val onLeftParty: () -> Unit = {},
) {

    private val jobs = mutableListOf<Job>()
    private var startJob: Job? = null
    private var publishJob: Job? = null
    private val clockOrigin = TimeSource.Monotonic.markNow()

    private var loadingVideoId: String? = null
    private var alignedSeq = -1L
    private var driftStrikes = 0
    private var driftCooldownUntilMs = 0L
    /** The playback anchor last aligned to, telling a real control from a queue-only seq bump. */
    private var lastAnchor: AnchorKey? = null
    /**
     * The seqs the party reaches once every control this device last sent has come back round.
     * Until then a reconcile would undo the very thing the listener just did; past them the quiet
     * window is over, however long it was meant to last.
     */
    private var awaitPlaybackSeq = Long.MAX_VALUE
    private var awaitQueueSeq = Long.MAX_VALUE
    /**
     * A listener in a locked party has paused their own device. The party plays on without them,
     * so [reconcile] keeps following the track and queue but neither starts the player nor chases
     * the playhead. Cleared by pressing play, by the lock lifting, and by leaving.
     */
    private var locallyPaused = false
    private var lastPartyCode: String? = null
    private var appliedAutoplay: Boolean? = null
    /** A track selection whose stream is still resolving. */
    private var pendingIntentVideoId: String? = null
    /** A queue row is being dragged; each neighbour it crosses is parked, not published. */
    private var queueDragActive = false
    private var queueDragDirty = false

    /**
     * A resume this device has accepted but not yet performed. Stored on [IosListenTogether] so a
     * play button can draw the wait — see [IosListenTogether.State.awaitingStart].
     */
    private var deferredPlayPending: Boolean
        get() = IosListenTogether.state.value.awaitingStart
        set(value) = IosListenTogether.setAwaitingStart(value)

    /**
     * Until when inbound reconciliation is held off.
     *
     * A control this device issued takes a moment to come back as a state frame; without a quiet
     * window the device that *issued* a seek would immediately fight its own echo.
     */
    private var quietUntilMs = 0L

    fun start() {
        stop()
        jobs += scope.launch {
            IosListenTogether.state
                // Not every field: the round-trip counter changing is not a control. The first
                // pong is, though — it is what lets a device that joined a playing party start.
                .map { ReconcileKey(it.playback.seq, it.queue.seq, it.code, it.clockSynced) }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.code != lastPartyCode) {
                        val wasInParty = lastPartyCode != null
                        val nowInParty = key.code != null
                        lastPartyCode = key.code
                        if (!wasInParty && nowInParty) {
                            TrackLog.i(TAG, "entered party, stashing personal queue")
                            onEnteredParty()
                        }
                        if (wasInParty && !nowInParty) {
                            TrackLog.i(TAG, "left party, restoring personal queue")
                            loadingVideoId = null
                            onLeftParty()
                        }
                    }
                    // Every control sent has come back, so the party now describes what the
                    // listener did — or somebody else has moved it on past that, which is as good
                    // a reason to stop holding reconcile off.
                    if (pendingIntentVideoId == null &&
                        key.seq >= awaitPlaybackSeq &&
                        key.queueSeq >= awaitQueueSeq
                    ) {
                        quietUntilMs = 0L
                    }
                    reconcile()
                }
        }
        // A steady tick as well as the frames: drift accumulates between controls, and nothing
        // arrives to announce it. It also reopens a socket the system dropped while the app was
        // suspended — the page is the only other thing that would, and a party outlives the page.
        jobs += scope.launch {
            while (true) {
                delay(RECONCILE_INTERVAL_MS)
                if (IosListenTogether.state.value.inParty) IosListenTogether.ensureConnected()
                reconcile()
            }
        }
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        startJob?.cancel()
        startJob = null
        publishJob?.cancel()
        publishJob = null
        loadingVideoId = null
        alignedSeq = -1L
        lastAnchor = null
        driftStrikes = 0
        awaitPlaybackSeq = Long.MAX_VALUE
        awaitQueueSeq = Long.MAX_VALUE
        locallyPaused = false
        lastPartyCode = null
        appliedAutoplay = null
        pendingIntentVideoId = null
        queueDragActive = false
        queueDragDirty = false
    }

    /**
     * Something the listener asked for here, which the party should be told about.
     *
     * Debounced, because one gesture is several calls: choosing a track is a queue, a load and a
     * play within a few milliseconds. [expectedVideoId] names a track selection still resolving,
     * so the publish waits for it rather than reporting the old track as current.
     */
    fun onLocalIntent(expectedVideoId: String? = null) {
        val party = IosListenTogether.state.value
        // Nothing this device does while the host holds control is the party's business; the
        // publish works by diffing, and would read a locally paused player as a pause to send.
        if (!party.inParty || party.controlsLocked) return
        // Queue/transport gestures can arrive while a preceding song selection is still loading.
        // Keep waiting for that selection rather than letting the later gesture cancel its guard.
        val waitForVideoId = expectedVideoId
            ?: pendingIntentVideoId
            ?: player.song.value?.videoId?.takeIf { !player.isReady }
        pendingIntentVideoId = waitForVideoId
        // Nothing has gone out yet, so there is nothing to wait for and somebody else's control
        // must not end the window protecting this one.
        awaitPlaybackSeq = Long.MAX_VALUE
        awaitQueueSeq = Long.MAX_VALUE
        // A selection first updates the visible state, then resolves a stream. Keep reconcile
        // out of the way until the new track is actually playable; otherwise the old party state
        // can arrive during the resolve and pause or replace the new selection.
        quietUntilMs = if (waitForVideoId != null) Long.MAX_VALUE else nowMs() + INTENT_QUIET_MS
        publishJob?.cancel()
        // A row dragged through the queue calls this once per neighbour it crosses. Parked, so
        // the party hears about the reorder once, when the row lands — see [endQueueDrag].
        if (queueDragActive) {
            queueDragDirty = true
            return
        }
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            if (waitForVideoId != null) {
                repeat(PUBLISH_WAIT_ATTEMPTS) {
                    if (player.song.value?.videoId == waitForVideoId && player.isReady) {
                        pendingIntentVideoId = null
                        quietUntilMs = nowMs() + INTENT_QUIET_MS
                        publish()
                        return@launch
                    }
                    delay(PUBLISH_WAIT_STEP_MS)
                }
                // The requested stream failed or was superseded. Do not publish the stale player
                // state; the regular party reconciliation will recover it.
                pendingIntentVideoId = null
                quietUntilMs = nowMs()
                return@launch
            }
            publish()
        }
    }

    /** A queue row started dragging. See [onLocalIntent]. */
    fun beginQueueDrag() {
        queueDragActive = true
    }

    /** The row was dropped, or the drag cancelled. Flushes anything parked by [onLocalIntent]. */
    fun endQueueDrag() {
        queueDragActive = false
        if (queueDragDirty) {
            queueDragDirty = false
            onLocalIntent()
        }
    }

    /**
     * Whether a `play()` from this device should be held back for the party.
     *
     * True means the caller must *not* start the player: this class will, at the instant the
     * server schedules for everyone. Starting now and letting drift correction sort it out does
     * not work — the head start a resume gives the device that issued it is smaller than any drift
     * threshold worth having, so it would never be corrected. Call [onLocalIntent] either way.
     *
     * False whenever the party could not schedule anything — no party, no socket, no measured
     * clock, a locked party — and when the player is already going, since a wait for a start that
     * has already happened is one nothing ever ends. The press is never simply swallowed:
     * [reconcile] starts the player on the echo, and a fallback starts it anyway after
     * [DEFERRED_PLAY_TIMEOUT_MS] if that echo never comes.
     */
    fun shouldDeferPlay(): Boolean {
        val party = IosListenTogether.state.value
        if (!party.inParty || party.connection != IosListenTogether.Connection.LIVE) return false
        if (!party.clockSynced || party.controlsLocked) return false
        if (player.isPlaying.value) return false
        deferredPlayPending = true
        deferredPlayFallback()
        return true
    }

    /** A control can be lost and a socket can be up in name only; a play button must still play. */
    private fun deferredPlayFallback() {
        startJob?.cancel()
        startJob = scope.launch {
            delay(DEFERRED_PLAY_TIMEOUT_MS)
            // Put down whatever happened: the flag is what the transport draws as "about to
            // start", and a wait that is over by any route has to end here.
            if (!deferredPlayPending) return@launch
            deferredPlayPending = false
            if (!player.isPlaying.value) {
                TrackLog.w(TAG, "party never acknowledged the resume; starting locally")
                player.play()
            }
        }
    }

    /**
     * Play or pause for a listener whose party is locked to its host.
     *
     * Returns true when it has handled it, which it has whenever the party is locked to somebody
     * else: the press moves this device and nothing else, and publishes nothing. Resuming rejoins
     * wherever the party has *got to* — the radio model, and the only one that makes sense when
     * the music never stopped for anybody else.
     */
    fun onLockedTransport(playing: Boolean): Boolean {
        val party = IosListenTogether.state.value
        if (!party.controlsLocked) return false
        if (playing) {
            locallyPaused = false
            // Nothing to join while the party itself is paused; starting would play alone for the
            // one tick it takes reconcile to pause again.
            if (!party.playback.isPlaying) return true
            IosListenTogether.partyPositionMs()?.takeIf { party.clockSynced }?.let(player::seekTo)
            player.play()
        } else {
            locallyPaused = true
            player.pause()
        }
        return true
    }

    /** Puts this device where the party is, writing to the player rather than through intents. */
    private fun reconcile() {
        val party = IosListenTogether.state.value
        if (!party.inParty) {
            loadingVideoId = null
            locallyPaused = false
            appliedAutoplay = null
            lastAnchor = null
            // Nothing is going to schedule a start now, so a resume still waiting on one would be
            // drawn as waiting forever.
            deferredPlayPending = false
            return
        }
        // Only the lock going away frees a local pause — not the party pausing: somebody who
        // muted their own device does not expect the host's play to bring it back.
        if (!party.controlsLocked) locallyPaused = false
        if (nowMs() < quietUntilMs) return
        val target = party.playback
        if (target.autoplayEnabled != appliedAutoplay) {
            appliedAutoplay = target.autoplayEnabled
            player.setAutoplay(target.autoplayEnabled)
        }
        val track = target.track
        if (track == null) {
            seedEmptyParty(party)
            return
        }
        val song = player.song.value

        // A local file is this device's own business; the party has no copy of it to agree on.
        if (song?.isDeviceFile() == true) return

        if (song?.videoId != track.videoId) {
            load(party, track)
            return
        }
        loadingVideoId = null
        reconcileQueue(party)

        // Muted by its own listener while the party plays on: the track and queue above still
        // follow, and everything below — starting, chasing the playhead — is sound.
        if (locallyPaused) {
            deferredPlayPending = false
            return
        }

        if (!target.isPlaying) {
            deferredPlayPending = false
            if (player.isPlaying.value) player.pause()
            // Held where the party paused it, so everybody resumes from the same place.
            if (abs(player.positionMs - target.positionMs) > PAUSED_TOLERANCE_MS) {
                player.seekTo(target.positionMs)
            }
            return
        }

        // A playing party cannot be joined *in time* without a measured clock: the stated
        // position may be minutes old. The track is already loaded above; only the position waits.
        if (!party.clockSynced) return

        val want = IosListenTogether.partyPositionMs()
        if (!player.isPlaying.value) {
            // Still resolving the stream: a seek now lands nowhere, and the next frame or tick
            // will find it ready.
            if (!player.isReady) return
            val wait = IosListenTogether.msUntilStart()
            if (wait > 0) {
                // Scheduled, not immediate. Wake for it rather than for the next tick, which
                // could be most of a tick late — and late is what this is here to avoid.
                startJob?.cancel()
                startJob = scope.launch {
                    delay(wait)
                    reconcile()
                }
                return
            }
            if (want != null) player.seekTo(want)
            deferredPlayPending = false
            startJob?.cancel()
            player.play()
            alignedSeq = target.seq
            lastAnchor = AnchorKey.of(target.anchorMs, target.positionMs, track.videoId, target.isPlaying)
            return
        }

        // Playing, in a party that is playing: whatever start this device was waiting for has
        // been made, by whichever route.
        deferredPlayPending = false
        if (want == null) return

        // Only a settled player can be measured: while it buffers, its position is where it will
        // resume rather than where it is, and the party running on reads as drift that is not there.
        if (!player.isReady) {
            driftStrikes = 0
            return
        }

        val drift = player.positionMs - want
        val anchor = AnchorKey.of(target.anchorMs, target.positionMs, track.videoId, target.isPlaying)
        val now = nowMs()
        val decision = decideSeek(
            drift = drift,
            controlSeq = target.seq,
            alignedSeq = alignedSeq,
            strikes = driftStrikes,
            nowMs = now,
            cooldownUntilMs = driftCooldownUntilMs,
            // A seq bumped by a queue edit or metadata moves no playhead; forcing an audible
            // align over it would make every Add to queue a hiccup on every device.
            anchorChanged = anchor != lastAnchor,
        )
        alignedSeq = decision.alignedSeq
        lastAnchor = anchor
        driftStrikes = decision.strikes
        if (decision.cooldownUntilMs > 0) driftCooldownUntilMs = decision.cooldownUntilMs
        if (decision.seek) {
            TrackLog.i(TAG, "party: ${decision.reason} ${drift}ms")
            player.seekTo(want)
        }
    }

    /**
     * Puts the party's running order on this player at its track.
     *
     * The queue comes across as well as the track so next and previous work locally and the next
     * track can be prepared. The queue is only usable if the track is actually in it: between the
     * two controls the party holds a new running order with the old song still current. Falling
     * back to the track alone is always right; falling back to position zero never is.
     */
    private fun load(party: IosListenTogether.State, track: PartyTrack) {
        if (loadingVideoId == track.videoId) return
        loadingVideoId = track.videoId
        val at = partyQueueIndexOf(party.queue, party.playback, track.videoId)
        val tracks = if (at >= 0) party.queue.items else listOf(track)
        val index = if (at >= 0) at else 0
        val songs = tracks.map(PartyTrack::toPartySong)
        // Started here only when nothing is left to wait for: a resume scheduled a moment out, or
        // an unmeasured clock, is [reconcile]'s to perform once the stream is ready.
        val startNow = party.playback.isPlaying && party.clockSynced && IosListenTogether.msUntilStart() <= 0L
        val position = IosListenTogether.partyPositionMs() ?: party.playback.positionMs
        player.loadParty(songs, index, position, startNow)
        if (startNow) alignedSeq = party.playback.seq
    }

    /**
     * Brings what plays after this track into line with the party's — every tick, not just when
     * the queue's seq moves, so a local edit the party never took is put right rather than lived
     * with until the next track. Only while the track playing here is in the party's queue; a new
     * empty party keeps the host's queue, which [publish] is about to seed it with.
     */
    private fun reconcileQueue(party: IosListenTogether.State) {
        if (party.queue.items.isEmpty()) return
        val songs = player.queue.value
        val index = player.index.value
        val currentId = songs.getOrNull(index)?.videoId ?: return
        if (partyQueueIndexOf(party.queue, party.playback, currentId) < 0) return
        val desired = partyUpcomingAfter(party.queue, party.playback, currentId).take(MAX_PARTY_UPCOMING_QUEUE)
        val local = songs.drop(index + 1)
        // Compared by track and section, so a matching tail is left alone rather than rebuilt —
        // replacing it would cost the player whatever it had prepared for the next track.
        if (local.map { it.videoId to it.fromAutoplay } == desired.map { it.videoId to it.fromAutoplay }) return
        player.replaceUpcoming(desired.map(PartyTrack::toPartySong))
    }

    /**
     * Hands a party with nothing in it the music this device is already playing.
     *
     * A party is created empty, and [publish] only runs off something the listener did — creating
     * a party is not one of those. Only the host, so several people arriving already playing
     * different things do not race; only while the party has no track; and only once the socket
     * is live, because a control sent before it is dropped without a word. Called from [reconcile]
     * so it is retried each tick until it lands.
     */
    private fun seedEmptyParty(party: IosListenTogether.State) {
        if (party.connection != IosListenTogether.Connection.LIVE) return
        if (party.you?.isHost != true) return
        val song = player.song.value ?: return
        if (song.isDeviceFile()) return
        // The upcoming context (the rest of an album or playlist) is this device's own; the party
        // takes the listener's queue and AutoPlay, so the local queue is trimmed to match.
        val upcoming = player.queue.value.drop(player.index.value + 1)
        val kept = upcoming.filter { it.queueTier != QueueTier.CONTEXT }
        if (kept.size != upcoming.size) player.replaceUpcoming(kept)
        publish()
    }

    /** Tells the party what this device just did. */
    private fun publish() {
        val party = IosListenTogether.state.value
        if (!party.inParty) return
        // A stream still resolving reads as paused and as possibly the wrong track; reporting it
        // would seed a false pause or an incomplete current track.
        if (!player.isReady) return
        val song = player.song.value ?: return
        if (song.isDeviceFile()) return
        val durationMs = player.durationMs
        val track = song.toPartyTrack(durationMs)
        val position = player.positionMs.coerceAtLeast(0L)
        // What the listener asked for, which during a deferred resume is not what the player is
        // doing yet — that is the whole point of the deferral.
        val wantsPlaying = deferredPlayPending || player.isPlaying.value
        val basePlayback = party.playback.seq
        val baseQueue = party.queue.seq
        var playbackControls = 0
        var queueControls = 0

        val (shareable, shareIndex) = queueForPartyPublish(
            songs = player.queue.value,
            currentIndex = player.index.value,
            currentVideoId = song.videoId,
            currentDurationMs = durationMs,
        )
        // Compared from the playing track on, by the tracks and their sections. Not by the party
        // queue's index, which only moves when the queue is resent; and not over history, which
        // each device trims to its own length, so two devices would otherwise resend their own
        // pasts at each other on every pause.
        val partyAt = partyQueueIndexOf(party.queue, party.playback, song.videoId)
        if (partyAt < 0 || shareIndex < 0 ||
            shareable.drop(shareIndex).map { it.videoId to it.fromAutoplay } !=
            party.queue.items.drop(partyAt).map { it.videoId to it.fromAutoplay }
        ) {
            IosListenTogether.setQueue(shareable, shareIndex)
            queueControls++
        }

        // After the queue, never before: a track change that arrives pointing into a running order
        // nobody has yet is the bug that played the wrong song.
        when {
            party.playback.track?.videoId != track.videoId -> {
                IosListenTogether.setTrack(track, position, wantsPlaying)
                playbackControls++
            }
            wantsPlaying && !party.playback.isPlaying -> {
                IosListenTogether.play(position)
                playbackControls++
            }
            !wantsPlaying && party.playback.isPlaying -> {
                IosListenTogether.pause(position)
                playbackControls++
            }
            // Same track, same state, so what moved was the playhead — unless it did not move far,
            // which is two devices reaching a track's end together rather than a seek.
            abs(position - (IosListenTogether.partyPositionMs() ?: position)) > SEEK_REPORT_FLOOR_MS -> {
                IosListenTogether.seek(position)
                playbackControls++
            }
        }
        awaitPlaybackSeq = basePlayback + playbackControls
        awaitQueueSeq = baseQueue + queueControls
        if (playbackControls == 0 && queueControls == 0) quietUntilMs = 0L
    }

    private fun nowMs(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

    /** The fields of the party a reconcile actually turns on. */
    private data class ReconcileKey(
        val seq: Long,
        val queueSeq: Long,
        val code: String?,
        val clockSynced: Boolean,
    )

    /** What a playback control moves; a seq whose anchor is unchanged moved nothing audible. */
    private data class AnchorKey(
        val anchorMs: Long,
        val positionMs: Long,
        val videoId: String,
        val isPlaying: Boolean,
    ) {
        companion object {
            fun of(anchorMs: Long, positionMs: Long, videoId: String, isPlaying: Boolean) =
                AnchorKey(anchorMs, positionMs, videoId, isPlaying)
        }
    }

    /** What [reconcile] decided about the playhead, and the counters it leaves behind. */
    internal data class SeekDecision(
        val seek: Boolean,
        val alignedSeq: Long,
        val strikes: Int,
        val cooldownUntilMs: Long = 0,
        val reason: String = "",
    )

    internal companion object {
        private const val TAG = "IosPartySync"

        /**
         * Whether the playhead should be moved, given how far out it is.
         *
         * Pure so the rules can be stated rather than inferred: a new control aligns at once and
         * tightly, ordinary drift has to be both large and persistent, and a correction that has
         * just fired will not fire again until the cooldown is out. A new seq whose anchor did not
         * change ([anchorChanged] false) is marked handled without the tight align.
         */
        fun decideSeek(
            drift: Long,
            controlSeq: Long,
            alignedSeq: Long,
            strikes: Int,
            nowMs: Long,
            cooldownUntilMs: Long,
            anchorChanged: Boolean = true,
        ): SeekDecision {
            // A deliberate jump by whoever holds the party: align to it now.
            if (controlSeq != alignedSeq && anchorChanged) {
                return SeekDecision(
                    seek = abs(drift) > ALIGN_TOLERANCE_MS,
                    alignedSeq = controlSeq,
                    strikes = 0,
                    reason = "aligning onto control $controlSeq",
                )
            }
            if (abs(drift) <= DRIFT_LIMIT_MS) {
                return SeekDecision(seek = false, alignedSeq = controlSeq, strikes = 0)
            }
            if (nowMs < cooldownUntilMs) {
                return SeekDecision(seek = false, alignedSeq = controlSeq, strikes = strikes)
            }
            val next = strikes + 1
            if (next < DRIFT_STRIKES) {
                return SeekDecision(seek = false, alignedSeq = controlSeq, strikes = next)
            }
            return SeekDecision(
                seek = true,
                alignedSeq = controlSeq,
                strikes = 0,
                cooldownUntilMs = nowMs + DRIFT_COOLDOWN_MS,
                reason = "correcting",
            )
        }

        /** Well above two decoders' jitter, and at the level of an actual desync. */
        const val DRIFT_LIMIT_MS = 1_200L

        /** Readings in a row before a correction fires, so one stall does not cause a seek. */
        const val DRIFT_STRIKES = 2

        /**
         * How long the player is left alone after a corrective seek: long enough to cover the
         * re-buffer a seek costs, which is what decides whether correction converges or oscillates.
         */
        const val DRIFT_COOLDOWN_MS = 6_000L

        /** While paused there is nothing to hear, so the playhead can be exact. */
        const val PAUSED_TOLERANCE_MS = 400L

        /** How exactly a device lands on a control: no head start worth hearing, no needless seek. */
        const val ALIGN_TOLERANCE_MS = 120L

        /** Below this, a difference from the party's playhead is not a seek anybody performed. */
        const val SEEK_REPORT_FLOOR_MS = 1_000L

        /**
         * The longest a local action is protected from being reconciled away. Normally it ends
         * sooner, when the controls it sent come back; this covers an echo that never does.
         */
        const val INTENT_QUIET_MS = 4_500L

        /** How long a deferred resume waits for the party before starting locally anyway. */
        const val DEFERRED_PLAY_TIMEOUT_MS = 1_800L

        /**
         * Long enough to coalesce the burst one tap makes, and no longer: a resume is held until
         * this has elapsed, and every millisecond here is silence after the button was pressed.
         */
        const val PUBLISH_DEBOUNCE_MS = 120L
        const val PUBLISH_WAIT_STEP_MS = 100L
        const val PUBLISH_WAIT_ATTEMPTS = 100
        const val RECONCILE_INTERVAL_MS = 700L

        /** Upcoming tracks in a party queue, excluding the one playing; the server's own ceiling. */
        const val MAX_PARTY_UPCOMING_QUEUE = 25

        /** The same queue projection Android and the desktop publish to a party. */
        internal fun queueForPartyPublish(
            songs: List<Song>,
            currentIndex: Int,
            currentVideoId: String,
            currentDurationMs: Long,
        ): Pair<List<PartyTrack>, Int> {
            val raw = songs.withIndex().filter { (_, song) -> !song.isDeviceFile() }
            val rawCurrent = raw.indexOfFirst { (originalIndex, song) ->
                originalIndex == currentIndex && song.videoId == currentVideoId
            }.takeIf { it >= 0 } ?: raw.indexOfFirst { it.value.videoId == currentVideoId }

            val withoutContextTail = if (rawCurrent >= 0) {
                raw.take(rawCurrent + 1) + raw.drop(rawCurrent + 1)
                    .filter { it.value.queueTier != QueueTier.CONTEXT }
            } else {
                raw.filter { it.value.queueTier != QueueTier.CONTEXT }
            }
            val projectedCurrent = withoutContextTail.indexOfFirst { (originalIndex, song) ->
                originalIndex == currentIndex && song.videoId == currentVideoId
            }.takeIf { it >= 0 }
                ?: withoutContextTail.indexOfFirst { it.value.videoId == currentVideoId }
            val endExclusive = if (projectedCurrent >= 0) {
                (projectedCurrent + 1 + MAX_PARTY_UPCOMING_QUEUE).coerceAtMost(withoutContextTail.size)
            } else {
                (1 + MAX_PARTY_UPCOMING_QUEUE).coerceAtMost(withoutContextTail.size)
            }
            val tracks = withoutContextTail.take(endExclusive).map { (_, queued) ->
                queued.toPartyTrack(if (queued.videoId == currentVideoId) currentDurationMs else 0L)
            }
            return tracks to tracks.indexOfFirst { it.videoId == currentVideoId }
        }
    }
}

/**
 * Whether this is a file on the device rather than a catalogue track, as the desktop decides it.
 * A downloaded catalogue track is not this case — the iOS downloads keep the catalogue row and
 * its real id, which every other device can stream perfectly well.
 */
private fun Song.isDeviceFile(): Boolean =
    localPath != null || localUri != null || videoId.startsWith("file://") || videoId.startsWith("content://") ||
        com.music.bitchord.ios.IosLocalMusic.isLocal(videoId)

/**
 * This device's row as the party knows it — identity and artwork, not how the audio was got.
 * The player's own duration when it has one, since it comes from the decoder; otherwise what the
 * row that queued the track claimed.
 */
internal fun Song.toPartyTrack(durationMs: Long): PartyTrack = PartyTrack(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMs.takeIf { it > 0 } ?: TrackMatcher.secondsOf(durationText)?.let { it * 1000L },
    fromAutoplay = queueTier == QueueTier.AUTOPLAY,
)

/**
 * A shared queue item as a playable catalogue song, in the section the phone puts it in:
 * AutoPlay's, or the listener's own queue.
 */
internal fun PartyTrack.toPartySong(): Song = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    // Not cosmetic: a cross-source match is made on it, as on the phone.
    durationText = durationMs?.let { ms ->
        val total = ms / 1000
        "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    },
    queueTier = if (fromAutoplay) QueueTier.AUTOPLAY else QueueTier.USER_QUEUE,
)
