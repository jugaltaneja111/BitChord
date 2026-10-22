package com.music.bitchord.ios

import kotlinx.cinterop.pointed
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemTrack
import platform.AVFoundation.estimatedDataRate
import platform.AVFoundation.formatDescriptions
import platform.AVFoundation.mediaType
import platform.AVFoundation.tracks
import platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFRelease
import platform.Foundation.CFBridgingRetain
import com.music.bitchord.data.TrackLog
import com.music.bitchord.ios.ffmpeg.IosFfmpegPlayer
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.AVFoundation.setVolume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Two AVPlayers, one heard and one waiting: the phone's pair of ExoPlayers at their simplest. The
 * waiting one is handed the next track ahead of time ([prepareNext]), so the change of track is
 * either instant or a blend of the two ([startNext]). Each player's item carries its own
 * [TapDsp], so both sides of a crossfade keep their own loudness and share the equaliser.
 *
 * AVFoundation decodes AAC and ALAC/FLAC in MP4 itself, so the queue only ever hands it those (see
 * [IosPlayer]). Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioEngine(
    private val onEnded: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private inner class Deck {
        val player = AVPlayer()
        var item: AVPlayerItem? = null
        var dsp: TapDsp? = null
        var loudnessDb: Double? = null
        /** The track's real length when known; AVPlayer's own figure is only a fallback. */
        var knownDurationMs = 0L
        /** What this deck holds, for [IosPlayer] to match against its queue. */
        var key: String? = null
        /** The crossfade's share of this deck's volume. */
        var fade = 1f
        val observers = mutableListOf<NSObjectProtocol>()
        /** The FFmpeg player holding this deck's track instead of [player], for what AVPlayer can't open. */
        var ff: IosFfmpegPlayer? = null

        init {
            player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.25, 600), queue = null) { time ->
                if (this === active) tick(CMTimeGetSeconds(time))
            }
        }

        fun hold(url: String, headers: Map<String, String>, loudnessDb: Double?, key: String?, durationMs: Long, route: Route): Boolean {
            if (route.ffmpeg) {
                clearObservers()
                releaseFfmpeg()
                player.pause()
                player.replaceCurrentItemWithPlayerItem(null)
                item = null
                val dsp = TapDsp()
                this.dsp = dsp
                this.loudnessDb = loudnessDb
                this.key = key
                knownDurationMs = durationMs
                ff = IosFfmpegPlayer(
                    dsp = dsp,
                    onEnded = {
                        if (this === active) {
                            // A track that "ends" well short of its length didn't play: the FFmpeg
                            // path is new, and this is how a stream it misreads looks. Failing it
                            // hands the track to YouTube's AAC instead of skipping the queue on.
                            val at = ff?.positionMs ?: 0L
                            val length = knownDurationMs.takeIf { it > 0 } ?: ff?.durationMs ?: 0L
                            if (length > 0 && at < length - EARLY_END_MS) {
                                TrackLog.w(TAG, "FFmpeg ended at ${at}ms of ${length}ms: ${ff?.diagnostics()}")
                                onFailed("ended early")
                            } else {
                                onEnded()
                            }
                        }
                    },
                    onFailed = { message -> if (this === active) onFailed(message) },
                ).also { it.open(url, headers, dash = route.dash) }
                applyGain()
                return true
            }
            releaseFfmpeg()
            val nsUrl = (if (url.startsWith("/")) NSURL.fileURLWithPath(url) else NSURL.URLWithString(url))
                ?: return false
            // AVURLAssetHTTPHeaderFieldsKey: long-standing, if undocumented, and what every
            // AVFoundation player with request headers uses.
            val asset = AVURLAsset(uRL = nsUrl, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
            val item = AVPlayerItem(asset = asset)
            clearObservers()
            val dsp = TapDsp()
            this.item = item
            this.dsp = dsp
            this.loudnessDb = loudnessDb
            this.key = key
            knownDurationMs = durationMs
            applyGain()
            IosAudioTap.attach(item, dsp)
            player.replaceCurrentItemWithPlayerItem(item)
            return true
        }

        /** The track's loudness gain: in the tap once it runs, the volume until then. */
        fun applyGain() {
            val gain = IosSound.loudnessGain(loudnessDb)
            val dsp = dsp
            dsp?.targetGain = gain
            val levelled = if (dsp?.attached == true) 1f else min(gain, 1f)
            ff?.let { it.volume = fade * levelled } ?: player.setVolume(fade * levelled)
        }

        fun play() = ff?.play() ?: player.play()

        fun pause() = ff?.pause() ?: player.pause()

        fun seekTo(positionMs: Long) {
            ff?.seekTo(positionMs) ?: player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 1000))
        }

        /** The playhead in seconds, NaN when there is none yet. */
        val seconds: Double
            get() = ff?.let { it.positionMs / 1000.0 } ?: CMTimeGetSeconds(player.currentTime())

        fun releaseFfmpeg() {
            ff?.release()
            ff = null
        }

        /** Ends and starts are reported only for the deck being heard. */
        fun listen() {
            val item = item ?: return
            clearObservers()
            val center = NSNotificationCenter.defaultCenter
            observers += center.addObserverForName(
                AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
            ) { _ -> if (this === active) onEnded() }
            observers += center.addObserverForName(
                AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
            ) { _ -> if (this === active) onFailed(item.error?.localizedDescription ?: "playback failed") }
        }

        fun failed(): Boolean = ff?.failure != null || item?.status == AVPlayerItemStatusFailed

        /** Whether this deck has a track at all, on either player. */
        val holding: Boolean get() = ff != null || item != null

        fun clear() {
            clearObservers()
            releaseFfmpeg()
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            item = null
            dsp = null
            key = null
            loudnessDb = null
            knownDurationMs = 0L
            fade = 1f
        }

        fun clearObservers() {
            observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
            observers.clear()
        }
    }

    private var active = Deck()
    private var spare = Deck()

    /** What the deck being heard decodes, once its item is ready; null before then. */
    data class DecodedFormat(
        /** As Media3 would name it, so the shared stats line reads it: `audio/mp4a-latm`, `audio/flac`… */
        val mimeType: String?,
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitDepth: Int?,
        /** AVFoundation's estimate of the encoded rate, when it has one. */
        val estimatedKbps: Int?,
        val key: String?,
    )

    fun decodedFormat(): DecodedFormat? {
        active.ff?.let { ff ->
            if (!ff.isReady) return null
            val info = ff.format ?: return null
            return DecodedFormat(info.mimeType, info.sampleRateHz, info.channels, info.bitDepth, info.kbps, active.key)
        }
        val item = active.item ?: return null
        if (item.status != AVPlayerItemStatusReadyToPlay) return null
        val track = item.tracks.mapNotNull { (it as? AVPlayerItemTrack)?.assetTrack }
            .firstOrNull { it.mediaType == AVMediaTypeAudio } ?: return null
        val description = track.formatDescriptions.firstOrNull() ?: return null
        // The array holds the CMFormatDescription as an Objective-C object; a Kotlin cast to the
        // CoreMedia pointer type throws, so the pointer is taken across the bridge, and let go of.
        val ref = CFBridgingRetain(description) ?: return null
        val basic = try {
            CMAudioFormatDescriptionGetStreamBasicDescription(ref.reinterpret())?.pointed?.let {
                Triple(it.mFormatID, it.mSampleRate, Triple(it.mChannelsPerFrame, it.mBitsPerChannel, it.mFormatFlags))
            }
        } finally {
            CFRelease(ref)
        } ?: return null
        val (formatId, sampleRate, rest) = basic
        val (channelsPerFrame, bitsPerChannel, formatFlags) = rest
        val lossless = formatId == FOURCC_FLAC || formatId == FOURCC_ALAC || formatId == FOURCC_LPCM
        return DecodedFormat(
            mimeType = when (formatId) {
                FOURCC_AAC, FOURCC_AAC_HE, FOURCC_AAC_HE2 -> "audio/mp4a-latm"
                FOURCC_FLAC -> "audio/flac"
                FOURCC_ALAC -> "audio/alac"
                FOURCC_MP3 -> "audio/mpeg"
                FOURCC_EAC3 -> "audio/eac3"
                FOURCC_AC3 -> "audio/ac3"
                FOURCC_OPUS -> "audio/opus"
                FOURCC_LPCM -> "audio/raw"
                else -> null
            },
            sampleRateHz = sampleRate.toInt().takeIf { it > 0 },
            channels = channelsPerFrame.toInt().takeIf { it > 0 },
            // FLAC and ALAC state their depth in the flags rather than in mBitsPerChannel.
            bitDepth = when {
                formatId == FOURCC_LPCM -> bitsPerChannel.toInt().takeIf { it > 0 }
                formatId == FOURCC_FLAC || formatId == FOURCC_ALAC -> when (formatFlags.toInt()) {
                    1 -> 16
                    2 -> 20
                    3 -> 24
                    4 -> 32
                    else -> null
                }
                else -> null
            }.takeIf { lossless },
            estimatedKbps = (track.estimatedDataRate / 1000f).toInt().takeIf { it > 0 },
            key = active.key,
        )
    }

    /** The deck fading out under [active] during a crossfade, if one is. */
    private var outgoing: Deck? = null
    private var fadeJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    /** Whether the playhead is meant to be moving — true while it buffers, as a pressed Play is. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    val position = PlaybackPosition()

    init {
        // Playback, so the music keeps going with the screen locked or the silent switch on.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        IosSound.start()
        scope.launch {
            IosSound.gainVersion.collect { refreshGains() }
        }
        // A tap that starts a moment after its track hands the levelling over from the volume.
        scope.launch {
            while (true) {
                delay(500)
                refreshGains()
            }
        }
        // An FFmpeg deck has no periodic time observer; this is its clock, at the same rate.
        scope.launch {
            while (true) {
                delay(250)
                active.ff?.let { tickFfmpeg(it) }
            }
        }
    }

    /**
     * Plays [url], fetched with [headers] (googlevideo wants the resolving client's User-Agent),
     * or the file at [url] when it is an absolute path — a download.
     */
    fun load(
        url: String,
        headers: Map<String, String>,
        loudnessDb: Double? = null,
        key: String? = null,
        durationMs: Long = 0L,
        /** Where to begin: a party joins its track part-way through. */
        startMs: Long = 0L,
        /** False loads it paused, for a party that starts it on its own schedule. */
        startPlaying: Boolean = true,
        /** Which player opens it; see [Route]. */
        route: Route = Route.of(url),
    ) {
        finishFade()
        if (!active.hold(url, headers, loudnessDb, key, durationMs, route)) return onFailed("bad stream URL")
        active.listen()
        AVAudioSession.sharedInstance().setActive(true, error = null)
        _durationMs.value = 0L
        if (startMs > 0L) active.seekTo(startMs)
        position.report(startMs)
        position.seeks++
        if (startPlaying) play() else _isPlaying.value = false
    }

    /** Hands the waiting player the next track, paused and silent, so it is ready when wanted. */
    fun prepareNext(
        url: String,
        headers: Map<String, String>,
        loudnessDb: Double?,
        key: String,
        durationMs: Long = 0L,
        route: Route = Route.of(url),
    ) {
        if (spare === outgoing) finishFade()
        spare.fade = 0f
        if (!spare.hold(url, headers, loudnessDb, key, durationMs, route)) spare.clear()
        spare.pause()
    }

    /** Whether a crossfade is under way; the waiting player is the one fading out until it ends. */
    val isBlending: Boolean get() = outgoing != null

    /** Whether the waiting player holds [key] and has not failed to load it. */
    fun preparedFor(key: String): Boolean =
        !isBlending && spare.key == key && spare.holding && !spare.failed()

    /** What the waiting player holds, prepared or still loading. */
    val preparedKey: String? get() = if (isBlending) null else spare.key

    /** Drops whatever the waiting player holds. */
    fun clearPrepared() {
        if (spare !== outgoing) spare.clear()
    }

    /**
     * Makes the waiting player the heard one: straight away when [fadeMs] is 0, or blended in
     * over [fadeMs] while the old track fades out under it.
     */
    fun startNext(fadeMs: Long) {
        finishFade()
        val previous = active
        active = spare
        spare = previous
        active.listen()
        previous.clearObservers()
        _durationMs.value = 0L
        position.report(0L)
        position.seeks++
        AVAudioSession.sharedInstance().setActive(true, error = null)
        if (fadeMs <= 0L) {
            previous.clear()
            active.fade = 1f
            active.applyGain()
            play()
            return
        }
        outgoing = previous
        active.fade = 0f
        active.applyGain()
        play()
        fadeJob = scope.launch {
            var elapsed = 0L
            while (elapsed < fadeMs) {
                delay(FADE_STEP_MS)
                // Paused, the blend waits where it is.
                if (_isPlaying.value) elapsed += FADE_STEP_MS
                val t = (elapsed.toDouble() / fadeMs).coerceIn(0.0, 1.0)
                // Equal power, so the middle of the blend is not a dip.
                active.fade = sin(t * PI / 2).toFloat()
                previous.fade = cos(t * PI / 2).toFloat()
                active.applyGain()
                previous.applyGain()
            }
            fadeJob = null
            finishFade()
        }
    }

    /** Ends a blend at once: the new track at full volume, the old one gone. */
    private fun finishFade() {
        fadeJob?.cancel()
        fadeJob = null
        outgoing?.let { old ->
            outgoing = null
            old.clear()
        }
        if (active.fade != 1f) {
            active.fade = 1f
            active.applyGain()
        }
    }

    fun play() {
        active.play()
        outgoing?.play()
        _isPlaying.value = true
    }

    fun pause() {
        active.pause()
        outgoing?.pause()
        _isPlaying.value = false
        tick(active.seconds)
    }

    /** Whether the FFmpeg player holds the track now (a stream or a cached or downloaded file). */
    val onFfmpeg: Boolean get() = active.ff != null

    fun seekTo(positionMs: Long) {
        finishFade()
        // A seek jumps the playhead: the real-time watch starts over from the new position.
        watchedFf = null
        active.seekTo(positionMs)
        position.report(positionMs)
        position.seeks++
    }

    fun stop() {
        finishFade()
        active.clear()
        _isPlaying.value = false
        _isBuffering.value = false
    }

    private fun refreshGains() {
        active.applyGain()
        outgoing?.applyGain()
        if (spare !== outgoing) spare.applyGain()
    }

    private fun tick(seconds: Double) {
        active.ff?.let { ff ->
            tickFfmpeg(ff)
            return
        }
        val item = active.player.currentItem ?: return
        if (item.status == AVPlayerItemStatusFailed) {
            onFailed(item.error?.localizedDescription ?: "playback failed")
            stop()
            return
        }
        // YouTube's audio-only MP4 is fragmented, with no total length in its header, so
        // AVPlayer's figure is an estimate that can land at twice the truth. The track's own
        // length wins whenever the caller knew it.
        val known = active.knownDurationMs
        val duration = CMTimeGetSeconds(item.duration)
        if (known > 0L) {
            _durationMs.value = known
        } else if (!duration.isNaN() && duration > 0) {
            _durationMs.value = (duration * 1000).toLong()
        }
        val player = active.player
        val waiting = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        _isBuffering.value = _isPlaying.value && waiting
        position.advancing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        if (!seconds.isNaN()) position.report((seconds * 1000).toLong())
    }

    /** The FFmpeg deck's counterpart of [tick]; it has no time observer of its own, so it is polled. */
    private fun tickFfmpeg(ff: IosFfmpegPlayer) {
        if (ff.failure != null) {
            position.advancing = false
            _isBuffering.value = false
            return
        }
        watchFfmpeg(ff)
        val known = active.knownDurationMs
        _durationMs.value = if (known > 0L) known else ff.durationMs
        _isBuffering.value = _isPlaying.value && (ff.isBuffering || !ff.isReady)
        position.advancing = ff.isPlaying
        position.report(ff.positionMs)
    }

    private var watchedFf: IosFfmpegPlayer? = null
    private var watchStartWall = 0L
    private var watchStartPos = 0L
    private var lastDiagnostics = 0L
    private var stuckSince = 0L

    /**
     * Holds the FFmpeg player to real time while it is new: a playhead that outruns the clock by
     * half again over a few seconds is a stream it is misreading (silence going by at speed), and
     * fails over to AVPlayer. Also logs its state every few seconds, for "Copy playback log".
     */
    private fun watchFfmpeg(ff: IosFfmpegPlayer) {
        val now = com.music.bitchord.data.wallClockMs()
        if (now - lastDiagnostics >= DIAGNOSTICS_MS) {
            lastDiagnostics = now
            TrackLog.d(TAG, "ffmpeg ${active.key}: ${ff.diagnostics()}")
        }
        val pos = ff.positionMs
        // Asked to play, opened, not waiting on the network, and still not moving: silence.
        if (_isPlaying.value && ff.isReady && !ff.isBuffering && !ff.isPlaying) {
            if (stuckSince == 0L) stuckSince = now
            if (now - stuckSince > STUCK_MS) {
                TrackLog.w(TAG, "FFmpeg isn't playing: ${ff.diagnostics()}")
                stuckSince = 0L
                onFailed("won't start")
                return
            }
        } else {
            stuckSince = 0L
        }
        if (watchedFf !== ff || !ff.isPlaying || !_isPlaying.value) {
            watchedFf = ff.takeIf { it.isPlaying && _isPlaying.value }
            watchStartWall = now
            watchStartPos = pos
            return
        }
        val wall = now - watchStartWall
        if (wall < WATCH_WINDOW_MS) return
        val advanced = pos - watchStartPos
        if (advanced > wall * 3 / 2 + 500) {
            TrackLog.w(TAG, "FFmpeg ran ahead: ${advanced}ms in ${wall}ms: ${ff.diagnostics()}")
            watchedFf = null
            onFailed("ran ahead of real time")
            return
        }
        watchStartWall = now
        watchStartPos = pos
    }

    /**
     * Which player opens a stream. AVPlayer for everything it can — it is the one the lock screen,
     * AirPlay and the equaliser tap were built around — and FFmpeg for the rest: Opus and Vorbis,
     * WebM and Ogg, and DASH manifests, which AVPlayer has no parser for.
     */
    data class Route(val ffmpeg: Boolean, val dash: Boolean = false) {
        companion object {
            val AVPLAYER = Route(ffmpeg = false)

            /** By what the URL or file name says: a local `.webm`, a `.mpd`, a googlevideo `mime=audio/webm`. */
            fun of(url: String, mimeType: String? = null): Route {
                val path = url.substringBefore('?').lowercase()
                val mime = mimeType?.lowercase().orEmpty()
                val dash = path.endsWith(".mpd") || "dash" in mime
                val foreign = dash || FFMPEG_EXTENSIONS.any { path.endsWith(".$it") } ||
                    FFMPEG_CODECS.any { it in mime } ||
                    Regex("[?&]mime=audio%2F(webm|ogg)", RegexOption.IGNORE_CASE).containsMatchIn(url)
                return Route(ffmpeg = foreign, dash = dash)
            }

            private val FFMPEG_EXTENSIONS = listOf("webm", "weba", "opus", "ogg", "oga", "mka", "mpd")
            private val FFMPEG_CODECS = listOf("opus", "vorbis", "webm", "ogg")
        }
    }

    private companion object {
        const val FADE_STEP_MS = 40L
        const val TAG = "IosAudioEngine"
        const val EARLY_END_MS = 10_000L
        const val WATCH_WINDOW_MS = 3_000L
        const val DIAGNOSTICS_MS = 2_000L
        const val STUCK_MS = 8_000L

        private fun fourCc(code: String): UInt = code.fold(0u) { acc, c -> (acc shl 8) or c.code.toUInt() }
        val FOURCC_AAC = fourCc("aac ")
        val FOURCC_AAC_HE = fourCc("aach")
        val FOURCC_AAC_HE2 = fourCc("aacp")
        val FOURCC_FLAC = fourCc("flac")
        val FOURCC_ALAC = fourCc("alac")
        val FOURCC_MP3 = fourCc(".mp3")
        val FOURCC_EAC3 = fourCc("ec-3")
        val FOURCC_AC3 = fourCc("ac-3")
        val FOURCC_OPUS = fourCc("opus")
        val FOURCC_LPCM = fourCc("lpcm")
    }
}
