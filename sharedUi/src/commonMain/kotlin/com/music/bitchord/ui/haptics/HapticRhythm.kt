package com.music.bitchord.ui.haptics

// ── The rhythms ───────────────────────────────────────────────────────────────

/**
 * One beat of a pattern: which of the three short primitives to strike, how
 * hard relative to that primitive's nominal strength, and how long to wait
 * after the previous beat before striking it.
 *
 * Only the three genuinely *short* primitives are used. The platform also
 * offers rises, falls, thuds and a spin, and all of them run 80–500ms — long
 * enough that a two-beat pattern built from them would still be vibrating well
 * after the screen had finished responding.
 */
internal class Beat(val kind: Kind, val scale: Float, val gapMs: Long) {
    enum class Kind(
        /** Roughly how long the primitive itself lasts, for the waveform tiers. */
        val pulseMs: Long,
        /** Its nominal amplitude, before [Beat.scale]. */
        val amplitude: Int,
    ) {
        Tick(8, 110),
        LowTick(10, 95),
        Click(14, 210),
    }
}

internal fun rhythmOf(haptic: Haptic): List<Beat> = when (haptic) {
    Haptic.Tick -> listOf(Beat(Beat.Kind.Tick, 0.35f, 0))
    Haptic.Tap -> listOf(Beat(Beat.Kind.Click, 0.5f, 0))
    Haptic.Select -> listOf(
        Beat(Beat.Kind.Tick, 0.4f, 0),
        Beat(Beat.Kind.Click, 0.75f, 18),
    )
    Haptic.ToggleOn -> listOf(
        Beat(Beat.Kind.Tick, 0.4f, 0),
        Beat(Beat.Kind.Click, 0.9f, 14),
    )
    Haptic.ToggleOff -> listOf(
        Beat(Beat.Kind.Click, 0.75f, 0),
        Beat(Beat.Kind.Tick, 0.3f, 14),
    )
    Haptic.SkipNext -> listOf(
        Beat(Beat.Kind.Tick, 0.4f, 0),
        Beat(Beat.Kind.Tick, 0.55f, 16),
        Beat(Beat.Kind.Click, 0.7f, 16),
    )
    Haptic.SkipPrevious -> listOf(
        Beat(Beat.Kind.Click, 0.7f, 0),
        Beat(Beat.Kind.Tick, 0.55f, 16),
        Beat(Beat.Kind.Tick, 0.4f, 16),
    )
    Haptic.Resume -> listOf(
        Beat(Beat.Kind.LowTick, 0.5f, 0),
        Beat(Beat.Kind.Click, 0.85f, 22),
    )
    Haptic.Pause -> listOf(
        Beat(Beat.Kind.Click, 0.85f, 0),
        Beat(Beat.Kind.LowTick, 0.4f, 22),
    )
    Haptic.Expand -> listOf(
        Beat(Beat.Kind.Tick, 0.3f, 0),
        Beat(Beat.Kind.Tick, 0.45f, 12),
        Beat(Beat.Kind.Click, 0.6f, 12),
    )
}
