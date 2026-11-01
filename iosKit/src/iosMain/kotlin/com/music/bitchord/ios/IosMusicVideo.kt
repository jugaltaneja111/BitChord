@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.metrolist.innertubex.extraction.StreamResolveException
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.systemNanoTime
import com.music.bitchord.data.wallClockMs
import com.music.bitchord.playback.MusicVideoSync
import com.music.bitchord.ui.player.MusicVideoQualityUi
import com.music.bitchord.ui.player.MusicVideoState
import com.music.bitchord.ui.rememberIsForeground
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGSizeMake
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortAirPlay
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.preventsDisplaySleepDuringVideoPlayback
import platform.AVFoundation.setAutomaticallyWaitsToMinimizeStalling
import platform.AVFoundation.setPreventsDisplaySleepDuringVideoPlayback
import platform.AVFoundation.currentTime
import platform.AVFoundation.errorLog
import platform.AVFoundation.pause
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.AVFoundation.setPreferredMaximumResolution
import platform.AVFoundation.setRate
import platform.AVFoundation.timeControlStatus
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIColor
import kotlin.math.abs

/**
 * A music video's picture on iOS: upstream's Android MusicVideoEngine
 * (b690dc14, app/.../ui/player/MusicVideoPlayer.kt) on AVFoundation, as
 * docs/designs/music-videos.md §2 lays out.
 *
 * One muted [AVPlayer] plays the video-only stream and follows [IosPlayer]'s clock; it never
 * drives it. The audio is the music player's own, untouched: this player is muted, its stream
 * has no audio track, it has no now-playing session, and none of its failures reach the audio.
 * It streams only while a surface claims the picture (plus a 4 s grace for a rotation or a sheet
 * reopening), and is let go of at once when the app goes to the background.
 *
 * Main thread throughout.
 */
internal object IosMusicVideo {
    private const val TAG = "MusicVideo"
    private const val RELEASE_GRACE_MS = 4_000L
    private const val STREAM_TTL_MS = 60 * 60 * 1000L
    private const val KEPT_STREAMS = 8

    /** Narrower than this is a cover held still, not a picture worth showing. */
    private const val MIN_VIDEO_ASPECT = 1.2f

    /** Where Auto opens: the top of YouTube's H.264 ladder, and less on a metered link. */
    private const val AUTO_HEIGHT = 1080
    private const val METERED_HEIGHT = 480

    /** What an HLS picture is taken to be until its first rung says otherwise. */
    private const val DEFAULT_ASPECT = 16f / 9f

    /** The rungs offered for an HLS picture, whose own list isn't read: YouTube's ladder. */
    private val HLS_HEIGHTS = listOf(1080, 720, 480, 360, 240)
    private const val QUALITY_DEBOUNCE_MS = 500L

    /** googlevideo's ways of refusing a URL. */
    private val REFUSAL_CODES = setOf(403L, 404L, 410L)

    private val ESCALATED_REASONS = setOf(
        StreamResolveException.Reason.AGE_RESTRICTED,
        StreamResolveException.Reason.NO_PLAYABLE_STREAM,
    )

    private val scope = MainScope()

    private val player: AVPlayer by lazy {
        AVPlayer().apply {
            setMuted(true)
            // The sync loop alone decides its rate; it must not stall to buffer on its own.
            setAutomaticallyWaitsToMinimizeStalling(false)
            setPreventsDisplaySleepDuringVideoPlayback(true)
        }
    }

    private class Claim(val view: CanvasPlayerView, val priority: Int, val fit: Boolean)

    private val claims = ArrayList<Claim>()

    /** The surface in front: the highest priority, the latest of equals. */
    private fun top(): Claim? = claims.lastOrNull { c -> claims.none { it.priority > c.priority } }

    private var loadedId: String? = null
    private var loadedProfile: String? = null

    /** The loaded picture is an HLS manifest, whose size shows only once it plays. */
    private var loadedHls = false
    private var loadJob: Job? = null
    private var syncJob: Job? = null
    private var releaseJob: Job? = null
    private var qualityJob: Job? = null
    private var lastSeekAt = 0L
    private var seekStartedAt = 0L
    private var seekPending = false
    private var seekLeadMs = 250L

    private val streams = LinkedHashMap<String, Triple<InnerTubeXResolver.VideoStream, Int, Long>>()
    private val noPicture = HashSet<String>()
    private val ageGated = HashSet<String>()
    private val refused = HashMap<String, MutableSet<String>>()

    private val _aspect = MutableStateFlow(16f / 9f)
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val _quality = MutableStateFlow(MusicVideoQualityUi(held = IosSettings.musicVideoHeight.flow.value.takeIf { it > 0 }))
    val quality: StateFlow<MusicVideoQualityUi> = _quality.asStateFlow()

    private val _state = MutableStateFlow(MusicVideoState.None)

    /** Loading, Showing, or None — what the player's video mode keys on. */
    val state: StateFlow<MusicVideoState> = _state.asStateFlow()

    private val _rendered = MutableStateFlow(false)
    val rendered: StateFlow<Boolean> = _rendered.asStateFlow()

    private val airPlay = MutableStateFlow(onAirPlay())
    private var started = false

    /** Starts following the player. Idempotent. */
    fun start() {
        if (started) return
        started = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            // Nothing can see it; the grace a rotation gets is not for this.
            releaseJob?.cancel()
            if (loadedId != null) TrackLog.d(TAG, "video: app in the background; let go")
            unload()
        }
        center.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { _ ->
            airPlay.value = onAirPlay()
        }
        scope.launch {
            combine(IosPlayer.song, airPlay, InnerTubeXResolver.sessionGeneration) { song, air, generation ->
                Triple(song, air, generation)
            }.distinctUntilChanged().collectLatest { (song, air, _) ->
                if (lastGeneration != InnerTubeXResolver.sessionGeneration.value) {
                    lastGeneration = InnerTubeXResolver.sessionGeneration.value
                    noPicture.clear()
                    streams.clear()
                    refused.clear()
                }
                refresh(song, air)
            }
        }
        scope.launch {
            snapshotFlow { IosPlayer.position.seeks }.collectLatest { syncNow() }
        }
        scope.launch { IosPlayer.isPlaying.collectLatest { syncNow() } }
    }

    private var lastGeneration = 0

    /** Answers [state] for the current song, and loads or lets go to match. */
    private fun refresh(song: Song? = IosPlayer.song.value, air: Boolean = airPlay.value) {
        val id = song?.videoId?.takeIf { song.isVideo }
        _state.value = when {
            id == null || air || id in noPicture -> MusicVideoState.None
            loadedId == id && _rendered.value -> MusicVideoState.Showing
            else -> MusicVideoState.Loading
        }
        when {
            id == null || air || id in noPicture -> unload()
            claims.isEmpty() -> Unit
            loadedId != id -> load(id)
        }
    }

    private fun onAirPlay(): Boolean =
        AVAudioSession.sharedInstance().currentRoute.outputs.any {
            (it as? AVAudioSessionPortDescription)?.portType == AVAudioSessionPortAirPlay
        }

    /** Puts the picture on [view] until the returned release is called, unless a higher [priority] holds it. */
    fun claim(view: CanvasPlayerView, priority: Int, fit: Boolean): () -> Unit {
        start()
        releaseJob?.cancel()
        claims.removeAll { it.view === view }
        claims += Claim(view, priority, fit)
        attachTop()
        refresh()
        return release@{
            val wasTop = top()?.view === view
            claims.removeAll { it.view === view }
            if (view.playerLayer.player != null) view.playerLayer.player = null
            if (!wasTop) return@release
            if (claims.isNotEmpty()) {
                attachTop()
            } else {
                releaseJob = scope.launch {
                    delay(RELEASE_GRACE_MS)
                    if (claims.isEmpty()) unload()
                }
            }
        }
    }

    /** The picture to the front claim's layer, and none on the rest; a paused frame repainted. */
    private fun attachTop() {
        val top = top() ?: return
        claims.forEach { if (it !== top && it.view.playerLayer.player != null) it.view.playerLayer.player = null }
        top.view.playerLayer.videoGravity = if (top.fit) AVLayerVideoGravityResizeAspect else AVLayerVideoGravityResizeAspectFill
        if (top.view.playerLayer.player == null) {
            top.view.playerLayer.player = player
            // A layer newly given a paused player shows nothing until a frame is decoded for it.
            if (player.rate == 0f && player.currentItem != null) seekTo(player.currentTime().let { CMTimeGetSeconds(it) * 1000 }.toLong(), exact = true)
        }
    }

    fun setQuality(height: Int?) {
        IosSettings.musicVideoHeight.set(height ?: 0)
        _quality.value = _quality.value.copy(held = height)
        qualityJob?.cancel()
        qualityJob = scope.launch {
            delay(QUALITY_DEBOUNCE_MS)
            val id = loadedId ?: return@launch
            streams.remove(id)
            load(id)
        }
    }

    private fun capFor(): Int {
        // Unknown counts as metered: the cheaper guess for data.
        val auto = if (IosNetwork.metered.value != false) METERED_HEIGHT else AUTO_HEIGHT
        return _quality.value.held?.coerceAtMost(AUTO_HEIGHT) ?: auto
    }

    private suspend fun resolve(videoId: String, cap: Int): InnerTubeXResolver.VideoStream? {
        val skip = refused[videoId].orEmpty().toSet()
        if (videoId !in ageGated) {
            val plain = try {
                InnerTubeXResolver.extractVideo(videoId, cap, skip, requireCodecPrefix = "avc1", allowHls = true)
            } catch (e: StreamResolveException) {
                if (e.reason !in ESCALATED_REASONS) throw e
                null
            }
            if (plain != null) return plain
            TrackLog.d(TAG, "video: nothing asked plainly; asking as age-restricted", about = videoId)
        }
        return InnerTubeXResolver.extractVideo(videoId, cap, skip, ageRestricted = true, requireCodecPrefix = "avc1", allowHls = true)
            ?.also { ageGated += videoId }
    }

    private fun load(videoId: String) {
        unload(keepState = true)
        loadedId = videoId
        _state.value = MusicVideoState.Loading
        val cap = capFor()
        loadJob = scope.launch {
            val stream = try {
                cachedStream(videoId, cap)
                    ?: resolve(videoId, cap)?.also { streams[videoId] = Triple(it, cap, wallClockMs()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamResolveException) {
                TrackLog.w(TAG, "video: no picture (${e.reason}): ${e.message}", about = videoId)
                if (loadedId != videoId) return@launch
                loadedId = null
                if (e.reason != StreamResolveException.Reason.NETWORK) noPicture += videoId
                refresh()
                return@launch
            } catch (e: Exception) {
                TrackLog.w(TAG, "video: couldn't resolve the picture: ${e.message}", about = videoId)
                if (loadedId == videoId) loadedId = null
                _state.value = MusicVideoState.None
                return@launch
            }
            if (loadedId != videoId) return@launch
            // A manifest's size is known only once a rung plays; sync() checks it then.
            if (stream == null || (!stream.isHls && stream.aspect < MIN_VIDEO_ASPECT)) {
                TrackLog.d(TAG, "video: no H.264 picture to show (${stream?.let { "${it.width}x${it.height}" } ?: "none"})", about = videoId)
                noPicture += videoId
                loadedId = null
                refresh()
                return@launch
            }
            val url = NSURL.URLWithString(stream.url) ?: return@launch
            val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to stream.headers))
            _aspect.value = if (stream.isHls) DEFAULT_ASPECT else stream.aspect
            val heights = if (stream.isHls) HLS_HEIGHTS else stream.heights
            _quality.value = _quality.value.copy(
                heights = heights.filter { it <= AUTO_HEIGHT },
                activeHeight = if (stream.isHls) cap else stream.height,
            )
            loadedProfile = stream.profileId
            loadedHls = stream.isHls
            val item = AVPlayerItem(asset = asset)
            if (stream.isHls) {
                // The manifest lists every rung: hold the player to the asked-for height.
                item.setPreferredMaximumResolution(CGSizeMake(cap * 16.0 / 9.0, cap.toDouble()))
            }
            player.replaceCurrentItemWithPlayerItem(item)
            attachTop()
            seekTo(target() + seekLeadMs, exact = false)
            if (IosPlayer.isPlaying.value) player.setRate(IosPlayer.heardRate)
            startSync()
        }
    }

    private fun cachedStream(videoId: String, cap: Int): InnerTubeXResolver.VideoStream? {
        val (stream, askedFor, at) = streams[videoId] ?: return null
        if (askedFor != cap || wallClockMs() - at > STREAM_TTL_MS) {
            streams.remove(videoId)
            return null
        }
        return stream
    }

    private fun unload(keepState: Boolean = false) {
        loadJob?.cancel()
        loadJob = null
        syncJob?.cancel()
        syncJob = null
        val had = loadedId != null || player.currentItem != null
        loadedId = null
        loadedProfile = null
        loadedHls = false
        _rendered.value = false
        _quality.value = _quality.value.copy(heights = emptyList(), activeHeight = null)
        if (had) {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            TrackLog.d(TAG, "video: unloaded")
        }
        while (streams.size > KEPT_STREAMS) streams.remove(streams.keys.first())
        if (!keepState && _state.value == MusicVideoState.Showing) _state.value = MusicVideoState.Loading
    }

    /** Where the audio is as heard, ms: the last reading carried on by the clock, less the output's delay. */
    private fun target(): Long {
        val position = IosPlayer.position
        val elapsedMs = if (IosPlayer.isPlaying.value && position.sampledAtNanos > 0L) {
            ((systemNanoTime() - position.sampledAtNanos) / 1_000_000L) * IosPlayer.heardRate
        } else 0f
        return (position.positionMs + elapsedMs.toLong() - IosPlayer.heardLatencyMs).coerceAtLeast(0L)
    }

    private fun videoMs(): Long = CMTimeGetSeconds(player.currentTime()).let { if (it.isNaN()) 0L else (it * 1000).toLong() }

    private fun seekTo(ms: Long, exact: Boolean) {
        lastSeekAt = wallClockMs()
        seekStartedAt = lastSeekAt
        seekPending = true
        val time = CMTimeMakeWithSeconds(ms.coerceAtLeast(0L) / 1000.0, 600)
        if (exact) {
            player.seekToTime(time, kCMTimeZero.readValue(), kCMTimeZero.readValue())
        } else {
            player.seekToTime(time)
        }
    }

    private fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                sync()
                delay(MusicVideoSync.SYNC_EVERY_MS)
            }
        }
    }

    private fun syncNow() {
        if (syncJob?.isActive == true) sync()
    }

    private fun sync() {
        val item = player.currentItem ?: return
        if (loadedId == null) return
        if (item.status == AVPlayerItemStatusFailed) return onFailed(item)
        // First frame: the still under it can go.
        val ready = top()?.view?.playerLayer?.readyForDisplay == true
        if (ready != _rendered.value) {
            _rendered.value = ready
            if (ready) _state.value = MusicVideoState.Showing
        }
        if (item.status != AVPlayerItemStatusReadyToPlay) return
        val portrait = item.presentationSize.useContents {
            if (width > 0 && height > 0) _aspect.value = (width / height).toFloat()
            width > 0 && height > 0 && width / height < MIN_VIDEO_ASPECT
        }
        if (portrait && loadedHls) {
            val id = loadedId
            TrackLog.d(TAG, "video: HLS picture is portrait; showing the artwork", about = id)
            if (id != null) noPicture += id
            unload()
            refresh()
            return
        }
        val now = wallClockMs()
        val buffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        if (seekPending && player.timeControlStatus == AVPlayerTimeControlStatusPlaying) {
            seekPending = false
            seekLeadMs = MusicVideoSync.nextSeekLead(seekLeadMs, now - seekStartedAt)
        }
        val action = MusicVideoSync.step(
            target = target(),
            videoMs = videoMs(),
            playing = IosPlayer.isPlaying.value,
            inGap = IosPlayer.inGap,
            videoBuffering = buffering,
            nowMs = now,
            lastSeekAt = lastSeekAt,
            seekLeadMs = seekLeadMs,
            rate = IosPlayer.heardRate,
        )
        when (action) {
            MusicVideoSync.Action.Pause, MusicVideoSync.Action.Hold -> if (player.rate != 0f) player.pause()
            is MusicVideoSync.Action.Seek -> {
                seekTo(action.toMs, exact = !action.play)
                if (action.play) player.setRate(IosPlayer.heardRate) else player.pause()
            }
            is MusicVideoSync.Action.Play -> if (abs(player.rate - action.rate) > 0.005f) player.setRate(action.rate)
        }
    }

    private fun onFailed(item: AVPlayerItem) {
        val id = loadedId ?: return
        val status = (item.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent)?.errorStatusCode
        TrackLog.w(TAG, "video: playback failed (HTTP ${status ?: "?"}): ${item.error?.localizedDescription}", about = id)
        streams.remove(id)
        val profile = loadedProfile
        if (profile != null && status != null && status in REFUSAL_CODES) {
            val skip = refused.getOrPut(id) { HashSet() }
            if (skip.add(profile) && skip.size <= InnerTubeXResolver.VIDEO_CLIENT_BUDGET) {
                TrackLog.w(TAG, "video: $profile refused; asking the next client", about = id)
                InnerTubeXResolver.onVideoRefused()
                load(id)
                return
            }
        }
        noPicture += id
        unload()
        refresh()
    }
}

/**
 * Where a music video's picture is drawn: a view whose layer is an AVPlayerLayer, handed the
 * shared player while it is the front claim. It claims only while the app is in the foreground,
 * so coming back to the app claims, and reloads, again.
 */
@Composable
internal fun IosMusicVideoSurface(
    videoId: String,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
    priority: Int,
) {
    val view = remember { CanvasPlayerView().apply { backgroundColor = UIColor.clearColor } }
    val foreground = rememberIsForeground()
    val reportRendered by rememberUpdatedState(onRenderedChanged)
    DisposableEffect(view, priority, fit, foreground, videoId) {
        val release = if (foreground) IosMusicVideo.claim(view, priority, fit) else null
        onDispose { release?.invoke() }
    }
    LaunchedEffect(videoId) {
        IosMusicVideo.rendered.collectLatest { rendered ->
            reportRendered(rendered && view.playerLayer.player != null)
        }
    }
    UIKitView(
        factory = { view },
        modifier = modifier,
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
}
