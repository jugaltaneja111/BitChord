package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
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

    private val engine = IosAudioEngine(onEnded = ::next, onFailed = ::fail)

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
        _queue.value = songs
        playAt(startIndex.coerceIn(songs.indices))
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
        if (nextIndex in _queue.value.indices) playAt(nextIndex) else engine.pause()
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
