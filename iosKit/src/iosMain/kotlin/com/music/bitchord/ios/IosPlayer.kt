package com.music.bitchord.ios

import com.music.bitchord.ios.sources.IosSources
import com.music.bitchord.ios.sources.ResolvedStream
import com.music.bitchord.ios.sources.StreamRequest
import kotlinx.coroutines.async
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TelemetryProvenance
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.ui.player.RepeatModes
import com.music.bitchord.ios.party.IosListenTogether
import com.music.bitchord.ios.party.IosPartyPlayer
import com.music.bitchord.ios.party.IosPartyKeepAlive
import com.music.bitchord.ios.party.IosPartySync
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.music.bitchord.ios.automix.IosAutomixAnalysis
import com.music.bitchord.ios.automix.IosVersionAligner
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import kotlinx.coroutines.Deferred
import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent

/**
 * The queue, and turning each of its songs into sound: resolve a stream with InnerTubeX, hand it
 * to [IosAudioEngine], tell the lock screen. A tap in search plays the song and fills the queue
 * with its radio, as the phone does. Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosPlayer {
    private const val TAG = "IosPlayer"
    private const val SUBSTITUTE_WAIT_MS = 6_000L
    /**
     * How long a source lookup that lost the start may go on, to become an upgrade instead. Long,
     * because nothing waits on it any more: an addon fronting a hi-res catalogue can take 10–15 s to
     * search and as long again to mint its stream URL, and a lookup cut off at 20 s threw that
     * answer away only for the upgrade to ask the whole question again from the top.
     */
    private const val SUBSTITUTE_LOOKUP_MS = 60_000L

    /** A version switch's blend, the phone's. */
    private const val VERSION_FADE_MS = 550L
    /** The longest a better copy waits for the playing track's length to settle before it is swapped in. */
    private const val UPGRADE_SETTLE_MS = 5_000L
    private const val UPGRADE_SETTLE_STEP_MS = 250L
    /**
     * The most a track being readied ahead lets the sources take, rather than [SUBSTITUTE_WAIT_MS]:
     * nothing is waiting on it while the one before plays, so a slow addon's FLAC can be the copy
     * the track starts on instead of a swap seconds into it.
     */
    private const val PREPARE_SUBSTITUTE_WAIT_MS = 25_000L
    /** What readying ahead leaves free before the blend or the end, so the track is in hand by then. */
    private const val PREPARE_SPARE_MS = 5_000L
    /** No upgrade with less than this left: the swap would cost more than it gives. */
    private const val UPGRADE_MIN_REMAINING_MS = 20_000L
    private const val LOW_KBPS = 64

    /** How long before a track ends the next one is fetched into the waiting player. */
    private const val PREPARE_AHEAD_MS = 30_000L
    private const val TRANSITION_POLL_MS = 250L
    /** Automix readies the next song earlier, so it can be cued before its blend comes round. */
    private const val AUTOMIX_PREPARE_AHEAD_MS = 45_000L
    /** Automix's fade for a pair it has no analysis for, with the slider at 0 (the phone's 6 s). */
    private const val AUTOMIX_FALLBACK_SECONDS = 6.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private val engine = IosAudioEngine(onEnded = ::onEnded, onFailed = ::engineFailed)

    /** Tracks whose stream the FFmpeg player could not play this session: YouTube's AAC for them. */
    private val avPlayerOnly = mutableSetOf<String>()

    /**
     * A track that failed in the FFmpeg player is played again from YouTube's AAC, where it has
     * one, from where it stopped — the phone's last-rung YouTube fallback, for a path this app
     * has only just grown. Anything else, or a second failure, is a failure.
     */
    private fun engineFailed(message: String) {
        val song = _song.value
        val key = song?.videoId
        if (key != null && engine.onFfmpeg && key !in avPlayerOnly) {
            TrackLog.w(TAG, "$key: FFmpeg could not play it ($message); trying YouTube's AAC")
            avPlayerOnly += key
            playAt(_index.value, startMs = engine.position.positionMs, startPlaying = true)
            return
        }
        fail(message)
    }

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    private val _resolving = MutableStateFlow(false)

    /** Finding a stream, or waiting on its first bytes. */
    val isLoading: StateFlow<Boolean> = combine(_resolving, engine.isBuffering) { resolving, buffering ->
        resolving || buffering
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val isPlaying: StateFlow<Boolean> = engine.isPlaying
    val durationMs: StateFlow<Long> = engine.durationMs
    val position get() = engine.position

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** One of [RepeatModes]. */
    private val _repeatMode = MutableStateFlow(RepeatModes.OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    /** Keep playing the last song's radio once the queue runs out, as the phone does by default. */
    private val _autoplay = IosSettings.autoplay.flow

    /** The party's own AutoPlay switch while in one; null outside a party. */
    private val partyAutoplay = MutableStateFlow<Boolean?>(null)
    private val autoplayOn: Boolean get() = partyAutoplay.value ?: _autoplay.value

    /** AutoPlay as the player shows it: the party's while in one, the listener's setting otherwise. */
    val autoplay: StateFlow<Boolean> = combine(partyAutoplay, _autoplay) { party, own -> party ?: own }
        .stateIn(scope, SharingStarted.Eagerly, _autoplay.value)

    /** The queue as it was before shuffling, so turning shuffle off puts it back. */
    private var unshuffled: List<Song>? = null
    /** Every track started since launch, which "Don't repeat songs" keeps AutoPlay from suggesting. */
    private val playedThisSession = mutableSetOf<String>()
    private var extending = false

    private val _error = MutableStateFlow<String?>(null)
    /** Why the last track would not play, until the next one starts. */
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Listen Together ─────────────────────────────────────────────────────

    /**
     * Set while the party itself drives the player ([PartyBridge]), so nothing it does comes back
     * round as something the listener did and is republished — the phone's SessionPlayer split.
     */
    private var applyingParty = false


    private val inParty: Boolean get() = IosListenTogether.state.value.inParty

    /** The listener's own queue, kept while a party plays and put back on leaving. */
    private var personalQueue: Triple<List<Song>, Int, Long>? = null

    private val partySync = IosPartySync(
        scope = scope,
        player = PartyBridge,
        onEnteredParty = { personalQueue = Triple(_queue.value, _index.value, engine.position.positionMs) },
        onLeftParty = {
            partyAutoplay.value = null
            val (songs, index, positionMs) = personalQueue ?: return@IosPartySync
            personalQueue = null
            if (songs.isNotEmpty()) applying { _queue.value = songs; playAt(index.coerceIn(songs.indices), positionMs, startPlaying = false) }
        },
    )

    init {
        IosPartyKeepAlive.watch(scope, engine.isPlaying)
        scope.launch { publishNerdStats() }
    }

    /** Where each track's audio came from and the bitrate it was picked at, for stats for nerds. */
    private val servedBy = HashMap<String, Pair<String, Int?>>()

    /**
     * Stats for nerds, as the phone's player publishes them: what the deck being heard actually
     * decodes, read off AVFoundation once its item is ready, beside where it came from. Cleared
     * across a track change, so a gap shows nothing rather than the last track's figures.
     */
    private suspend fun publishNerdStats() {
        while (true) {
            kotlinx.coroutines.delay(500)
            if (!IosSettings.showNerdStats.flow.value) continue
            // Decoration: a format AVFoundation describes oddly costs the stats line, never the app.
            val format = runCatching { engine.decodedFormat() }
                .onFailure { TrackLog.w(TAG, "stats for nerds: ${it.message}") }
                .getOrNull()
            val key = format?.key
            if (format == null || key == null) {
                // Between tracks, or still loading: nothing, rather than the last track's figures.
                if (NerdStats.current.value != null) NerdStats.current.value = null
                continue
            }
            val (source, pickedKbps) = servedBy[key] ?: (null to null)
            val snapshot = NerdStats.Snapshot(
                mimeType = format.mimeType,
                bitrateKbps = pickedKbps ?: format.estimatedKbps,
                bitrateProvenance = if (pickedKbps != null) TelemetryProvenance.AUTHORITATIVE else TelemetryProvenance.DERIVED,
                sampleRateHz = format.sampleRateHz,
                channels = format.channels,
                bitDepth = format.bitDepth,
                sourceName = source,
            )
            val now = NerdStats.current.value
            if (now == null || now.mimeType != snapshot.mimeType || now.bitrateKbps != snapshot.bitrateKbps ||
                now.sampleRateHz != snapshot.sampleRateHz || now.channels != snapshot.channels || now.sourceName != snapshot.sourceName
            ) {
                NerdStats.current.value = snapshot
            }
        }
    }

    /** A queue row held mid-drag: the party hears the move once it is let go. */
    fun queueDrag(active: Boolean) = if (active) partySync.beginQueueDrag() else partySync.endQueueDrag()

    /** Something the listener did here: the party is told. */
    private fun intent(expectedVideoId: String? = null) {
        if (!applyingParty) partySync.onLocalIntent(expectedVideoId)
    }

    /** A guest in a party whose host keeps the controls: refused, and told why. */
    private fun refusedByHost(): Boolean {
        if (applyingParty || !IosListenTogether.state.value.controlsLocked) return false
        IosPlayerHost.showMessage(tr("Only the host can change what’s playing"))
        return true
    }

    private inline fun applying(block: () -> Unit) {
        val was = applyingParty
        applyingParty = true
        try {
            block()
        } finally {
            applyingParty = was
        }
    }

    /** What the party sync reads and drives; nothing done through it is reported back. */
    private object PartyBridge : IosPartyPlayer {
        override val song: StateFlow<Song?> get() = _song
        override val isPlaying: StateFlow<Boolean> get() = engine.isPlaying
        override val isReady: Boolean get() = _song.value != null && !_resolving.value && !engine.isBuffering.value
        override val positionMs: Long get() = engine.position.positionMs
        override val durationMs: Long get() = engine.durationMs.value
        override val queue: StateFlow<List<Song>> get() = _queue
        override val index: StateFlow<Int> get() = _index
        override fun play() = applying { engine.play() }
        override fun pause() = applying { engine.pause() }
        override fun seekTo(positionMs: Long) = applying {
            engine.seekTo(positionMs)
            IosDiscord.refresh()
        }
        override fun loadParty(songs: List<Song>, index: Int, positionMs: Long, play: Boolean) = applying {
            if (songs.isEmpty()) return@applying
            unshuffled = null
            _shuffle.value = false
            _queue.value = songs
            playAt(index.coerceIn(songs.indices), positionMs, play)
        }
        override fun replaceUpcoming(songs: List<Song>) = applying {
            val index = _index.value
            _queue.value = _queue.value.take(index + 1) + songs
        }
        override fun setAutoplay(enabled: Boolean) {
            partyAutoplay.value = enabled
        }
    }

    init {
        partySync.start()
        wireRemoteCommands()
        scope.launch {
            combine(_song, engine.isPlaying, engine.durationMs) { song, playing, duration ->
                Triple(song, playing, duration)
            }.collect { (song, playing, duration) -> publishNowPlaying(song, playing, duration) }
        }
        // The next track, readied ahead and blended or cut in at the end of this one.
        scope.launch {
            while (true) {
                delay(TRANSITION_POLL_MS)
                watchTransition()
            }
        }
        // The scrobblers follow what is heard: playing once the stream is found, paused otherwise.
        scope.launch {
            combine(engine.isPlaying, _resolving) { playing, resolving -> playing && !resolving }
                .collect { IosScrobbler.onPlaying(it, engine.position.positionMs) }
        }
        scope.launch { engine.durationMs.collect(IosScrobbler::onDuration) }
        // Discord's card: up while playing, down on pause, re-sent on a new track.
        scope.launch {
            // The length lands a beat after the track; the card's countdown needs it.
            combine(_song, engine.isPlaying, _resolving, engine.durationMs) { song, playing, resolving, duration ->
                Triple(song?.videoId, playing && !resolving, duration > 0)
            }.distinctUntilChanged().collect { IosDiscord.refresh() }
        }
        // Replay's listening, sampled while something actually plays.
        scope.launch {
            var wasPlaying = false
            while (true) {
                val song = _song.value
                val playing = engine.isPlaying.value && song != null && !_resolving.value
                if (playing && song != null) {
                    IosListeningRecorder.onSample(song, engine.durationMs.value)
                } else if (wasPlaying) {
                    IosListeningRecorder.onStopped()
                }
                wasPlaying = playing
                delay(IosListeningRecorder.SAMPLE_MS)
            }
        }
    }

    /** Plays [song] now and queues its radio after it. */
    fun playRadio(song: Song) {
        if (refusedByHost()) return
        playQueue(listOf(song), 0)
        // A local file has no YouTube id to seed a station with.
        if (isOwnFile(song)) return
        scope.launch {
            YtMusicRepository.radio(song.videoId).onSuccess { radio ->
                // Still the same session: the user may have moved on while it loaded.
                if (_queue.value.firstOrNull()?.videoId != song.videoId) return@onSuccess
                _queue.value = listOf(song) + radio.filter { it.videoId != song.videoId }
                intent()
            }
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty() || refusedByHost()) return
        unshuffled = null
        _shuffle.value = false
        _queue.value = songs
        val index = startIndex.coerceIn(songs.indices)
        playAt(index)
        intent(songs[index].videoId)
    }

    /** A whole list in random order, from its first song. */
    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) return
        playQueue(songs.shuffled(), 0)
        unshuffled = songs
        _shuffle.value = true
    }

    /** [songs] straight after the one playing; they start now if nothing is. */
    fun playNext(songs: List<Song>) {
        if (songs.isEmpty() || refusedByHost()) return
        val index = _index.value
        if (_song.value == null || index < 0) {
            playQueue(songs, 0)
            return
        }
        val queue = _queue.value
        _queue.value = queue.take(index + 1) + songs + queue.drop(index + 1)
        unshuffled = unshuffled?.plus(songs)
        intent()
        IosPlayerHost.showMessage(if (songs.size == 1) tr("Playing next") else trPlural(songs.size, "{0} song will play next", "{0} songs will play next"))
    }

    /** [songs] at the end of the queue; they start now if nothing is playing. */
    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty() || refusedByHost()) return
        if (_song.value == null || _index.value < 0) {
            playQueue(songs, 0)
            return
        }
        _queue.value = _queue.value + songs
        unshuffled = unshuffled?.plus(songs)
        intent()
        IosPlayerHost.showMessage(if (songs.size == 1) tr("Added to queue") else trPlural(songs.size, "Added {0} song to the queue", "Added {0} songs to the queue"))
    }

    /** A swiped row: the end of the queue, or next with "Play next on swipe". */
    fun swipe(song: Song) = if (IosSettings.swipePlayNext.flow.value) playNext(listOf(song)) else addToQueue(listOf(song))

    fun jumpTo(index: Int) {
        if (index !in _queue.value.indices || refusedByHost()) return
        playAt(index)
        intent(_queue.value[index].videoId)
    }

    fun removeAt(index: Int) {
        val queue = _queue.value
        if (index !in queue.indices || index == _index.value || refusedByHost()) return
        _queue.value = queue.toMutableList().apply { removeAt(index) }
        if (index < _index.value) _index.value -= 1
        intent()
    }

    fun move(from: Int, to: Int) {
        val queue = _queue.value
        if (from !in queue.indices || to !in queue.indices || from == to || refusedByHost()) return
        _queue.value = queue.toMutableList().apply { add(to, removeAt(from)) }
        val current = _index.value
        _index.value = when {
            from == current -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }
        intent()
    }

    /** Everything but the song playing. */
    fun clearQueue() {
        val song = _song.value ?: return
        if (refusedByHost()) return
        unshuffled = null
        _queue.value = listOf(song)
        _index.value = 0
        intent()
    }

    fun toggleShuffle() {
        if (refusedByHost()) return
        val queue = _queue.value
        val current = _song.value
        if (_shuffle.value) {
            _shuffle.value = false
            val original = unshuffled ?: return
            unshuffled = null
            // Songs added since shuffling stay, after the original list.
            val restored = original + queue.filter { song -> original.none { it.videoId == song.videoId } }
            _queue.value = restored
            _index.value = restored.indexOfFirst { it.videoId == current?.videoId }.coerceAtLeast(0)
        } else {
            _shuffle.value = true
            unshuffled = queue
            val index = _index.value
            if (index < 0) return
            _queue.value = queue.take(index + 1) + queue.drop(index + 1).shuffled()
        }
        intent()
    }

    /** The phone's order: off, all, one. */
    fun cycleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatModes.OFF -> RepeatModes.ALL
            RepeatModes.ALL -> RepeatModes.ONE
            else -> RepeatModes.OFF
        }
    }

    fun toggleAutoplay() {
        // In a party AutoPlay is the party's, and everyone's switch is the same switch.
        if (inParty) IosListenTogether.setAutoplay(!autoplayOn) else IosSettings.autoplay.set(!_autoplay.value)
    }

    fun togglePlayPause() {
        if (_song.value == null) return
        val play = !engine.isPlaying.value
        // A guest under a host's lock plays or pauses only this phone.
        if (partySync.onLockedTransport(play)) return
        if (!play) {
            engine.pause()
        } else if (!partySync.shouldDeferPlay()) {
            // In a live party the play is the party's: it starts when the server says, everywhere.
            engine.play()
        }
        intent()
    }

    /** Lock-screen play and pause, which say which they mean. */
    private fun setPlaying(play: Boolean) {
        if (play == engine.isPlaying.value) return
        togglePlayPause()
    }

    fun next() {
        if (!advancingFromEnd && refusedByHost()) return
        val nextIndex = _index.value + 1
        when {
            nextIndex in _queue.value.indices -> {
                playAt(nextIndex)
                intent(_queue.value[nextIndex].videoId)
            }
            _repeatMode.value == RepeatModes.ALL && _queue.value.isNotEmpty() -> {
                playAt(0)
                intent(_queue.value[0].videoId)
            }
            autoplayOn -> extendWithRadio(playNext = true)
            else -> {
                // The queue ran out: nothing follows to close the last listen out.
                if (advancingFromEnd) _song.value?.let { IosScrobbler.onSongChanged(it, ended = true, song = it) }
                engine.pause()
            }
        }
    }

    /** The sleep timer running out: a pause, as the play button's. */
    fun pauseForSleep() = setPlaying(false)

    /**
     * Set by "After this song" as the track ends: the queue still moves on, as the phone's does, and
     * the next track is left paused at its start.
     */
    private var pauseOnNextStart = false

    private fun onEnded() {
        if (IosSleepTimer.afterTrack.value) {
            IosSleepTimer.cancel()
            pauseOnNextStart = true
        }
        if (_repeatMode.value == RepeatModes.ONE) {
            _song.value?.let { IosScrobbler.onSongChanged(it, ended = true, song = it) }
            engine.seekTo(0)
            if (pauseOnNextStart) {
                pauseOnNextStart = false
                engine.pause()
                return
            }
            engine.play()
            // Still playing, so the play state never changes: the repeat starts here.
            IosScrobbler.onPlaying(true, 0L)
        } else {
            advancingFromEnd = true
            next()
            advancingFromEnd = false
            // Nothing followed to start paused: the queue ran out, or a radio is still loading.
            if (pauseOnNextStart) {
                pauseOnNextStart = false
                engine.pause()
            }
        }
    }

    /** Set while the queue moves on because a track ended, for the scrobblers: a skip isn't a listen. */
    private var advancingFromEnd = false

    /** The queue position that plays after this one on its own, or null at the end. */
    private fun upcomingIndex(): Int? {
        if (_repeatMode.value == RepeatModes.ONE) return null
        val queue = _queue.value
        val next = _index.value + 1
        return when {
            next in queue.indices -> next
            _repeatMode.value == RepeatModes.ALL && queue.isNotEmpty() -> 0
            else -> null
        }
    }

    private var prepareJob: Job? = null
    private var preparing: String? = null

    /**
     * Readies the next track in the waiting player [PREPARE_AHEAD_MS] before this one ends, and
     * with a crossfade set, starts the blend once the end is that close.
     */
    private fun watchTransition() {
        val song = _song.value ?: return
        // A swap onto another copy holds the waiting player until it lands.
        if (_resolving.value || !engine.isPlaying.value || engine.isBlending || engine.isSwapping) return
        val duration = engine.durationMs.value
        if (duration <= 0L) return
        val remaining = duration - engine.position.positionMs
        val nextIndex = upcomingIndex()
        val next = nextIndex?.let { _queue.value.getOrNull(it) }
        if (next == null || next.videoId == song.videoId) {
            if (engine.preparedKey != null && preparing == null) engine.clearPrepared()
            return
        }
        // A party keeps every device on one timeline: a blend would put this one ahead of it.
        // "After this song" lets the track end on its own: no blend runs into the next one.
        val sleepAfter = IosSleepTimer.afterTrack.value
        val automix = IosSettings.automix.flow.value && !inParty && !sleepAfter
        if (automix) {
            IosAutomixAnalysis.request(song)
            IosAutomixAnalysis.request(next)
        }
        val prepareAhead = if (automix) AUTOMIX_PREPARE_AHEAD_MS else PREPARE_AHEAD_MS
        if (remaining <= prepareAhead && engine.preparedKey != next.videoId && preparing != next.videoId) {
            prepare(next)
        }
        if (automix && automixTransition(song, next, nextIndex, duration)) return
        val fadeMs = if (inParty || sleepAfter) 0L else (IosSettings.crossfadeSeconds.flow.value * 1000L).coerceAtMost(duration / 3)
        if (fadeMs > 0L && remaining in 1..fadeMs && engine.preparedFor(next.videoId)) {
            advancingFromEnd = true
            transition(nextIndex, fadeMs)
            advancingFromEnd = false
        }
    }

    /** Where the waiting player's track was last cued for Automix, so it is cued once per plan. */
    private var automixCue: Pair<String, Long>? = null

    /**
     * Automix: the shared planner (the phone's and the desktop's) decides from both songs' analyses
     * when the blend starts, how long it runs and where the next song comes in, every tick until it
     * starts. False when it declines — a blocked plan (too short, a live recording, …) — and the
     * ordinary crossfade decides instead. Without analyses yet, its plan is a plain fade.
     */
    private fun automixTransition(song: Song, next: Song, nextIndex: Int, durationMs: Long): Boolean {
        val crossfade = IosSettings.crossfadeSeconds.flow.value
        val plan = planTransition(
            analysis = IosAutomixAnalysis.analysisFor(song.videoId) ?: TrackAnalysis(),
            nextAnalysis = IosAutomixAnalysis.analysisFor(next.videoId) ?: TrackAnalysis(),
            currentTrack = song.transitionInfo(durationMs),
            nextTrack = next.transitionInfo(phoneLength(next)),
            currentTime = engine.position.positionMs / 1000.0,
            duration = durationMs / 1000.0,
            fadeSeconds = if (crossfade > 0) crossfade.toDouble() else AUTOMIX_FALLBACK_SECONDS,
            mode = CrossfadeMode.SMART,
            // The filters, echo and tempo ramps of the phone's advanced mode aren't built here.
            advanced = false,
            mixWindow = true,
        )
        if (plan.blocked) return false
        // Cued only once it has opened: a seek before then can be lost.
        if (!engine.readyFor(next.videoId)) return true
        val cueMs = (plan.incomingCueTime * 1000).toLong().coerceAtLeast(0L)
        if (automixCue != next.videoId to cueMs) {
            engine.cueNext(cueMs)
            automixCue = next.videoId to cueMs
        }
        if (!plan.shouldStart) return true
        TrackLog.i(
            TAG,
            "Automix: ${plan.transitionStyle} over ${plan.fadeMs}ms at ${engine.position.positionMs}ms, " +
                "next in at ${cueMs}ms" + if (plan.incomingPlaybackRate != 1.0) " ×${plan.incomingPlaybackRate}" else "",
        )
        automixCue = null
        advancingFromEnd = true
        transition(nextIndex, plan.fadeMs.coerceAtLeast(1L), cueMs, plan.incomingPlaybackRate.toFloat())
        advancingFromEnd = false
        return true
    }

    private fun Song.transitionInfo(durationMs: Long) = TransitionTrackInfo(
        id = videoId,
        durationMs = durationMs,
        title = title,
        artist = artist,
        album = albumName.orEmpty(),
        albumId = albumId.orEmpty(),
    )

    /** A copy of [videoId] on the phone — a download first, else the song cache — and its loudness. */
    private fun onPhone(videoId: String): Pair<String, Double?>? {
        // The listener's own file: played where it lies, AVPlayer or FFmpeg by its extension.
        if (IosLocalMusic.isLocal(videoId)) {
            servedBy[videoId] = "On this iPhone" to null
            return IosLocalMusic.audioPath(videoId)?.let { it to null }
        }
        IosDownloads.audioPath(videoId)?.let { path ->
            val track = IosDownloads.tracks.value.firstOrNull { it.videoId == videoId }
            servedBy[videoId] = "Downloaded" to track?.kbps?.takeIf { it > 0 }
            return path to track?.loudnessDb
        }
        val cached = IosSongCache.audio(videoId) ?: return null
        // A track FFmpeg couldn't play goes to AVPlayer, which can't open the cache's Opus copy.
        if (videoId in avPlayerOnly && IosAudioEngine.Route.of(cached.first).ffmpeg) return null
        servedBy[videoId] = "Song cache" to null
        return cached
    }

    private fun prepare(song: Song) {
        prepareJob?.cancel()
        preparing = song.videoId
        // Time the sources may have: up to the blend (Automix plans its own, well inside its lead) or the end.
        val reserve = if (IosSettings.automix.flow.value && !inParty) {
            AUTOMIX_PREPARE_AHEAD_MS - PREPARE_AHEAD_MS
        } else {
            IosSettings.crossfadeSeconds.flow.value * 1000L
        } + PREPARE_SPARE_MS
        val budgetMs = (engine.durationMs.value - engine.position.positionMs - reserve)
            .coerceIn(SUBSTITUTE_WAIT_MS, PREPARE_SUBSTITUTE_WAIT_MS)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        prepareJob = scope.launch {
            // What this job is readying: the queued track, or its audio release once swapped in.
            var readying = song.videoId
            try {
                onPhone(song.videoId)?.let { (path, loudness) ->
                    engine.prepareNext(path, emptyMap(), loudness, song.videoId, phoneLength(song))
                    return@launch
                }
                val audio = musicOnly(song)
                if (audio.videoId != song.videoId) {
                    val at = upcomingIndex()?.takeIf { _queue.value.getOrNull(it)?.videoId == song.videoId } ?: return@launch
                    _queue.value = _queue.value.toMutableList().also { it[at] = audio }
                    preparing = audio.videoId
                    readying = audio.videoId
                    onPhone(audio.videoId)?.let { (path, loudness) ->
                        engine.prepareNext(path, emptyMap(), loudness, audio.videoId, phoneLength(audio))
                        return@launch
                    }
                }
                var stream = networkStream(audio, budgetMs) ?: return@launch
                noteUpgradeLead(audio, stream)
                stream = betterBeforeStart(audio, stream, budgetMs - started.elapsedNow().inWholeMilliseconds)
                // The queue may have moved on while that resolved.
                if (upcomingIndex()?.let { _queue.value.getOrNull(it)?.videoId } != audio.videoId) return@launch
                TrackLog.i(TAG, "${audio.videoId}: readied from ${stream.source}, ${stream.kbps} kbps")
                servedBy[audio.videoId] = stream.source to stream.kbps
                engine.prepareNext(stream.url, stream.headers, stream.loudnessDb, audio.videoId, stream.durationMs, stream.route)
                if (stream.fromYouTube) IosSongCache.remember(audio.videoId, stream.url, stream.headers, stream.loudnessDb, stream.route.ffmpeg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "${song.videoId}: could not ready: ${e.message}")
            } finally {
                if (preparing == readying) preparing = null
            }
        }
    }

    /** Moves the queue on to [index], whose track the waiting player already holds. */
    private fun transition(index: Int, fadeMs: Long, startAtMs: Long = 0L, incomingRate: Float = 1f) {
        val song = _queue.value.getOrNull(index) ?: return
        loadJob?.cancel()
        _resolving.value = false
        _index.value = index
        playedThisSession += song.videoId
        IosScrobbler.onSongChanged(_song.value, advancingFromEnd, song)
        _song.value = song
        _error.value = null
        if (autoplayOn && _queue.value.size > 1 && index >= _queue.value.size - 2) {
            extendWithRadio(playNext = false)
        }
        upgradeJob?.cancel()
        engine.startNext(fadeMs, startAtMs, incomingRate)
        // The engine never stops between the two tracks, so the new one is started here.
        IosScrobbler.onPlaying(engine.isPlaying.value, 0L)
        lookForBetterCopy(song)
    }

    /** The last song's radio after the queue; played straight away when the queue has run out. */
    private fun extendWithRadio(playNext: Boolean) {
        // Seeded from the last YouTube track: a local file has no id to seed it with.
        val last = _queue.value.lastOrNull { !isOwnFile(it) }
            ?: return if (playNext && _queue.value.isNotEmpty()) engine.pause() else Unit
        if (extending) return
        // One supplier per party, as the phone has it: the host tops the queue up for everyone.
        val party = IosListenTogether.state.value
        if (party.inParty && party.you?.isHost != true) return
        extending = true
        scope.launch {
            YtMusicRepository.radio(last.videoId).onSuccess { radio ->
                val known = _queue.value.map { it.videoId }.toSet() +
                    if (IosSettings.dontRepeatSongs.flow.value) playedThisSession else emptySet()
                val added = radio.filter { it.videoId !in known }
                if (added.isEmpty()) return@onSuccess
                val start = _queue.value.size
                _queue.value = _queue.value + added
                if (playNext && _index.value == start - 1) playAt(start)
                intent(if (playNext) added.first().videoId else null)
            }.onFailure { if (playNext) engine.pause() }
            extending = false
        }
    }

    fun previous() {
        if (refusedByHost()) return
        // Back to the start first, as every player does a few seconds in.
        if (engine.position.positionMs > 3_000 || _index.value <= 0) {
            engine.seekTo(0)
            intent()
        } else {
            playAt(_index.value - 1)
            intent(_queue.value[_index.value].videoId)
        }
    }

    fun seekTo(positionMs: Long) {
        if (refusedByHost()) return
        engine.seekTo(positionMs)
        intent()
        // The card counts down from instants; a seek moves them.
        IosDiscord.refresh()
    }

    private fun playAt(index: Int, startMs: Long = 0L, startPlaying: Boolean = true) {
        // "After this song": the track that follows waits, paused, at its start.
        val play = startPlaying && !pauseOnNextStart
        pauseOnNextStart = false
        val song = _queue.value.getOrNull(index) ?: return
        // Already waiting in the second player: it starts at once.
        if (song.videoId != _song.value?.videoId && engine.preparedFor(song.videoId)) {
            transition(index, 0L)
            if (startMs > 0L) engine.seekTo(startMs)
            if (!play) engine.pause()
            return
        }
        _index.value = index
        playedThisSession += song.videoId
        IosScrobbler.onSongChanged(_song.value, advancingFromEnd, song)
        _song.value = song
        _error.value = null
        // Fetched ahead, so the next song is already queued when this one ends. A queue of one is
        // a radio still loading (see playRadio).
        if (autoplayOn && _queue.value.size > 1 && index >= _queue.value.size - 2) {
            extendWithRadio(playNext = false)
        }
        engine.stop()
        loadJob?.cancel()
        upgradeJob?.cancel()
        loadJob = scope.launch {
            _resolving.value = true
            try {
                // A download plays from the phone, connection or not.
                onPhone(song.videoId)?.let { (path, loudness) ->
                    TrackLog.i(TAG, "${song.videoId}: from the phone")
                    engine.load(path, emptyMap(), loudness, song.videoId, phoneLength(song), startMs, play)
                    lookForBetterCopy(song)
                    return@launch
                }
                val audio = musicOnly(song)
                if (audio.videoId != song.videoId) {
                    // The video's details stood in while its audio release was found; now the
                    // queue and the player name the track that is actually heard.
                    if (_queue.value.getOrNull(index)?.videoId == song.videoId) {
                        _queue.value = _queue.value.toMutableList().also { it[index] = audio }
                    }
                    playedThisSession += audio.videoId
                    _song.value = audio
                    onPhone(audio.videoId)?.let { (path, loudness) ->
                        engine.load(path, emptyMap(), loudness, audio.videoId, phoneLength(audio), startMs, play)
                        lookForBetterCopy(audio)
                        return@launch
                    }
                }
                val stream = networkStream(audio)
                if (stream == null) {
                    fail(tr("No playable stream for this track"))
                    return@launch
                }
                TrackLog.i(TAG, "${audio.videoId}: from ${stream.source}, ${stream.kbps} kbps")
                servedBy[audio.videoId] = stream.source to stream.kbps
                engine.load(stream.url, stream.headers, stream.loudnessDb, audio.videoId, stream.durationMs, startMs, play, stream.route)
                if (stream.fromYouTube) IosSongCache.remember(audio.videoId, stream.url, stream.headers, stream.loudnessDb, stream.route.ffmpeg)
                noteUpgradeLead(audio, stream)
                lookForBetterCopy(audio)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            } finally {
                _resolving.value = false
            }
        }
    }

    /** The other cut of each track switched by [switchVersion], both ways, so switching back is instant. */
    private val otherCut = HashMap<String, Song>()
    private var versionJob: Job? = null

    private val _versionSwitching = MutableStateFlow(false)

    /** A version switch is finding the other cut and its stream. */
    val versionSwitching: StateFlow<Boolean> = _versionSwitching.asStateFlow()

    private val _versionAligning = MutableStateFlow(false)

    /** Smart audio alignment is fetching and measuring the other cut; the player draws it on the bar. */
    val versionAligning: StateFlow<Boolean> = _versionAligning.asStateFlow()

    /**
     * [song]'s other cut: a music video's catalogue audio release, or a song's music video. Null
     * when there is none, and for a file on the phone or a source's track, which have no other.
     */
    suspend fun alternateVersion(song: Song): Song? {
        otherCut[song.videoId]?.let { return it }
        if (isOwnFile(song) || IosSources.isSourceTrack(song)) return null
        val other = runCatching {
            if (song.isVideo) YtMusicRepository.resolveAudio(song) else YtMusicRepository.resolveVideo(song)
        }.getOrNull()
        return other?.takeIf { it.videoId != song.videoId }
    }

    /**
     * The phone's Convert to audio / Convert to video: the playing track moves onto its other cut
     * mid-play, blended over [VERSION_FADE_MS]. With smart audio alignment on, the two openings are
     * measured first and the other cut comes in on the same moment of the music — past a video's
     * intro skit, say — rather than the same second; without it, or when the measurement fails, at
     * the same second. Not in a party, where everyone hears one cut.
     */
    fun switchVersion() {
        val song = _song.value ?: return
        if (inParty || _versionSwitching.value) return
        if (engine.isBlending) {
            IosPlayerHost.showMessage(tr("Try again once this transition ends"))
            return
        }
        versionJob?.cancel()
        versionJob = scope.launch {
            _versionSwitching.value = true
            try {
                val found = alternateVersion(song)
                if (found == null) {
                    IosPlayerHost.showMessage(if (song.isVideo) tr("No audio version found") else tr("No video version found"))
                    return@launch
                }
                val target = found.copy(
                    isVideoOrigin = song.isVideoOrigin || song.isVideo,
                    queueTier = song.queueTier,
                    queueEntryId = song.queueEntryId,
                    radioName = song.radioName,
                    playbackSource = song.playbackSource,
                    playbackSourceType = song.playbackSourceType,
                    playbackSourceId = song.playbackSourceId,
                )
                TrackLog.i(TAG, "${song.videoId}: switching to ${if (target.isVideo) "the video" else "the audio"} ${target.videoId}")
                val offsetMs = if (IosSettings.smartAlignment.flow.value) {
                    _versionAligning.value = true
                    try {
                        IosVersionAligner.offsetMs(song, target) ?: 0L
                    } finally {
                        _versionAligning.value = false
                    }
                } else {
                    0L
                }
                if (_song.value?.videoId != song.videoId) return@launch
                val local = onPhone(target.videoId)
                val stream = if (local == null) networkStream(target) else null
                if (local == null && stream == null) {
                    IosPlayerHost.showMessage(tr("Couldn’t load the other version"))
                    return@launch
                }
                if (_song.value?.videoId != song.videoId) return@launch
                val url = local?.first ?: stream!!.url
                val headers = stream?.headers ?: emptyMap()
                val loudness = local?.second ?: stream?.loudnessDb
                val duration = stream?.durationMs ?: phoneLength(target)
                val route = stream?.route ?: IosAudioEngine.Route.of(url)
                // The waiting player is the swap's; the next track is readied again afterwards.
                prepareJob?.cancel()
                preparing = null
                engine.clearPrepared()
                upgradeJob?.cancel()
                val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
                engine.swapStream(url, headers, loudness, duration, route, target.videoId, offsetMs, VERSION_FADE_MS) { done.complete(it) }
                val swapped = done.await()
                if (_song.value?.videoId != song.videoId) return@launch
                if (!swapped) {
                    TrackLog.w(TAG, "${song.videoId}: the blend didn't take; switching straight over")
                    val at = (engine.position.positionMs + offsetMs).coerceAtLeast(0L)
                    engine.load(url, headers, loudness, target.videoId, duration, at, engine.isPlaying.value, route)
                }
                otherCut[song.videoId] = target
                otherCut[target.videoId] = song
                stream?.let {
                    servedBy[target.videoId] = it.source to it.kbps
                    if (it.fromYouTube) IosSongCache.remember(target.videoId, it.url, it.headers, it.loudnessDb, it.route.ffmpeg)
                }
                val at = _index.value
                if (_queue.value.getOrNull(at)?.videoId == song.videoId) {
                    _queue.value = _queue.value.toMutableList().also { it[at] = target }
                }
                playedThisSession += target.videoId
                IosListeningRecorder.switched(song.videoId, target.videoId)
                _song.value = target
                TrackLog.i(TAG, "${target.videoId}: switched, ${offsetMs} ms shift")
            } finally {
                _versionSwitching.value = false
                _versionAligning.value = false
            }
        }
    }

    /**
     * "Prefer music-only version": a music video's catalogue audio release, carrying the video's
     * place in the queue; the video itself when the setting is off or nothing matches.
     */
    private suspend fun musicOnly(song: Song): Song {
        // A party plays one cut of a song for everyone, so no device swaps its own.
        if (!IosSettings.preferMusicOnly.flow.value || !song.isVideo || inParty) return song
        val match = runCatching { YtMusicRepository.resolveAudio(song) }.getOrDefault(song)
        if (match.videoId == song.videoId) return song
        return match.copy(
            playbackSource = song.playbackSource,
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
    }

    /** A stream to open over the network, and where it came from. */
    private class Picked(
        val url: String,
        val headers: Map<String, String>,
        val loudnessDb: Double?,
        val durationMs: Long,
        val source: String,
        val kbps: Int?,
        val fromYouTube: Boolean,
        /** Which player opens it: FFmpeg for Opus, Vorbis, WebM, Ogg and DASH. */
        val route: IosAudioEngine.Route,
        val codec: String? = null,
        /** What a source said it served; null for YouTube's own. */
        val format: StreamFormat? = null,
    ) {
        /** The source lookup that lost the start to YouTube, still going; see [lookForBetterCopy]. */
        var lookup: Deferred<ResolvedStream?>? = null
    }

    /** What a track was started on short of the best, for [lookForBetterCopy] once it plays. */
    private class UpgradeLead(
        val lookup: Deferred<ResolvedStream?>?,
        val playing: StreamFormat?,
        val servedBy: String?,
    )

    private val upgradeLeads = HashMap<String, UpgradeLead>()
    private var upgradeJob: Job? = null

    /**
     * Marks [song] for a second look once it plays: when YouTube served it, and — the phone's
     * `settledForLess` — when a source above YouTube answered first with less than was asked for,
     * JioSaavn's 320 kbps AAC under Lossless being the everyday case. That second look starts here
     * rather than once the track settles, leaving out the source already serving it and waiting on
     * every other one, so a slow addon's FLAC is often in hand by the time the track is playing.
     */
    private fun noteUpgradeLead(song: Song, stream: Picked) {
        val videoId = song.videoId
        val request = IosSources.requestFor(IosSettings.effectiveAudioQuality)
        val settled = !stream.fromYouTube && request is StreamRequest.Lossless &&
            stream.format?.let { it.isLossless != true && !it.isDolbyAtmos } == true
        if (!stream.fromYouTube && !settled) return
        upgradeLeads.remove(videoId)?.lookup?.cancel()
        val playing = stream.format ?: StreamFormat(codec = stream.codec, kbps = stream.kbps)
        val lookup = if (settled) {
            TrackLog.d(TAG, "$videoId: ${stream.source} gave ${playing.summary}, below the request; looking further")
            scope.async {
                kotlinx.coroutines.withTimeoutOrNull(SUBSTITUTE_LOOKUP_MS) {
                    runCatching { IosSources.upgradeFor(song, request, playing, stream.source) }.getOrNull()
                }
            }
        } else {
            stream.lookup
        }
        upgradeLeads[videoId] = UpgradeLead(lookup, playing, stream.source.takeIf { settled })
    }

    /**
     * The better copy [stream]'s upgrade lead finds within [budgetMs], taken before the track
     * starts rather than swapped in once it plays — for a track readied ahead, where the time is
     * free. Else [stream], its lead left for [lookForBetterCopy].
     */
    private suspend fun betterBeforeStart(song: Song, stream: Picked, budgetMs: Long): Picked {
        if (budgetMs <= 0L) return stream
        val lead = upgradeLeads[song.videoId] ?: return stream
        val lookup = lead.lookup ?: return stream
        val found = kotlinx.coroutines.withTimeoutOrNull(budgetMs) { runCatching { lookup.await() }.getOrNull() }
            ?: return stream
        val lengthSec = (phoneLength(song) / 1000).toInt().takeIf { it > 0 }
        if (!IosSources.worthSwapping(found.format, lead.playing) ||
            !IosSources.sameRecordingAs(found.durationMs?.let { (it / 1000).toInt() }, lengthSec)
        ) {
            return stream
        }
        upgradeLeads.remove(song.videoId)
        TrackLog.i(TAG, "${song.videoId}: readied on ${found.sourceName}, ${found.format.summary}, before it starts")
        // Still short of the request — JioSaavn's AAC in place of YouTube's — it gets its own second look.
        return found.picked(song).also { noteUpgradeLead(song, it) }
    }

    /**
     * The phone's quality upgrade: a track that started on YouTube (or its cached copy) because no
     * better source answered in time is swapped, mid-play and at the same point, onto a better copy
     * when one turns up — the lookup that lost the start if it finishes with one, else a fresh ask
     * of every source ranked above YouTube. Only a copy worth the swap (lossless, or 96 kbps more)
     * of the same recording (length within two seconds), and only with 20 s or more still to play.
     */
    private fun lookForBetterCopy(song: Song, byHand: Boolean = false) {
        upgradeJob?.cancel()
        val key = song.videoId
        val lead = upgradeLeads.remove(key)
            ?: if (servedBy[key]?.first == "Song cache") UpgradeLead(null, null, null) else return
        // Sent back to YouTube's own by the listener: only their "Upgrade quality" looks again.
        if (song.isVideo || inParty || IosSources.isSourceTrack(song) || (!byHand && IosOriginalVersion.isPinned(key))) {
            lead.lookup?.cancel()
            return
        }
        val request = IosSources.requestFor(IosSettings.effectiveAudioQuality)
        if (!IosSources.canSubstituteForYouTube(request)) {
            lead.lookup?.cancel()
            return
        }
        upgradeJob = scope.launch {
            try {
                // The phone's wait: only until the decoder reports the length a candidate must match.
                kotlinx.coroutines.withTimeoutOrNull(UPGRADE_SETTLE_MS) {
                    while (engine.durationMs.value <= 0L || !engine.isPlaying.value) delay(UPGRADE_SETTLE_STEP_MS)
                }
                if (_song.value?.videoId != key) return@launch
                // A length the track came with beats the decoder's: AVPlayer's guess at a
                // fragmented MP4 can be twice the truth, and then no copy would match.
                val lengthMs = phoneLength(song).takeIf { it > 0 } ?: engine.durationMs.value
                val lengthSec = (lengthMs / 1000).toInt().takeIf { it > 0 }
                val raced = lead.lookup?.await()?.takeIf {
                    IosSources.worthSwapping(it.format, lead.playing) &&
                        IosSources.sameRecordingAs(it.durationMs?.let { ms -> (ms / 1000).toInt() }, lengthSec)
                }
                val found = raced ?: runCatching {
                    IosSources.upgradeFor(song.copy(durationText = lengthSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: song.durationText), request, lead.playing, lead.servedBy)
                }.getOrNull()
                if (found == null) {
                    if (byHand && _song.value?.videoId == key) IosPlayerHost.showMessage(tr("No better copy found"))
                    return@launch
                }
                if (_song.value?.videoId != key) return@launch
                val remaining = lengthMs - engine.position.positionMs
                if (remaining < UPGRADE_MIN_REMAINING_MS || engine.isBlending) {
                    TrackLog.d(TAG, "$key: upgrade to ${found.sourceName} passed over (${remaining}ms left)")
                    return@launch
                }
                val stream = found.picked(song)
                TrackLog.i(TAG, "$key: upgrading to ${found.sourceName}, ${found.format.summary}")
                fun swap(route: IosAudioEngine.Route) {
                    engine.swapStream(stream.url, stream.headers, stream.loudnessDb, stream.durationMs, route) { swapped ->
                        when {
                            _song.value?.videoId != key -> Unit
                            swapped -> {
                                servedBy[key] = found.sourceName to found.format.kbps
                                TrackLog.i(TAG, "$key: upgraded to ${found.sourceName}")
                            }
                            // AVPlayer turning down a copy FFmpeg can open — a FLAC from a URL with
                            // no extension to go by — is a second try, not a lost upgrade.
                            !route.ffmpeg && !engine.isBlending -> {
                                TrackLog.w(TAG, "$key: AVPlayer refused ${found.sourceName}'s stream; trying FFmpeg")
                                swap(IosAudioEngine.Route(ffmpeg = true))
                            }
                            else -> TrackLog.w(TAG, "$key: upgrade to ${found.sourceName} didn't take")
                        }
                    }
                }
                swap(stream.route)
            } finally {
                lead.lookup?.cancel()
                if (byHand && _upgradingByHand.value == key) _upgradingByHand.value = null
            }
        }
    }

    private val _upgradingByHand = MutableStateFlow<String?>(null)

    /** The track "Upgrade quality" is looking for a better copy of, while it looks. */
    val upgradingByHand: StateFlow<String?> = _upgradingByHand

    /** What [song] is playing from, while it is the track playing. */
    private fun playingFrom(song: Song): String? = servedBy[song.videoId]?.first.takeIf { _song.value?.videoId == song.videoId }

    private val ownCopies = setOf("YouTube", "Song cache")
    private val onPhoneCopies = setOf("On this iPhone", "Downloaded")

    /**
     * Whether the menu offers "Revert to original" for [song]: playing, from a source standing in
     * for YouTube's upload — whether an upgrade swapped it in or it played from the first second,
     * since a wrong match sounds wrong either way.
     */
    fun canRevertToOriginal(song: Song): Boolean {
        val from = playingFrom(song) ?: return false
        return from !in ownCopies && from !in onPhoneCopies && !song.isVideo && !inParty &&
            !IosSources.isSourceTrack(song) && !isOwnFile(song) && !IosOriginalVersion.isPinned(song.videoId)
    }

    /**
     * Whether the menu offers "Upgrade quality" for [song]: playing YouTube's own copy, by the
     * listener's revert or because nothing better answered, with a source that could beat it.
     */
    fun canUpgradeQuality(song: Song): Boolean {
        val from = playingFrom(song) ?: return false
        if (from in onPhoneCopies || song.isVideo || inParty || IosSources.isSourceTrack(song) || isOwnFile(song)) return false
        if (from !in ownCopies && !IosOriginalVersion.isPinned(song.videoId)) return false
        return IosSources.canSubstituteForYouTube(IosSources.requestFor(IosSettings.effectiveAudioQuality))
    }

    /** The phone's "Revert to original": the playing track back on YouTube's own upload, kept there. */
    fun revertToOriginal() {
        val song = _song.value ?: return
        if (!canRevertToOriginal(song)) return
        val key = song.videoId
        IosOriginalVersion.pin(key)
        upgradeJob?.cancel()
        upgradeLeads.remove(key)?.lookup?.cancel()
        scope.launch {
            val request = IosSources.requestFor(IosSettings.effectiveAudioQuality)
            val cap = (request as? StreamRequest.Capped)?.maxKbps?.coerceAtMost(320) ?: 320
            val stream = runCatching { youtube(key, song, cap, opusAllowed(request, key)) }.getOrNull()
            if (_song.value?.videoId != key) return@launch
            if (stream == null) {
                IosPlayerHost.showMessage(tr("Could not switch to the original"))
                return@launch
            }
            TrackLog.i(TAG, "$key: back to YouTube's own, as asked")
            engine.swapStream(stream.url, stream.headers, stream.loudnessDb, stream.durationMs, stream.route) { swapped ->
                if (_song.value?.videoId != key) return@swapStream
                if (swapped) {
                    servedBy[key] = stream.source to stream.kbps
                    IosSongCache.remember(key, stream.url, stream.headers, stream.loudnessDb, stream.route.ffmpeg)
                } else {
                    IosPlayerHost.showMessage(tr("Could not switch to the original"))
                }
            }
        }
    }

    /** The phone's "Upgrade quality": look for a better copy of the playing track now. */
    fun upgradeQuality() {
        val song = _song.value ?: return
        if (!canUpgradeQuality(song) || _upgradingByHand.value == song.videoId) return
        val key = song.videoId
        IosOriginalVersion.unpin(key)
        TrackLog.d(TAG, "$key: upgrade asked for by hand")
        val kbps = servedBy[key]?.second
        upgradeLeads.remove(key)?.lookup?.cancel()
        upgradeLeads[key] = UpgradeLead(null, kbps?.let { StreamFormat(kbps = it) }, null)
        _upgradingByHand.value = key
        lookForBetterCopy(song, byHand = true)
    }

    /**
     * The stream for [audio], as the phone's resolver picks it, under the ceiling for the
     * connection in hand (Settings › On Wi-Fi / On mobile data).
     *
     * A row from an add-on or JioSaavn plays from its own source, falling back to the next one and
     * last to YouTube's match. A YouTube track is offered to the sources the listener ranked above
     * YouTube while YouTube's own stream resolves beside it, and the first to answer plays — a
     * slower source becomes the track's upgrade instead. A track readied ahead gives the sources
     * up to [substituteWaitMs] first. YouTube failing leaves the sources [SUBSTITUTE_WAIT_MS].
     */
    private suspend fun networkStream(audio: Song, substituteWaitMs: Long = SUBSTITUTE_WAIT_MS): Picked? = kotlinx.coroutines.coroutineScope {
        // A local file that has gone has nothing to ask YouTube or the sources for.
        if (IosLocalMusic.isLocal(audio)) return@coroutineScope null
        // A file on the listener's server streams from there, as it is, with their login.
        if (IosWebDav.isWebDav(audio)) {
            val (url, headers) = IosWebDav.stream(audio.videoId) ?: return@coroutineScope null
            return@coroutineScope Picked(
                url = url,
                headers = headers,
                loudnessDb = null,
                durationMs = trackLength(null, audio),
                source = "WebDAV",
                kbps = null,
                fromYouTube = false,
                route = IosAudioEngine.Route.of(url),
            )
        }
        val request = IosSources.requestFor(IosSettings.effectiveAudioQuality)
        val cap = (request as? StreamRequest.Capped)?.maxKbps?.coerceAtMost(320) ?: 320
        if (IosSources.isSourceTrack(audio)) {
            IosSources.resolve(audio, request)?.let { return@coroutineScope it.picked(audio) }
            val id = IosSources.youtubeFallback(audio) ?: return@coroutineScope null
            return@coroutineScope youtube(id, audio, cap, opusAllowed(request, audio.videoId))
        }
        val fromYouTube = async { youtube(audio.videoId, audio, cap, opusAllowed(request, audio.videoId)) }
        // In the player's scope rather than this one: a lookup that loses the start goes on, to
        // become the track's upgrade (see lookForBetterCopy).
        // A track sent back to YouTube's own isn't offered to the sources until asked for again.
        val lookup = if (!audio.isVideo && !IosOriginalVersion.isPinned(audio.videoId) && IosSources.canSubstituteForYouTube(request)) {
            scope.async {
                kotlinx.coroutines.withTimeoutOrNull(SUBSTITUTE_LOOKUP_MS) {
                    runCatching { IosSources.substituteForYouTube(audio, request) }.getOrNull()
                }
            }
        } else {
            null
        }
        if (lookup != null) {
            // A track asked for now goes to whichever answers first, as on the phone: YouTube's
            // URL usually, while the sources' lossless copy follows as a swap a few seconds in.
            // Only a track readied ahead, which nobody is waiting on, holds out for the sources.
            val holdOut = substituteWaitMs > SUBSTITUTE_WAIT_MS
            val substitute = kotlinx.coroutines.withTimeoutOrNull(substituteWaitMs) {
                if (holdOut) {
                    lookup.await()
                } else {
                    kotlinx.coroutines.selects.select<ResolvedStream?> {
                        lookup.onAwait { it }
                        // A YouTube that came back empty has won nothing: the sources are all there is.
                        fromYouTube.onAwait { picked -> if (picked != null) null else lookup.await() }
                    }
                }
            }
            if (substitute != null) {
                fromYouTube.cancel()
                return@coroutineScope substitute.picked(audio)
            }
        }
        val picked = try {
            fromYouTube.await()
        } catch (e: Throwable) {
            lookup?.cancel()
            throw e
        }
        picked?.also { it.lookup = lookup } ?: run {
            lookup?.cancel()
            null
        }
    }

    /**
     * Whether YouTube may be asked for its best rendition — Opus at about 160 kbps, which the
     * FFmpeg player plays — rather than the 128 kbps AAC AVPlayer can: above Low, with the
     * setting on, and not for a track whose Opus already failed this session.
     */
    private fun opusAllowed(request: StreamRequest, key: String): Boolean =
        IosSettings.youtubeOpus.flow.value && key !in avPlayerOnly &&
            !(request is StreamRequest.Capped && request.maxKbps <= LOW_KBPS)

    /** YouTube's stream: its best (usually Opus in WebM) when [opus], else AAC in MP4. */
    private suspend fun youtube(videoId: String, song: Song, capKbps: Int, opus: Boolean = false): Picked? {
        if (opus) {
            val best = runCatching { InnerTubeXResolver.extract(videoId, maxKbps = 320) }.getOrNull()
            if (best != null) {
                return Picked(
                    url = best.url,
                    headers = best.headers,
                    loudnessDb = best.loudnessDb,
                    durationMs = trackLength(best.url, song),
                    source = "YouTube",
                    kbps = best.kbps.takeIf { it > 0 },
                    fromYouTube = true,
                    route = IosAudioEngine.Route.of(best.url, best.mimeType),
                    codec = best.mimeType?.let { if ("opus" in it) "opus" else if ("mp4a" in it || "mp4" in it) "aac" else null },
                )
            }
        }
        val stream = InnerTubeXResolver.extract(videoId, maxKbps = capKbps, requireM4a = true)
            // Low asks for less than most tracks have in AAC; the smallest there is beats nothing.
            ?: (if (capKbps < 320) InnerTubeXResolver.extract(videoId, maxKbps = 320, requireM4a = true) else null)
            ?: return null
        return Picked(
            url = stream.url,
            headers = stream.headers,
            loudnessDb = stream.loudnessDb,
            durationMs = trackLength(stream.url, song),
            source = "YouTube",
            kbps = stream.kbps.takeIf { it > 0 },
            fromYouTube = true,
            route = IosAudioEngine.Route.AVPLAYER,
            codec = "aac",
        )
    }

    private fun ResolvedStream.picked(song: Song) = Picked(
        url = url,
        headers = headers,
        loudnessDb = null,
        durationMs = durationMs?.takeIf { it > 0 } ?: trackLength(null, song),
        source = sourceName,
        kbps = format.kbps,
        fromYouTube = false,
        route = if (dash) IosAudioEngine.Route(ffmpeg = true, dash = true) else IosAudioEngine.Route.of(url, format.codec),
        codec = format.codec,
        format = format,
    )

    /**
     * How long the track really is: googlevideo's `dur` parameter (seconds, exact), else the
     * catalogue's length, else 0 for the engine to estimate.
     */
    private fun trackLength(url: String?, song: Song): Long {
        val dur = url?.let { Regex("[?&]dur=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        if (dur != null && dur > 0) return (dur * 1000).toLong()
        return song.durationMillis().coerceAtLeast(0L)
    }

    /**
     * How long a copy on the phone of [song] runs: the catalogue's figure, else the length the song
     * cache kept from its stream, else a download's — 0 leaves it to the decoder.
     */
    private fun phoneLength(song: Song): Long =
        trackLength(null, song).takeIf { it > 0 }
            ?: IosSongCache.lengthMs(song.videoId)
            ?: IosDownloads.tracks.value.firstOrNull { it.videoId == song.videoId }?.durationText
                ?.let { song.copy(durationText = it).durationMillis() }?.takeIf { it > 0 }
            ?: 0L

    private fun fail(message: String) {
        TrackLog.w(TAG, "${_song.value?.videoId}: $message")
        _error.value = message
        engine.stop()
    }

    private fun publishNowPlaying(song: Song?, playing: Boolean, durationMs: Long) {
        val center = MPNowPlayingInfoCenter.defaultCenter()
        if (song == null) {
            center.nowPlayingInfo = null
            return
        }
        center.nowPlayingInfo = mapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to song.title,
            MPMediaItemPropertyArtist to song.artist,
            MPMediaItemPropertyPlaybackDuration to durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to engine.position.positionMs / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (playing) 1.0 else 0.0,
        )
    }

    private fun wireRemoteCommands() {
        val commands = MPRemoteCommandCenter.sharedCommandCenter()
        fun handled(action: () -> Unit): MPRemoteCommandHandlerStatus {
            action()
            return MPRemoteCommandHandlerStatusSuccess
        }
        commands.playCommand.addTargetWithHandler { _ -> handled { setPlaying(true) } }
        commands.pauseCommand.addTargetWithHandler { _ -> handled { setPlaying(false) } }
        commands.togglePlayPauseCommand.addTargetWithHandler { _ -> handled(::togglePlayPause) }
        commands.nextTrackCommand.addTargetWithHandler { _ -> handled(::next) }
        commands.previousTrackCommand.addTargetWithHandler { _ -> handled(::previous) }
        commands.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
                ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            handled { seekTo((seconds * 1000).toLong()) }
        }
    }
}
