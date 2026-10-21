package com.music.bitchord.ios

import com.music.bitchord.playback.PlaybackPosition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.AVFoundation.setVolume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Two AVPlayers, one heard and one waiting: the phone's pair of ExoPlayers at their simplest. The
 * waiting one is handed the next track ahead of time ([prepareNext]), so the change of track is
 * either instant or a blend of the two ([startNext]). Each player's item carries its own
 * [TapDsp], so both sides of a crossfade keep their own loudness and share the equaliser.
 *
 * AVFoundation decodes AAC and ALAC/FLAC in MP4 itself, so the queue only ever hands it those (see
 * [IosPlayer]). Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioEngine(
    private val onEnded: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private inner class Deck {
        val player = AVPlayer()
        var item: AVPlayerItem? = null
        var dsp: TapDsp? = null
        var loudnessDb: Double? = null
        /** What this deck holds, for [IosPlayer] to match against its queue. */
        var key: String? = null
        /** The crossfade's share of this deck's volume. */
        var fade = 1f
        val observers = mutableListOf<NSObjectProtocol>()

        init {
            player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.25, 600), queue = null) { time ->
                if (this === active) tick(CMTimeGetSeconds(time))
            }
        }

        fun hold(url: String, headers: Map<String, String>, loudnessDb: Double?, key: String?): Boolean {
            val nsUrl = (if (url.startsWith("/")) NSURL.fileURLWithPath(url) else NSURL.URLWithString(url))
                ?: return false
            // AVURLAssetHTTPHeaderFieldsKey: long-standing, if undocumented, and what every
            // AVFoundation player with request headers uses.
            val asset = AVURLAsset(uRL = nsUrl, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
            val item = AVPlayerItem(asset = asset)
            clearObservers()
            val dsp = TapDsp()
            this.item = item
            this.dsp = dsp
            this.loudnessDb = loudnessDb
            this.key = key
            applyGain()
            IosAudioTap.attach(item, dsp)
            player.replaceCurrentItemWithPlayerItem(item)
            return true
        }

        /** The track's loudness gain: in the tap once it runs, the volume until then. */
        fun applyGain() {
            val gain = IosSound.loudnessGain(loudnessDb)
            val dsp = dsp
            dsp?.targetGain = gain
            val levelled = if (dsp?.attached == true) 1f else min(gain, 1f)
            player.setVolume(fade * levelled)
        }

        /** Ends and starts are reported only for the deck being heard. */
        fun listen() {
            val item = item ?: return
            clearObservers()
            val center = NSNotificationCenter.defaultCenter
            observers += center.addObserverForName(
                AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
            ) { _ -> if (this === active) onEnded() }
            observers += center.addObserverForName(
                AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
            ) { _ -> if (this === active) onFailed(item.error?.localizedDescription ?: "playback failed") }
        }

        fun failed(): Boolean = item?.status == AVPlayerItemStatusFailed

        fun clear() {
            clearObservers()
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            item = null
            dsp = null
            key = null
            loudnessDb = null
            fade = 1f
        }

        fun clearObservers() {
            observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
            observers.clear()
        }
    }

    private var active = Deck()
    private var spare = Deck()

    /** The deck fading out under [active] during a crossfade, if one is. */
    private var outgoing: Deck? = null
    private var fadeJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    /** Whether the playhead is meant to be moving — true while it buffers, as a pressed Play is. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    val position = PlaybackPosition()

    init {
        // Playback, so the music keeps going with the screen locked or the silent switch on.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        IosSound.start()
        scope.launch {
            IosSound.gainVersion.collect { refreshGains() }
        }
        // A tap that starts a moment after its track hands the levelling over from the volume.
        scope.launch {
            while (true) {
                delay(500)
                refreshGains()
            }
        }
    }

    /**
     * Plays [url], fetched with [headers] (googlevideo wants the resolving client's User-Agent),
     * or the file at [url] when it is an absolute path — a download.
     */
    fun load(url: String, headers: Map<String, String>, loudnessDb: Double? = null, key: String? = null) {
        finishFade()
        if (!active.hold(url, headers, loudnessDb, key)) return onFailed("bad stream URL")
        active.listen()
        AVAudioSession.sharedInstance().setActive(true, error = null)
        _durationMs.value = 0L
        position.report(0L)
        position.seeks++
        play()
    }

    /** Hands the waiting player the next track, paused and silent, so it is ready when wanted. */
    fun prepareNext(url: String, headers: Map<String, String>, loudnessDb: Double?, key: String) {
        if (spare === outgoing) finishFade()
        spare.fade = 0f
        if (!spare.hold(url, headers, loudnessDb, key)) spare.clear()
        spare.player.pause()
    }

    /** Whether a crossfade is under way; the waiting player is the one fading out until it ends. */
    val isBlending: Boolean get() = outgoing != null

    /** Whether the waiting player holds [key] and has not failed to load it. */
    fun preparedFor(key: String): Boolean =
        !isBlending && spare.key == key && spare.item != null && !spare.failed()

    /** What the waiting player holds, prepared or still loading. */
    val preparedKey: String? get() = if (isBlending) null else spare.key

    /** Drops whatever the waiting player holds. */
    fun clearPrepared() {
        if (spare !== outgoing) spare.clear()
    }

    /**
     * Makes the waiting player the heard one: straight away when [fadeMs] is 0, or blended in
     * over [fadeMs] while the old track fades out under it.
     */
    fun startNext(fadeMs: Long) {
        finishFade()
        val previous = active
        active = spare
        spare = previous
        active.listen()
        previous.clearObservers()
        _durationMs.value = 0L
        position.report(0L)
        position.seeks++
        AVAudioSession.sharedInstance().setActive(true, error = null)
        if (fadeMs <= 0L) {
            previous.clear()
            active.fade = 1f
            active.applyGain()
            play()
            return
        }
        outgoing = previous
        active.fade = 0f
        active.applyGain()
        play()
        fadeJob = scope.launch {
            var elapsed = 0L
            while (elapsed < fadeMs) {
                delay(FADE_STEP_MS)
                // Paused, the blend waits where it is.
                if (_isPlaying.value) elapsed += FADE_STEP_MS
                val t = (elapsed.toDouble() / fadeMs).coerceIn(0.0, 1.0)
                // Equal power, so the middle of the blend is not a dip.
                active.fade = sin(t * PI / 2).toFloat()
                previous.fade = cos(t * PI / 2).toFloat()
                active.applyGain()
                previous.applyGain()
            }
            fadeJob = null
            finishFade()
        }
    }

    /** Ends a blend at once: the new track at full volume, the old one gone. */
    private fun finishFade() {
        fadeJob?.cancel()
        fadeJob = null
        outgoing?.let { old ->
            outgoing = null
            old.clear()
        }
        if (active.fade != 1f) {
            active.fade = 1f
            active.applyGain()
        }
    }

    fun play() {
        active.player.play()
        outgoing?.player?.play()
        _isPlaying.value = true
    }

    fun pause() {
        active.player.pause()
        outgoing?.player?.pause()
        _isPlaying.value = false
        tick(CMTimeGetSeconds(active.player.currentTime()))
    }

    fun seekTo(positionMs: Long) {
        finishFade()
        active.player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 1000))
        position.report(positionMs)
        position.seeks++
    }

    fun stop() {
        finishFade()
        active.clear()
        _isPlaying.value = false
        _isBuffering.value = false
    }

    private fun refreshGains() {
        active.applyGain()
        outgoing?.applyGain()
        if (spare !== outgoing) spare.applyGain()
    }

    private fun tick(seconds: Double) {
        val item = active.player.currentItem ?: return
        if (item.status == AVPlayerItemStatusFailed) {
            onFailed(item.error?.localizedDescription ?: "playback failed")
            stop()
            return
        }
        val duration = CMTimeGetSeconds(item.duration)
        if (!duration.isNaN() && duration > 0) _durationMs.value = (duration * 1000).toLong()
        val player = active.player
        val waiting = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        _isBuffering.value = _isPlaying.value && waiting
        position.advancing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        if (!seconds.isNaN()) position.report((seconds * 1000).toLong())
    }

    private companion object {
        const val FADE_STEP_MS = 40L
    }
}
