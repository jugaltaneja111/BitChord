package com.music.bitchord.ios.automix

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.ios.IosSettings
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Whether the pair now mixing gets a loop mix (docs/designs/automix-loop-mix.md, Phase 3): the
 * phone's `loopPlanFor`, `loopRefusalFor`, `noteLoopVerdict` and `describeLoop` for iOS, over the
 * shared [LoopMix] planner.
 *
 * The verdict is logged and shown on the Automix line of stats for nerds; [IosPlayer] plays a plan
 * through the engine's loop mix (Phase 4), and a pair whose loop mix fell through is told so here
 * ([fallBack]) and gets its blend.
 *
 * Main thread only.
 */
internal object IosLoopPlanner {
    private const val TAG = "IosLoopPlanner"

    /** The phone's: how far ahead of the playhead a loop must start, so its regions can be decoded first. */
    const val LOOP_SETUP_SECONDS = 15.0

    /** The phone's: a cached plan stands while its loop is at least this far ahead; a seek nearer plans again. */
    private const val LOOP_MIN_LEAD_MS = 9_000L

    /** The phone's LOOP_MIX_LYRICS_TIMEOUT_MS: the vocal mask stands in for lyrics that don't come. */
    private const val LYRICS_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Synced-lyric spans per video id, once asked: [spans] null when the song has none. */
    private class LyricSpans(val spans: List<LoopMix.Span>?)

    private val lyricSpans = HashMap<String, LyricSpans>()
    private val lyricsAsked = HashSet<String>()

    /** The pair and the two analyses the cached plan was made from, and that plan; null when none. */
    private var planPair: String? = null
    private var planOut: TrackAnalysis? = null
    private var planIn: TrackAnalysis? = null
    private var planned: LoopMix.Plan? = null
    private var lastVerdict = ""

    /** Pairs whose loop mix fell through, and why: they get their blend from then on. */
    private val fellBack = HashMap<String, String>()

    private val _detail = MutableStateFlow<Triple<String, String, String>?>(null)

    /** (outgoing id, incoming id, the stats line) for the pair last planned. */
    val detail: StateFlow<Triple<String, String, String>?> = _detail.asStateFlow()

    /**
     * The loop mix for [song] -> [next], planned once per pair and analysis and again after a seek
     * brings the playhead too near its loop; null when the pair gets none (the reason is logged
     * and published).
     */
    fun planFor(
        song: Song,
        next: Song,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        state: SmartAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
        positionMs: Long,
    ): LoopMix.Plan? {
        val pair = "${song.videoId}->${next.videoId}"
        refusalFor(song, next, state)?.let { why ->
            note(song, next, null, why)
            return null
        }
        askLyrics(song, durationMs)
        askLyrics(next, nextDurationMs)
        val outLyrics = lyricSpans[song.videoId]
        val inLyrics = lyricSpans[next.videoId]
        if (outLyrics == null || inLyrics == null) {
            note(song, next, null, "waiting on lyrics")
            return null
        }
        val cached = planned
        if (pair == planPair && outAnalysis === planOut && inAnalysis === planIn &&
            (cached == null || cached.out.loopStart * 1000 - positionMs >= LOOP_MIN_LEAD_MS)
        ) {
            return cached
        }
        val why = StringBuilder()
        val plan = LoopMix.plan(
            out = outAnalysis,
            outDuration = durationMs / 1000.0,
            inn = inAnalysis,
            inDuration = nextDurationMs / 1000.0,
            earliestOutSec = positionMs / 1000.0 + LOOP_SETUP_SECONDS,
            sungOut = outLyrics.spans,
            sungIn = inLyrics.spans,
            outVideo = song.isVideoOrigin,
            inVideo = next.isVideoOrigin,
            why = why,
        )
        if (pair != planPair) forgetLyricsBut(song.videoId, next.videoId)
        planPair = pair
        planOut = outAnalysis
        planIn = inAnalysis
        planned = plan
        val lyricsNote = "lyrics ${if (outLyrics.spans != null) "out" else "-"}/${if (inLyrics.spans != null) "in" else "-"}"
        note(song, next, plan, if (plan == null) "$why; $lyricsNote" else null)
        return plan
    }

    /** Why [song] -> [next] can't be a loop mix right now, or null when it can. */
    private fun refusalFor(song: Song, next: Song, state: SmartAnalysis): String? = when {
        fellBack.containsKey("${song.videoId}->${next.videoId}") -> fellBack["${song.videoId}->${next.videoId}"]
        song.isVideoOrigin || next.isVideoOrigin -> "a music video"
        IosSettings.skipSilence.flow.value -> "skip silence is on"
        state.current != TrackAnalysisState.ANALYSED || state.next != TrackAnalysisState.ANALYSED -> "waiting on analysis"
        else -> null
    }

    /** The loop mix of [song] -> [next] fell through ([reason]): the pair gets its blend instead. */
    fun fallBack(song: Song, next: Song, reason: String) {
        val pair = "${song.videoId}->${next.videoId}"
        if (fellBack[pair] == reason) return
        if (fellBack.size > 64) fellBack.clear()
        fellBack[pair] = reason
        if (planPair == pair) {
            planPair = null
            planned = null
        }
        note(song, next, null, reason)
    }

    /** The loop mix of [song] -> [next] has started. */
    fun playing(song: Song, next: Song, plan: LoopMix.Plan) {
        _detail.value = Triple(song.videoId, next.videoId, "Automix · loop mix playing · ${describe(plan)}")
        TrackLog.i(TAG, "loop ${song.videoId}->${next.videoId}: playing")
    }

    /** The loop mix of [song] -> [next] played out. */
    fun finished(song: Song, next: Song) {
        TrackLog.i(TAG, "loop ${song.videoId}->${next.videoId}: played out")
        if (planPair == "${song.videoId}->${next.videoId}") {
            planPair = null
            planned = null
        }
    }

    /** Asks for [song]'s synced lyrics once; a loop they vouch for needs no vocal mask. */
    private fun askLyrics(song: Song, durationMs: Long) {
        val id = song.videoId
        if (!lyricsAsked.add(id)) return
        if (!IosSettings.syncedLyrics.flow.value || durationMs <= 0L) {
            lyricSpans[id] = LyricSpans(null)
            return
        }
        val order = IosSettings.lyricsSourceOrder.flow.value
        val enabled = IosSettings.lyricsSources.flow.value.toSet()
        scope.launch {
            val lines = runCatching {
                withTimeoutOrNull(LYRICS_TIMEOUT_MS) {
                    LyricsRepository.lyrics(
                        videoId = id,
                        title = song.title,
                        artist = song.artist,
                        durationMs = durationMs,
                        album = song.albumName,
                        sources = order.filter { it in enabled }.toSet(),
                        order = order,
                    )?.lines
                }
            }.getOrNull()
            // Only line-synced lyrics say where a voice is; plain text says nothing about time.
            val spans = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }?.let { found ->
                LoopMix.sungSpans(
                    found.map { line ->
                        val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs
                        LoopMix.Line(line.timeMs, end, blank = line.isGap || line.text.isBlank())
                    },
                )
            }
            lyricSpans[id] = LyricSpans(spans)
        }
    }

    /** Keeps the lyric spans of the pair in hand only, so a long session doesn't collect them. */
    private fun forgetLyricsBut(vararg keep: String) {
        lyricSpans.keys.retainAll(keep.toSet())
        lyricsAsked.retainAll(keep.toSet() + lyricSpans.keys)
    }

    private fun note(song: Song, next: Song, plan: LoopMix.Plan?, refusal: String?) {
        val pair = "${song.videoId}->${next.videoId}"
        val text = plan?.let { "Automix · loop mix at ${clock(it.out.loopStart)} · ${describe(it)}" }
            ?: "Automix · no loop mix ($refusal)"
        _detail.value = Triple(song.videoId, next.videoId, text)
        val verdict = "$pair: " + (plan?.let { "loop mix at ${twoDecimals(it.out.loopStart)}s · ${describe(it)}" } ?: "no loop mix ($refusal)")
        if (verdict == lastVerdict) return
        lastVerdict = verdict
        TrackLog.i(TAG, "loop $verdict")
    }

    /** "out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars". */
    fun describe(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        val tempo = if (from == to) "$from BPM" else "$from → $to BPM over ${plan.rampBars} bars"
        val fold = when {
            plan.fold > 1 -> " (next in double time)"
            plan.fold < 1 -> " (next in half time)"
            else -> ""
        }
        fun side(pick: LoopMix.Pick): String {
            val by = when (pick.checkedBy) {
                LoopMix.Check.LYRICS -> "lyrics"
                LoopMix.Check.MASK -> "mask"
                LoopMix.Check.LYRICS_AND_MASK -> "lyrics + mask"
            }
            return "${pick.bars}-bar, clear by $by"
        }
        return "out ${side(plan.out)} · in ${side(plan.inn)} · $tempo$fold"
    }

    private fun clock(seconds: Double): String {
        val whole = seconds.toInt().coerceAtLeast(0)
        return "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
    }

    private fun twoDecimals(value: Double): String = ((value * 100).roundToInt() / 100.0).toString()
}
