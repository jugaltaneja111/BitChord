package com.music.bitchord.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The phone's sleep timer: pause after a while, or when the song playing ends. Kept for the
 * session only, as the phone keeps it. Main thread only.
 */
internal object IosSleepTimer {
    val PRESETS = listOf(15, 30, 45, 60)

    /** The custom picker moves in steps this size, from one step up to [MAX_MINUTES]. */
    const val STEP_MINUTES = 5

    /** Longest custom duration: twelve hours, as the phone's. */
    const val MAX_MINUTES = 720

    /** The custom duration a slider at [fraction] (0..1) of its travel stands for. */
    fun minutesAt(fraction: Float): Int {
        val raw = STEP_MINUTES + fraction.coerceIn(0f, 1f) * (MAX_MINUTES - STEP_MINUTES)
        return kotlin.math.round(raw / STEP_MINUTES).toInt() * STEP_MINUTES
    }

    /** Where [minutes] sits along the custom slider, the inverse of [minutesAt]. */
    fun fractionOf(minutes: Int): Float =
        (minutes - STEP_MINUTES).toFloat() / (MAX_MINUTES - STEP_MINUTES)

    private val clock = TimeSource.Monotonic
    private val _deadline = MutableStateFlow<TimeSource.Monotonic.ValueTimeMark?>(null)
    private val _minutes = MutableStateFlow<Int?>(null)
    private val _afterTrack = MutableStateFlow(false)

    /** The preset running, or null. */
    val minutes: StateFlow<Int?> = _minutes

    /** Whether playback pauses when the song playing ends. */
    val afterTrack: StateFlow<Boolean> = _afterTrack

    val isRunning: Boolean get() = _deadline.value != null || _afterTrack.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // collectLatest: setting the timer again restarts the wait rather than adding a second one.
        scope.launch {
            _deadline.collectLatest { deadline ->
                deadline ?: return@collectLatest
                val left = -deadline.elapsedNow().inWholeMilliseconds
                if (left > 0) delay(left)
                IosPlayer.pauseForSleep()
                cancel()
            }
        }
    }

    fun start(minutes: Int) {
        _afterTrack.value = false
        _minutes.value = minutes
        _deadline.value = clock.markNow() + minutes.minutes
    }

    fun startAfterTrack() {
        _minutes.value = null
        _deadline.value = null
        _afterTrack.value = true
    }

    fun cancel() {
        _minutes.value = null
        _deadline.value = null
        _afterTrack.value = false
    }

    /** Milliseconds until the timer pauses playback, or null when it isn't counting down. */
    fun remainingMs(): Long? = _deadline.value?.let { (-it.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L) }
}
