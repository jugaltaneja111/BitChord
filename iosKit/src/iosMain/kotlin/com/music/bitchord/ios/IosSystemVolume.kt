package com.music.bitchord.ios

import com.music.bitchord.ui.player.SystemVolume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.outputVolume
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UISlider

/**
 * The phone's output volume, for the Audio output drawer's bar: the Android `AudioManager`
 * media stream's counterpart.
 *
 * Reading is public: the session's `outputVolume`, re-read every [POLL_MS] while anything shows
 * the bar, so it follows the side buttons and Control Center. (KVO would need an Objective-C
 * override Kotlin can't declare.) Setting is not; iOS only lets a [MPVolumeView] move it. One sits
 * in the window, all but invisible, and [set] moves its slider. While it is there iOS also leaves out
 * its own volume overlay, which would otherwise cover the drawer on every drag. Main thread.
 */
internal object IosSystemVolume : SystemVolume {
    private val session = AVAudioSession.sharedInstance()
    private val _level = MutableStateFlow(session.outputVolume)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private const val POLL_MS = 250L

    private var volumeView: MPVolumeView? = null
    private var watching = false

    override fun set(level: Float) {
        attach()
        val slider = volumeView?.subviews?.firstOrNull { it is UISlider } as? UISlider ?: return
        slider.setValue(level.coerceIn(0f, 1f), animated = false)
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
    }

    override fun refresh() {
        attach()
        _level.value = session.outputVolume
    }

    /** Starts watching the level and puts the volume view in the window, once each it's possible. */
    @OptIn(ExperimentalForeignApi::class)
    private fun attach() {
        if (!watching) {
            watching = true
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                _level.subscriptionCount.collectLatest { watchers ->
                    while (watchers > 0) {
                        _level.value = session.outputVolume
                        delay(POLL_MS)
                    }
                }
            }
            // A new route brings its own level (AirPods remember theirs); show it at once.
            NSNotificationCenter.defaultCenter.addObserverForName(
                AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue,
            ) { _ -> _level.value = session.outputVolume }
        }
        if (volumeView == null) {
            val window = UIApplication.sharedApplication.keyWindow ?: return
            val view = MPVolumeView(frame = CGRectMake(-100.0, -100.0, 1.0, 1.0))
            view.alpha = 0.01
            view.userInteractionEnabled = false
            window.addSubview(view)
            volumeView = view
        }
    }
}
