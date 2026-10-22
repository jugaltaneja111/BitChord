package com.music.bitchord.ios.automix

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.TrackAnalysis

/** Automix's track analyses. Placeholder until the analyzer lands: nothing is ever analysed. */
internal object IosAutomixAnalysis {
    /** Asks for [song] to be analysed, unless it already is or is under way. Main thread. */
    fun request(song: Song) {}

    /** [videoId]'s finished analysis, or null. Main thread. */
    fun analysisFor(videoId: String): TrackAnalysis? = null
}
