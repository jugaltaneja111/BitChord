@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.ios.ffmpeg.FfmpegAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionInterruptionWasSuspendedKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.setActive
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification

/**
 * The phone's audio focus, the iOS way. Android's player pauses for a call and picks up again
 * after it (ExoPlayer's handleAudioFocus), and pauses when the headphones come out
 * (handleAudioBecomingNoisy); here those are AVAudioSession's interruption and route-change
 * notices. Everything goes through [IosPlayer], so the lock screen, the widget and Discord follow.
 */
internal object IosInterruptions {
    private const val TAG = "IosAudio"
    private var started = false

    /** Whether music was playing when the interruption began, so it is picked up again after. */
    private var resumeAfter = false

    fun start() {
        if (started) return
        started = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            val info = note?.userInfo
            val type = (info?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            when (type) {
                AVAudioSessionInterruptionTypeBegan -> {
                    // A late notice for an interruption that came while the app was suspended:
                    // the audio is already gone, and nothing should change.
                    val wasSuspended = (info[AVAudioSessionInterruptionWasSuspendedKey] as? NSNumber)?.boolValue == true
                    if (!wasSuspended) began()
                }
                AVAudioSessionInterruptionTypeEnded -> {
                    val options = (info?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongValue ?: 0uL
                    ended(shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL)
                }
            }
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            reclaim("app active")
        }
        center.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { note ->
            val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable && IosPlayer.isPlaying.value) {
                // Headphones out or Bluetooth gone: don't carry on out of the speaker.
                TrackLog.i(TAG, "output device gone: pausing")
                IosPlayer.pauseFor("headphones")
            }
        }
    }

    private fun began() {
        resumeAfter = IosPlayer.isPlaying.value
        TrackLog.i(TAG, "interruption began (playing=$resumeAfter): pausing")
        FfmpegAudioEngine.beginInterruption()
        if (resumeAfter) IosPlayer.pauseFor("interruption")
    }

    private fun ended(shouldResume: Boolean) {
        TrackLog.i(TAG, "interruption ended (resume=$shouldResume, was playing=$resumeAfter)")
        AVAudioSession.sharedInstance().setActive(true, error = null)
        FfmpegAudioEngine.endInterruption()
        if (resumeAfter && shouldResume) IosPlayer.resumeFor("interruption")
        resumeAfter = false
    }

    /**
     * iOS doesn't always post the end of an interruption (an alarm dismissed in another app, a
     * call the app was suspended through). When the user presses play, or the app comes back, an
     * interruption still marked running is over if the session can be taken back.
     */
    fun reclaim(why: String) {
        if (!FfmpegAudioEngine.interrupted) return
        if (AVAudioSession.sharedInstance().setActive(true, error = null)) {
            TrackLog.i(TAG, "interruption over without a notice ($why)")
            resumeAfter = false
            FfmpegAudioEngine.endInterruption()
        }
    }

    /** Simulator check: plays out an interruption as iOS would post it. */
    fun simulate(began: Boolean, shouldResume: Boolean = true) {
        if (began) began() else ended(shouldResume)
    }
}
