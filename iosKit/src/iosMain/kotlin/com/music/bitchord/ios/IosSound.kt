package com.music.bitchord.ios

import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.playback.manualCurve
import com.music.bitchord.playback.toneCurve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The settings that shape the sound, turned into what the audio tap and the engine read: the
 * equaliser's target curve ([IosEqTarget]) and each track's loudness gain. The phone's
 * `PlaybackService.applyEqualizer` and `loudnessGainFor`.
 */
internal object IosSound {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _onSpeaker = MutableStateFlow(onBuiltInSpeaker())
    /** Whether the phone's own speaker is playing, which "Off on the phone speaker" asks about. */
    val onSpeaker: StateFlow<Boolean> = _onSpeaker.asStateFlow()

    /** Bumped whenever a track's loudness gain could have changed; the engine re-reads them. */
    private val _gainVersion = MutableStateFlow(0)
    val gainVersion: StateFlow<Int> = _gainVersion.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                IosSettings.equalizerEnabled.flow,
                IosSettings.equalizerMode.flow,
                combine(IosSettings.equalizerToneX.flow, IosSettings.equalizerToneY.flow, IosSettings.equalizerFocused.flow) { x, y, f -> Triple(x, y, f) },
                IosSettings.equalizerBands.flow,
                IosSettings.equalizerBalance.flow,
            ) { enabled, mode, tone, bands, balance ->
                if (!enabled) {
                    EqTarget.OFF
                } else {
                    val curve = if (mode == MODE_MANUAL) manualCurve(bands) else toneCurve(tone.first, tone.second, tone.third)
                    EqTarget(curve, balance.coerceIn(-1f, 1f))
                }
            }.collect { IosEqTarget.target = it }
        }
        scope.launch {
            combine(IosSettings.loudnessNormalization.flow, IosSettings.loudnessOffOnSpeaker.flow, _onSpeaker) { _, _, _ -> }
                .collect { _gainVersion.value++ }
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> _onSpeaker.value = onBuiltInSpeaker() }
    }

    /**
     * The linear gain for a track YouTube measured at [loudnessDb] above its reference: -15 to
     * +3 dB, as the phone clamps it, or 1 when normalization is off or the figure is unknown.
     */
    fun loudnessGain(loudnessDb: Double?): Float {
        if (!IosSettings.loudnessNormalization.flow.value) return 1f
        if (IosSettings.loudnessOffOnSpeaker.flow.value && _onSpeaker.value) return 1f
        val db = loudnessDb ?: return 1f
        val gainMb = (-db * 100.0).roundToInt().coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)
        return 10.0.pow(gainMb / 2000.0).toFloat()
    }

    fun setEqualizerBands(values: List<Float>) {
        IosSettings.equalizerBands.set(
            List(com.music.bitchord.playback.EqLayout.MANUAL_COUNT) {
                values.getOrElse(it) { 0f }.coerceIn(
                    -com.music.bitchord.playback.EqLayout.MANUAL_RANGE_DB,
                    com.music.bitchord.playback.EqLayout.MANUAL_RANGE_DB,
                )
            },
        )
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        if (preset != EqualizerPreset.CUSTOM) setEqualizerBands(preset.bands)
    }

    fun setEqualizerTone(x: Int, y: Int) {
        val steps = com.music.bitchord.playback.EqLayout.TONE_STEPS
        IosSettings.equalizerToneX.set(x.coerceIn(-steps, steps))
        IosSettings.equalizerToneY.set(y.coerceIn(-steps, steps))
    }

    private fun onBuiltInSpeaker(): Boolean =
        AVAudioSession.sharedInstance().currentRoute.outputs.any {
            (it as? AVAudioSessionPortDescription)?.portType == AVAudioSessionPortBuiltInSpeaker
        }

    const val MODE_DYNAMIC = 0
    const val MODE_MANUAL = 1

    private const val MIN_GAIN_MB = -1500
    private const val MAX_GAIN_MB = 300
}
