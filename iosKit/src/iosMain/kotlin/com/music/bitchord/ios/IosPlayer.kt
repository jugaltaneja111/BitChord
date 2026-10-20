package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
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
import kotlinx.coroutines.flow.stateIn
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
            else -> engine.pause()
        }
    }

    private fun onEnded() {
        if (_repeatMode.value == RepeatModes.ONE) {
            engine.seekTo(0)
            engine.play()
        } else {
            next()
        }
    }

    /** The last song's radio after the queue; played straight away when the queue has run out. */
    private fun extendWithRadio(playNext: Boolean) {
        val last = _queue.value.lastOrNull() ?: return
        if (extending) return
        extending = true
        scope.launch {
            YtMusicRepository.radio(last.videoId).onSuccess { radio ->
                val known = _queue.value.map { it.videoId }.toSet()
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

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    private fun playAt(index: Int) {
        val song = _queue.value.getOrNull(index) ?: return
        _index.value = index
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
                // AAC in MP4: AVFoundation cannot play the Opus/WebM streams the phone prefers.
                val stream = InnerTubeXResolver.extract(song.videoId, maxKbps = 320, requireM4a = true)
                if (stream == null) {
                    fail("No playable stream for this track")
                    return@launch
                }
                TrackLog.i(TAG, "${song.videoId}: ${stream.clientName} ${stream.mimeType} ${stream.kbps} kbps")
                engine.load(stream.url, stream.headers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            } finally {
                _resolving.value = false
            }
        }
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
