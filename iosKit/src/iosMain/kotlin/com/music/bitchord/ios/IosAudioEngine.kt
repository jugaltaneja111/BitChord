package com.music.bitchord.ios

import com.music.bitchord.playback.PlaybackPosition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol

/**
 * One track at a time through AVPlayer: what the desktop's engine is to the desktop, at its
 * simplest. AVFoundation decodes AAC and ALAC/FLAC in MP4 itself, so the queue only ever hands it
 * those (see [IosPlayer]). Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioEngine(
    private val onEnded: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val player = AVPlayer()
    private val itemObservers = mutableListOf<NSObjectProtocol>()

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
        player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.25, 600), queue = null) { time ->
            tick(CMTimeGetSeconds(time))
        }
    }

    /** Plays [url], fetched with [headers] (googlevideo wants the resolving client's User-Agent). */
    fun load(url: String, headers: Map<String, String>) {
        val nsUrl = NSURL.URLWithString(url) ?: return onFailed("bad stream URL")
        // AVURLAssetHTTPHeaderFieldsKey: long-standing, if undocumented, and what every
        // AVFoundation player with request headers uses.
        val asset = AVURLAsset(uRL = nsUrl, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
        val item = AVPlayerItem(asset = asset)
        clearItemObservers()
        val center = NSNotificationCenter.defaultCenter
        itemObservers += center.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
        ) { _ -> onEnded() }
        itemObservers += center.addObserverForName(
            AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
        ) { _ -> onFailed(item.error?.localizedDescription ?: "playback failed") }
        AVAudioSession.sharedInstance().setActive(true, error = null)
        _durationMs.value = 0L
        position.report(0L)
        position.seeks++
        player.replaceCurrentItemWithPlayerItem(item)
        play()
    }

    fun play() {
        player.play()
        _isPlaying.value = true
    }

    fun pause() {
        player.pause()
        _isPlaying.value = false
        tick(CMTimeGetSeconds(player.currentTime()))
    }

    fun seekTo(positionMs: Long) {
        player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 1000))
        position.report(positionMs)
        position.seeks++
    }

    fun stop() {
        clearItemObservers()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        _isPlaying.value = false
        _isBuffering.value = false
    }

    private fun tick(seconds: Double) {
        val item = player.currentItem ?: return
        if (item.status == AVPlayerItemStatusFailed) {
            onFailed(item.error?.localizedDescription ?: "playback failed")
            stop()
            return
        }
        val duration = CMTimeGetSeconds(item.duration)
        if (!duration.isNaN() && duration > 0) _durationMs.value = (duration * 1000).toLong()
        val waiting = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        _isBuffering.value = _isPlaying.value && waiting
        position.advancing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        if (!seconds.isNaN()) position.report((seconds * 1000).toLong())
    }

    private fun clearItemObservers() {
        itemObservers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        itemObservers.clear()
    }
}
