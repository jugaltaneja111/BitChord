package com.music.bitchord.ios

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

    /** How long before a track ends the next one is fetched into the waiting player. */
    private const val PREPARE_AHEAD_MS = 30_000L
    private const val TRANSITION_POLL_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private val engine = IosAudioEngine(onEnded = ::onEnded, onFailed = ::fail)

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
        IosPlayerHost.showMessage("Only the host can change what’s playing")
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
        IosPlayerHost.showMessage(if (songs.size == 1) "Playing next" else "${songs.size} songs playing next")
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
        IosPlayerHost.showMessage(if (songs.size == 1) "Added to queue" else "${songs.size} songs added to queue")
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

    private fun onEnded() {
        if (_repeatMode.value == RepeatModes.ONE) {
            _song.value?.let { IosScrobbler.onSongChanged(it, ended = true, song = it) }
            engine.seekTo(0)
            engine.play()
            // Still playing, so the play state never changes: the repeat starts here.
            IosScrobbler.onPlaying(true, 0L)
        } else {
            advancingFromEnd = true
            next()
            advancingFromEnd = false
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
        if (_resolving.value || !engine.isPlaying.value || engine.isBlending) return
        val duration = engine.durationMs.value
        if (duration <= 0L) return
        val remaining = duration - engine.position.positionMs
        val nextIndex = upcomingIndex()
        val next = nextIndex?.let { _queue.value.getOrNull(it) }
        if (next == null || next.videoId == song.videoId) {
            if (engine.preparedKey != null && preparing == null) engine.clearPrepared()
            return
        }
        if (remaining <= PREPARE_AHEAD_MS && engine.preparedKey != next.videoId && preparing != next.videoId) {
            prepare(next)
        }
        // A party keeps every device on one timeline: a blend would put this one ahead of it.
        val fadeMs = if (inParty) 0L else (IosSettings.crossfadeSeconds.flow.value * 1000L).coerceAtMost(duration / 3)
        if (fadeMs > 0L && remaining in 1..fadeMs && engine.preparedFor(next.videoId)) {
            advancingFromEnd = true
            transition(nextIndex, fadeMs)
            advancingFromEnd = false
        }
    }

    /** A copy of [videoId] on the phone — a download first, else the song cache — and its loudness. */
    private fun onPhone(videoId: String): Pair<String, Double?>? =
        IosDownloads.audioPath(videoId)?.let { path ->
            path to IosDownloads.tracks.value.firstOrNull { it.videoId == videoId }?.loudnessDb
        } ?: IosSongCache.audio(videoId)

    private fun prepare(song: Song) {
        prepareJob?.cancel()
        preparing = song.videoId
        prepareJob = scope.launch {
            // What this job is readying: the queued track, or its audio release once swapped in.
            var readying = song.videoId
            try {
                onPhone(song.videoId)?.let { (path, loudness) ->
                    engine.prepareNext(path, emptyMap(), loudness, song.videoId, trackLength(null, song))
                    return@launch
                }
                val audio = musicOnly(song)
                if (audio.videoId != song.videoId) {
                    val at = upcomingIndex()?.takeIf { _queue.value.getOrNull(it)?.videoId == song.videoId } ?: return@launch
                    _queue.value = _queue.value.toMutableList().also { it[at] = audio }
                    preparing = audio.videoId
                    readying = audio.videoId
                    onPhone(audio.videoId)?.let { (path, loudness) ->
                        engine.prepareNext(path, emptyMap(), loudness, audio.videoId, trackLength(null, audio))
                        return@launch
                    }
                }
                val stream = InnerTubeXResolver.extract(audio.videoId, maxKbps = 320, requireM4a = true) ?: return@launch
                // The queue may have moved on while that resolved.
                if (upcomingIndex()?.let { _queue.value.getOrNull(it)?.videoId } != audio.videoId) return@launch
                TrackLog.i(TAG, "${audio.videoId}: readied ${stream.clientName} ${stream.mimeType} ${stream.kbps} kbps")
                engine.prepareNext(stream.url, stream.headers, stream.loudnessDb, audio.videoId, trackLength(stream.url, audio))
                IosSongCache.remember(audio.videoId, stream.url, stream.headers, stream.loudnessDb)
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
    private fun transition(index: Int, fadeMs: Long) {
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
        engine.startNext(fadeMs)
        // The engine never stops between the two tracks, so the new one is started here.
        IosScrobbler.onPlaying(engine.isPlaying.value, 0L)
    }

    /** The last song's radio after the queue; played straight away when the queue has run out. */
    private fun extendWithRadio(playNext: Boolean) {
        val last = _queue.value.lastOrNull() ?: return
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
        val song = _queue.value.getOrNull(index) ?: return
        // Already waiting in the second player: it starts at once.
        if (song.videoId != _song.value?.videoId && engine.preparedFor(song.videoId)) {
            transition(index, 0L)
            if (startMs > 0L) engine.seekTo(startMs)
            if (!startPlaying) engine.pause()
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
        loadJob = scope.launch {
            _resolving.value = true
            try {
                // A download plays from the phone, connection or not.
                onPhone(song.videoId)?.let { (path, loudness) ->
                    TrackLog.i(TAG, "${song.videoId}: from the phone")
                    engine.load(path, emptyMap(), loudness, song.videoId, trackLength(null, song), startMs, startPlaying)
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
                        engine.load(path, emptyMap(), loudness, audio.videoId, trackLength(null, audio), startMs, startPlaying)
                        return@launch
                    }
                }
                // AAC in MP4: AVFoundation cannot play the Opus/WebM streams the phone prefers.
                val stream = InnerTubeXResolver.extract(audio.videoId, maxKbps = 320, requireM4a = true)
                if (stream == null) {
                    fail("No playable stream for this track")
                    return@launch
                }
                TrackLog.i(TAG, "${audio.videoId}: ${stream.clientName} ${stream.mimeType} ${stream.kbps} kbps")
                engine.load(stream.url, stream.headers, stream.loudnessDb, audio.videoId, trackLength(stream.url, audio), startMs, startPlaying)
                IosSongCache.remember(audio.videoId, stream.url, stream.headers, stream.loudnessDb)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            } finally {
                _resolving.value = false
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

    /**
     * How long the track really is: googlevideo's `dur` parameter (seconds, exact), else the
     * catalogue's length, else 0 for the engine to estimate.
     */
    private fun trackLength(url: String?, song: Song): Long {
        val dur = url?.let { Regex("[?&]dur=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        if (dur != null && dur > 0) return (dur * 1000).toLong()
        return song.durationMillis().coerceAtLeast(0L)
    }

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
