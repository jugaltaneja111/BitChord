package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.ui.player.RepeatModes
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
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()

    /** The queue as it was before shuffling, so turning shuffle off puts it back. */
    private var unshuffled: List<Song>? = null
    /** Every track started since launch, which "Don't repeat songs" keeps AutoPlay from suggesting. */
    private val playedThisSession = mutableSetOf<String>()
    private var extending = false

    private val _error = MutableStateFlow<String?>(null)
    /** Why the last track would not play, until the next one starts. */
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
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
        playQueue(listOf(song), 0)
        scope.launch {
            YtMusicRepository.radio(song.videoId).onSuccess { radio ->
                // Still the same session: the user may have moved on while it loaded.
                if (_queue.value.firstOrNull()?.videoId != song.videoId) return@onSuccess
                _queue.value = listOf(song) + radio.filter { it.videoId != song.videoId }
            }
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        unshuffled = null
        _shuffle.value = false
        _queue.value = songs
        playAt(startIndex.coerceIn(songs.indices))
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
        if (songs.isEmpty()) return
        val index = _index.value
        if (_song.value == null || index < 0) {
            playQueue(songs, 0)
            return
        }
        val queue = _queue.value
        _queue.value = queue.take(index + 1) + songs + queue.drop(index + 1)
        unshuffled = unshuffled?.plus(songs)
        IosPlayerHost.showMessage(if (songs.size == 1) "Playing next" else "${songs.size} songs playing next")
    }

    /** [songs] at the end of the queue; they start now if nothing is playing. */
    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (_song.value == null || _index.value < 0) {
            playQueue(songs, 0)
            return
        }
        _queue.value = _queue.value + songs
        unshuffled = unshuffled?.plus(songs)
        IosPlayerHost.showMessage(if (songs.size == 1) "Added to queue" else "${songs.size} songs added to queue")
    }

    /** A swiped row: the end of the queue, or next with "Play next on swipe". */
    fun swipe(song: Song) = if (IosSettings.swipePlayNext.flow.value) playNext(listOf(song)) else addToQueue(listOf(song))

    fun jumpTo(index: Int) {
        if (index in _queue.value.indices) playAt(index)
    }

    fun removeAt(index: Int) {
        val queue = _queue.value
        if (index !in queue.indices || index == _index.value) return
        _queue.value = queue.toMutableList().apply { removeAt(index) }
        if (index < _index.value) _index.value -= 1
    }

    fun move(from: Int, to: Int) {
        val queue = _queue.value
        if (from !in queue.indices || to !in queue.indices || from == to) return
        _queue.value = queue.toMutableList().apply { add(to, removeAt(from)) }
        val current = _index.value
        _index.value = when {
            from == current -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }
    }

    /** Everything but the song playing. */
    fun clearQueue() {
        val song = _song.value ?: return
        unshuffled = null
        _queue.value = listOf(song)
        _index.value = 0
    }

    fun toggleShuffle() {
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
        IosSettings.autoplay.set(!_autoplay.value)
    }

    fun togglePlayPause() {
        when {
            _song.value == null -> Unit
            engine.isPlaying.value -> engine.pause()
            else -> engine.play()
        }
    }

    fun next() {
        val nextIndex = _index.value + 1
        when {
            nextIndex in _queue.value.indices -> playAt(nextIndex)
            _repeatMode.value == RepeatModes.ALL && _queue.value.isNotEmpty() -> playAt(0)
            _autoplay.value -> extendWithRadio(playNext = true)
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
        val fadeMs = (IosSettings.crossfadeSeconds.flow.value * 1000L).coerceAtMost(duration / 3)
        if (fadeMs > 0L && remaining in 1..fadeMs && engine.preparedFor(next.videoId)) {
            advancingFromEnd = true
            transition(nextIndex, fadeMs)
            advancingFromEnd = false
        }
    }

    private fun prepare(song: Song) {
        prepareJob?.cancel()
        preparing = song.videoId
        prepareJob = scope.launch {
            // What this job is readying: the queued track, or its audio release once swapped in.
            var readying = song.videoId
            try {
                IosDownloads.audioPath(song.videoId)?.let { path ->
                    val loudness = IosDownloads.tracks.value.firstOrNull { it.videoId == song.videoId }?.loudnessDb
                    engine.prepareNext(path, emptyMap(), loudness, song.videoId, trackLength(null, song))
                    return@launch
                }
                val audio = musicOnly(song)
                if (audio.videoId != song.videoId) {
                    val at = upcomingIndex()?.takeIf { _queue.value.getOrNull(it)?.videoId == song.videoId } ?: return@launch
                    _queue.value = _queue.value.toMutableList().also { it[at] = audio }
                    preparing = audio.videoId
                    readying = audio.videoId
                    IosDownloads.audioPath(audio.videoId)?.let { path ->
                        engine.prepareNext(path, emptyMap(), null, audio.videoId, trackLength(null, audio))
                        return@launch
                    }
                }
                val stream = InnerTubeXResolver.extract(audio.videoId, maxKbps = 320, requireM4a = true) ?: return@launch
                // The queue may have moved on while that resolved.
                if (upcomingIndex()?.let { _queue.value.getOrNull(it)?.videoId } != audio.videoId) return@launch
                TrackLog.i(TAG, "${audio.videoId}: readied ${stream.clientName} ${stream.mimeType} ${stream.kbps} kbps")
                engine.prepareNext(stream.url, stream.headers, stream.loudnessDb, audio.videoId, trackLength(stream.url, audio))
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
        if (_autoplay.value && _queue.value.size > 1 && index >= _queue.value.size - 2) {
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
            }.onFailure { if (playNext) engine.pause() }
            extending = false
        }
    }

    fun previous() {
        // Back to the start first, as every player does a few seconds in.
        if (engine.position.positionMs > 3_000 || _index.value <= 0) {
            engine.seekTo(0)
        } else {
            playAt(_index.value - 1)
        }
    }

    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
        // The card counts down from instants; a seek moves them.
        IosDiscord.refresh()
    }

    private fun playAt(index: Int) {
        val song = _queue.value.getOrNull(index) ?: return
        // Already waiting in the second player: it starts at once.
        if (song.videoId != _song.value?.videoId && engine.preparedFor(song.videoId)) {
            transition(index, 0L)
            return
        }
        _index.value = index
        playedThisSession += song.videoId
        IosScrobbler.onSongChanged(_song.value, advancingFromEnd, song)
        _song.value = song
        _error.value = null
        // Fetched ahead, so the next song is already queued when this one ends. A queue of one is
        // a radio still loading (see playRadio).
        if (_autoplay.value && _queue.value.size > 1 && index >= _queue.value.size - 2) {
            extendWithRadio(playNext = false)
        }
        engine.stop()
        loadJob?.cancel()
        loadJob = scope.launch {
            _resolving.value = true
            try {
                // A download plays from the phone, connection or not.
                IosDownloads.audioPath(song.videoId)?.let { path ->
                    TrackLog.i(TAG, "${song.videoId}: from download")
                    val loudness = IosDownloads.tracks.value.firstOrNull { it.videoId == song.videoId }?.loudnessDb
                    engine.load(path, emptyMap(), loudness, song.videoId, trackLength(null, song))
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
                    IosDownloads.audioPath(audio.videoId)?.let { path ->
                        engine.load(path, emptyMap(), null, audio.videoId, trackLength(null, audio))
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
                engine.load(stream.url, stream.headers, stream.loudnessDb, audio.videoId, trackLength(stream.url, audio))
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
        if (!IosSettings.preferMusicOnly.flow.value || !song.isVideo) return song
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
        commands.playCommand.addTargetWithHandler { _ -> handled { if (!engine.isPlaying.value) engine.play() } }
        commands.pauseCommand.addTargetWithHandler { _ -> handled { engine.pause() } }
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
