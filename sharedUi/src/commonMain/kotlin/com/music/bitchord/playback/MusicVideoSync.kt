package com.music.bitchord.playback

import kotlin.math.abs

/**
 * How a music video's picture is held to the audio it plays over: one step of upstream's
 * MusicVideoEngine.sync (b690dc14, app/.../ui/player/MusicVideoPlayer.kt), apart from any player
 * so it can be tested. The platform's loop calls [step] every [SYNC_EVERY_MS] and on every play,
 * pause and seek, and does what it answers.
 *
 * Positions are ms of the video's own timeline. [target] is where the audio is *as heard*: the
 * playhead, moved on by the time since it was read and less the output's latency.
 */
object MusicVideoSync {
    /** Past this the picture jumps to the audio; under it, its rate is nudged. */
    const val SEEK_DRIFT_MS = 450L
    const val IGNORE_DRIFT_MS = 25L
    const val SYNC_EVERY_MS = 250L

    /** No second seek inside this, while the first is still landing. */
    const val SEEK_COOLDOWN_MS = 1_500L

    /** Paused, the picture is put on the exact frame once it is further out than this. */
    const val PAUSED_SNAP_MS = 40L

    /** And not again within this of the last seek, while that one lands. */
    const val PAUSED_SEEK_GAP_MS = 120L

    /** The most the picture's rate leaves the audio's to catch up: about a second's drift a second. */
    const val MAX_NUDGE = 0.1f

    const val MAX_SEEK_LEAD_MS = 1_500L

    sealed interface Action {
        /** Stop the picture where it is. */
        data object Pause : Action

        /** Leave it be: paused and on its frame, or waiting out a jump of the audio. */
        data object Hold : Action

        /** Jump to [toMs] (paused: exactly there; playing: there plus the lead). */
        data class Seek(val toMs: Long, val play: Boolean) : Action

        /** Play at [rate]. */
        data class Play(val rate: Float) : Action
    }

    /**
     * @param videoMs where the picture is now.
     * @param playing whether the audio is playing.
     * @param inGap whether skip silence is rushing the audio through a quiet stretch: the
     *   picture holds, and catches up once the gap's jump lands.
     * @param videoBuffering the picture is waiting on its stream; nothing to correct yet.
     * @param rate the audio's own speed (an Automix tempo ramp).
     */
    fun step(
        target: Long,
        videoMs: Long,
        playing: Boolean,
        inGap: Boolean,
        videoBuffering: Boolean,
        nowMs: Long,
        lastSeekAt: Long,
        seekLeadMs: Long,
        rate: Float,
    ): Action {
        if (!playing) {
            val off = abs(videoMs - target) > PAUSED_SNAP_MS
            return if (off && nowMs - lastSeekAt > PAUSED_SEEK_GAP_MS) Action.Seek(target, play = false) else Action.Pause
        }
        if (inGap) return Action.Hold
        if (videoBuffering) return Action.Play(rate)
        val drift = videoMs - target
        if (abs(drift) > SEEK_DRIFT_MS) {
            return if (nowMs - lastSeekAt > SEEK_COOLDOWN_MS) Action.Seek(target + seekLeadMs, play = true) else Action.Play(rate)
        }
        // Catch up (or wait) over about a second, rather than jumping.
        val nudge = if (abs(drift) > IGNORE_DRIFT_MS) (-drift / 1000f).coerceIn(-MAX_NUDGE, MAX_NUDGE) else 0f
        return Action.Play(rate * (1f + nudge))
    }

    /** The next seek's lead: halfway from the last towards what that seek took to land. */
    fun nextSeekLead(lastLeadMs: Long, tookMs: Long): Long =
        ((lastLeadMs + tookMs) / 2).coerceIn(0L, MAX_SEEK_LEAD_MS)
}
