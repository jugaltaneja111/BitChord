package com.music.bitchord.ui.haptics

import androidx.compose.runtime.Composable
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * The Taptic Engine, playing the same rhythms as the phone's vibrator: each beat is an impact of
 * the closest style, struck at the beat's strength and after its gap.
 */
actual class Haptics {
    private val generators = Beat.Kind.entries.associateWith { kind ->
        UIImpactFeedbackGenerator(style = kind.style())
    }

    actual fun play(haptic: Haptic) {
        var atMs = 0L
        for (beat in rhythmOf(haptic)) {
            atMs += beat.gapMs
            val generator = generators.getValue(beat.kind)
            if (atMs == 0L) {
                generator.impactOccurredWithIntensity(beat.scale.toDouble())
            } else {
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, atMs * 1_000_000), dispatch_get_main_queue()) {
                    generator.impactOccurredWithIntensity(beat.scale.toDouble())
                }
            }
        }
    }
}

private fun Beat.Kind.style(): UIImpactFeedbackStyle = when (this) {
    Beat.Kind.Tick -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
    Beat.Kind.LowTick -> UIImpactFeedbackStyle.UIImpactFeedbackStyleSoft
    Beat.Kind.Click -> UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid
}

private val SharedHaptics by lazy { Haptics() }

@Composable
actual fun rememberHaptics(): Haptics = SharedHaptics
