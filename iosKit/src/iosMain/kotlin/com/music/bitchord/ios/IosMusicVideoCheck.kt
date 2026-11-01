@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.timeControlStatus
import platform.Foundation.NSURL
import platform.UIKit.UIPasteboard
import kotlin.time.TimeSource

/**
 * Phase 0 of docs/designs/music-videos.md, on the phone: does YouTube give iOS an H.264
 * (`avc1`) video-only stream for most music videos, and does 1080p play without stalling?
 *
 * It finds 20 music videos by search (official videos and fan uploads, old and new), asks
 * [InnerTubeXResolver.extractVideo] for each with `requireCodecPrefix = "avc1"`, then plays
 * three of them muted at 1080p for 30 s each and counts stalls over 1 s. The report goes to
 * the clipboard and the playback log. Nothing it does touches the audio player.
 */
internal object IosMusicVideoCheck {
    private const val TAG = "VideoCheck"

    private val _progress = MutableStateFlow<String?>(null)

    /** "3/20", "playing 2/3", "done: 19/20 H.264" — what the settings row shows. */
    val progress: StateFlow<String?> = _progress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /**
     * Searches that turn up a spread of official videos (OMV) and fan uploads (UGC), from
     * before 2015 and after 2020. Each gives up to two ids not already picked.
     */
    private val QUERIES = listOf(
        "Michael Jackson Billie Jean official video",
        "Queen Bohemian Rhapsody official video",
        "Adele Rolling in the Deep official video",
        "Coldplay Viva la Vida official video",
        "Taylor Swift Anti-Hero official video",
        "Dua Lipa Houdini official video",
        "Arijit Singh Kesariya official video",
        "Linkin Park In the End lyrics",
        "Daft Punk Get Lucky fan video",
        "lofi hip hop beats to study",
    )

    private class Probe(val id: String, val title: String, val result: String, val stream: InnerTubeXResolver.VideoStream?)

    fun run() {
        if (job?.isActive == true) return
        job = scope.launch {
            val report = StringBuilder()
            try {
                report.appendLine("BitChord music video check (H.264 coverage)")
                report.appendLine("signed in: ${com.music.bitchord.data.innertube.Innertube.cookie != null}, metered: ${IosNetwork.metered.value}")
                _progress.value = tr("Finding videos…")
                val picked = pickVideos()
                report.appendLine("videos found: ${picked.size}")
                val probes = ArrayList<Probe>()
                picked.forEachIndexed { index, (id, title) ->
                    _progress.value = "${index + 1}/${picked.size}"
                    probes += probe(id, title)
                }
                val avc = probes.filter { it.stream != null }
                report.appendLine("H.264 within ${InnerTubeXResolver.VIDEO_CLIENT_BUDGET} clients: ${avc.size}/${probes.size}")
                probes.forEach { report.appendLine("  ${it.id}  ${it.result}  — ${it.title.take(40)}") }

                report.appendLine("1080p playback (30 s each, stalls over 1 s):")
                val toPlay = avc.filter { (it.stream?.height ?: 0) >= 720 }.take(3)
                toPlay.forEachIndexed { index, probe ->
                    _progress.value = tr("Playing {0}/{1}", index + 1, toPlay.size)
                    report.appendLine("  ${probe.id}  ${playFor(probe.stream!!)}")
                }
                val go = probes.size >= 20 && avc.size >= 18
                report.appendLine(if (go) "verdict: GO (at least 18 of 20)" else "verdict: CHECK (${avc.size} of ${probes.size})")
                _progress.value = tr("Done: {0}/{1} H.264", avc.size, probes.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report.appendLine("check failed: ${e::class.simpleName}: ${e.message}")
                _progress.value = tr("Failed")
            }
            val text = report.toString()
            TrackLog.i(TAG, text)
            UIPasteboard.generalPasteboard.string = text
            IosPlayerHost.showMessage(tr("Music video check copied to the clipboard"))
        }
    }

    private suspend fun pickVideos(): List<Pair<String, String>> {
        val seen = LinkedHashMap<String, String>()
        for (query in QUERIES) {
            val songs = YtMusicRepository.search(query, SearchFilter.VIDEOS).getOrNull()
                ?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
            songs.filter { it.videoId !in seen }.take(2).forEach { seen[it.videoId] = it.title }
            if (seen.size >= 20) break
        }
        return seen.entries.take(20).map { it.key to it.value }
    }

    private suspend fun probe(id: String, title: String): Probe = try {
        val stream = withTimeoutOrNull(45_000L) {
            InnerTubeXResolver.extractVideo(id, maxHeight = 1080, requireCodecPrefix = "avc1")
        }
        val result = if (stream == null) {
            "NO avc1 (or timed out)"
        } else {
            val passed = if (stream.codecRejected.isEmpty()) "" else " after passing over ${stream.codecRejected.joinToString()}"
            "avc1 ${stream.width}x${stream.height} ${stream.codecs} via ${stream.profileId}$passed"
        }
        Probe(id, title, result, stream)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Probe(id, title, "error ${e::class.simpleName}: ${e.message?.take(80)}", null)
    }

    /** Plays [stream] muted for 30 s; reports start-up time and stalls over 1 s. */
    private suspend fun playFor(stream: InnerTubeXResolver.VideoStream): String {
        val url = NSURL.URLWithString(stream.url) ?: return "bad url"
        val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to stream.headers))
        val item = AVPlayerItem(asset = asset)
        val player = AVPlayer()
        player.muted = true
        player.replaceCurrentItemWithPlayerItem(item)
        player.play()
        val start = TimeSource.Monotonic.markNow()
        var startedAtMs: Long? = null
        var stallStart: Long? = null
        var stalls = 0
        var longest = 0L
        try {
            while (start.elapsedNow().inWholeMilliseconds < 45_000L) {
                val now = start.elapsedNow().inWholeMilliseconds
                if (item.status == AVPlayerItemStatusFailed) return "failed: ${item.error?.localizedDescription}"
                val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
                if (startedAtMs == null) {
                    if (playing) startedAtMs = now
                } else {
                    if (!playing && stallStart == null) stallStart = now
                    if (playing && stallStart != null) {
                        val length = now - stallStart!!
                        if (length > 1_000L) stalls++
                        longest = maxOf(longest, length)
                        stallStart = null
                    }
                    if (now - startedAtMs!! >= 30_000L) break
                }
                delay(250L)
            }
        } finally {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
        }
        val started = startedAtMs ?: return "never started in 45 s"
        return "${stream.height}p started in ${started} ms, stalls over 1 s: $stalls, longest pause ${longest} ms"
    }
}
