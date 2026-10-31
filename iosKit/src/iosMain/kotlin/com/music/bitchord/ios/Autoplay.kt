package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo

/**
 * A smoke test CI can run without touching the screen: launched with BITCHORD_AUTOPLAY set to a
 * video id, the app plays it at once and prints what the player is doing every two seconds. A
 * position that advances means AVPlayer is decoding the stream. BITCHORD_AUTOSEARCH (see
 * [autoSearchQuery]) also draws a page of results, which is where the search rows get exercised.
 */
internal fun startAutoplayIfAsked() {
    val videoId = NSProcessInfo.processInfo.environment["BITCHORD_AUTOPLAY"] as? String ?: return
    TrackLog.echo = { level, tag, message, error ->
        println("$level $tag: $message${error?.let { " ($it)" }.orEmpty()}")
    }
    IosPlayer.playQueue(listOf(Song(videoId = videoId, title = "Autoplay", artist = "", thumbnailUrl = null)), 0)
    MainScope().launch {
        repeat(AUTOPLAY_REPORTS) { report ->
            delay(2_000)
            // A pretend phone call part-way through: the player must pause for it, keep the engine
            // down through it, and pick up again once it ends.
            when (report) {
                INTERRUPT_AT -> {
                    IosInterruptions.simulate(began = true)
                    println("interruption check: began, playing=${IosPlayer.isPlaying.value}")
                }
                INTERRUPT_AT + 2 -> {
                    println("interruption check: during, playing=${IosPlayer.isPlaying.value}")
                    IosInterruptions.simulate(began = false, shouldResume = true)
                }
                INTERRUPT_AT + 4 -> println("interruption check: after, playing=${IosPlayer.isPlaying.value}")
            }
            println(
                "autoplay: playing=${IosPlayer.isPlaying.value} loading=${IosPlayer.isLoading.value} " +
                    "positionMs=${IosPlayer.position.positionMs} durationMs=${IosPlayer.durationMs.value} " +
                    "error=${IosPlayer.error.value}",
            )
        }
    }
}

private const val AUTOPLAY_REPORTS = 40
private const val INTERRUPT_AT = 12

/** The search CI asks the app to submit at launch, or null. */
internal fun autoSearchQuery(): String? =
    NSProcessInfo.processInfo.environment["BITCHORD_AUTOSEARCH"] as? String

/**
 * BITCHORD_TOUR: walk every page CI cannot tap its way to — Home, Explore, a category, an album,
 * an artist, the full player, the sign-in page, search — a fixed time apart, so the screenshots CI takes on the
 * same clock land one on each. Each step is printed as it starts.
 */
internal fun tourRequested(): Boolean = NSProcessInfo.processInfo.environment["BITCHORD_TOUR"] != null
