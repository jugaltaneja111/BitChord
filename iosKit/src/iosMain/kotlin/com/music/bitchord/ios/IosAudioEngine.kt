package com.music.bitchord.ios

import kotlinx.cinterop.pointed
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVAudioTimePitchAlgorithmSpectral
import platform.AVFoundation.audioTimePitchAlgorithm
import com.music.bitchord.ios.automix.IosAutomixRide
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
import kotlin.coroutines.coroutineContext
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
import platform.CoreMedia.kCMTimeZero
import kotlinx.cinterop.readValue
import platform.AVFoundation.canPlayFastForward
import platform.AVFoundation.rate
import platform.AVFoundation.setRate
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

        // Skip silence on AVPlayer, which can't drop samples: a gap past a second is played muted at
        // speed, then rewound a little once sound comes back, so the music after it starts whole.
        /** Where in the track the silence now being heard began; NaN while there's sound. */
        private var quietSince = Double.NaN
        /** The rate a gap is being fast-forwarded at; 0 when none is. */
        private var gapRate = 0f
        /** No new gap before this point: the rewind after one lands back in its tail. */
        private var noGapBefore = 0.0

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
                    onFailed = { message ->
                        if (this === active && failureReported !== ff) {
                            failureReported = ff
                            onFailed(message)
                        }
                    },
                ).also {
                    it.skipSilence = IosSettings.skipSilence.flow.value
                    it.open(url, headers, dash = route.dash)
                }
                applyGain()
                return true
            }
            releaseFfmpeg()
            val nsUrl = (if (url.startsWith("/")) NSURL.fileURLWithPath(url) else NSURL.URLWithString(url))
                ?: return false
            // AVURLAssetHTTPHeaderFieldsKey: long-standing, if undocumented, and what every
            // AVFoundation player with request headers uses.
            // A file on the phone is read through for its length: a fragmented MP4 (the song
            // cache's YouTube copies) otherwise gets an estimate that can be twice the truth.
            val asset = AVURLAsset(
                uRL = nsUrl,
                options = if (nsUrl.isFileURL()) {
                    mapOf<Any?, Any?>("AVURLAssetPreferPreciseDurationAndTimingKey" to true)
                } else {
                    mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers)
                },
            )
            val item = AVPlayerItem(asset = asset)
            // Automix's tempo stretch: the best of AVFoundation's time-stretchers for music.
            item.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmSpectral
            clearObservers()
            val dsp = TapDsp()
            this.item = item
            this.dsp = dsp
            this.loudnessDb = loudnessDb
            this.key = key
            knownDurationMs = durationMs
            applyGain()
            resetGap()
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
            if (gapRate > 0f) endGap(null)
            quietSince = Double.NaN
            ff?.seekTo(positionMs) ?: player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 1000))
        }

        /** Like [seekTo], but to the exact sample: a swap onto another copy has to line up. */
        fun seekExactly(positionMs: Long) {
            ff?.seekTo(positionMs) ?: player.seekToTime(
                CMTimeMakeWithSeconds(positionMs / 1000.0, 1000), kCMTimeZero.readValue(), kCMTimeZero.readValue(),
            )
        }

        /** Whether sound is coming out of it now, rather than it waiting on data. */
        val sounding: Boolean
            get() = ff?.isPlaying ?: (player.timeControlStatus == AVPlayerTimeControlStatusPlaying)

        /** The playhead in seconds, NaN when there is none yet. */
        val seconds: Double
            get() = ff?.let { it.positionMs / 1000.0 } ?: CMTimeGetSeconds(player.currentTime())

        fun releaseFfmpeg() {
            ff?.release()
            ff = null
        }

        /** Called about every 50 ms for the deck being heard. */
        fun skipSilence(enabled: Boolean) {
            ff?.let {
                it.skipSilence = enabled
                return
            }
            val dsp = dsp ?: return
            val item = item ?: return
            val sound = dsp.heardSound
            dsp.heardSound = false
            val now = CMTimeGetSeconds(player.currentTime())
            if (now.isNaN()) return
            if (gapRate > 0f) {
                when {
                    // Paused, or played on at its own rate: the fast-forward is over.
                    player.rate != gapRate -> endGap(null)
                    sound -> endGap(now)
                }
                return
            }
            val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying && player.rate == 1f
            if (!enabled || !dsp.attached || !playing || sound || now < noGapBefore) {
                quietSince = Double.NaN
                return
            }
            if (quietSince.isNaN()) {
                quietSince = now
            } else if (now - quietSince >= MIN_SILENCE_S) {
                gapRate = if (item.canPlayFastForward) GAP_RATE else 2f
                dsp.muted = true
                player.setRate(gapRate)
            }
        }

        /** Ends a fast-forwarded gap; with [soundAt], rewinds to just before the sound came back. */
        private fun endGap(soundAt: Double?) {
            gapRate = 0f
            quietSince = Double.NaN
            val dsp = dsp
            if (soundAt == null) {
                // Not paused, so AVPlayer chose a rate of its own: back to real time.
                if (player.rate != 0f) player.setRate(1f)
                dsp?.muted = false
                return
            }
            player.setRate(1f)
            noGapBefore = soundAt
            val back = (soundAt - GAP_REWIND_S).coerceAtLeast(0.0)
            player.seekToTime(CMTimeMakeWithSeconds(back, 1000), kCMTimeZero.readValue(), kCMTimeZero.readValue()) { _ ->
                dsp?.muted = false
            }
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
            resetGap()
        }

        fun resetGap() {
            gapRate = 0f
            quietSince = Double.NaN
            noGapBefore = 0.0
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

    /** What the Audio Pipeline readout says about the deck being heard, past its format. */
    data class Chain(
        /** FFmpeg for what AVFoundation can't open, else AVFoundation's own decoder. */
        val ffmpeg: Boolean,
        /** The track's level correction in dB, 0 when none applies; null with no measurement. */
        val loudnessGainDb: Double?,
        /** The audio tap (equaliser, loudness, skip silence) is in the chain. */
        val tapAttached: Boolean,
    )

    fun chain(): Chain? {
        val deck = active
        if (deck.ff == null && deck.item == null) return null
        val gain = IosSound.loudnessGain(deck.loudnessDb)
        return Chain(
            ffmpeg = deck.ff != null,
            loudnessGainDb = deck.loudnessDb?.let { 20.0 * kotlin.math.log10(gain.toDouble()) },
            tapAttached = deck.dsp?.attached == true,
        )
    }

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
        IosInterruptions.start()
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
        scope.launch {
            while (true) {
                delay(50)
                active.skipSilence(IosSettings.skipSilence.flow.value)
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
        abandonSwap()
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
        abandonSwap()
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

    /** Whether the waiting player holds [key], opened and ready to be cued and started. */
    fun readyFor(key: String): Boolean =
        preparedFor(key) && (spare.ff?.isReady ?: (spare.item?.status == AVPlayerItemStatusReadyToPlay))

    /** Puts the waiting player's track at [positionMs], where Automix brings it in. */
    fun cueNext(positionMs: Long) {
        if (!isBlending) spare.seekExactly(positionMs)
    }

    /** Drops whatever the waiting player holds. */
    fun clearPrepared() {
        if (spare !== outgoing) spare.clear()
    }

    /**
     * Makes the waiting player the heard one: straight away when [fadeMs] is 0, or blended in
     * over [fadeMs] while the old track fades out under it. Automix cues the new track at
     * [startAtMs] (see [cueNext]) and may play it at [incomingRate] through the blend, to hold
     * the two beats together; only AVPlayer can, so a track the FFmpeg player holds stays at 1.
     */
    /**
     * Starts the waiting track, blended in over [fadeMs] from [startAtMs] at [incomingRate]. With
     * [ride], advanced Automix's moves set both faders and both decks' filters and echo each tick
     * instead of the plain equal-power pair; [outgoingRate] is the speed the heard track was ramped
     * up to for it, held through the blend; [beatMs] paces the incoming track's ease back to its
     * own tempo afterwards.
     */
    fun startNext(
        fadeMs: Long,
        startAtMs: Long = 0L,
        incomingRate: Float = 1f,
        ride: IosAutomixRide? = null,
        outgoingRate: Float = 1f,
        beatMs: Long = 0L,
    ) {
        abandonSwap()
        finishFade()
        val previous = active
        active = spare
        spare = previous
        active.listen()
        previous.clearObservers()
        _durationMs.value = 0L
        position.report(startAtMs)
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
        // play() puts an AVPlayer back at 1x: the ramped outgoing speed and the incoming stretch go on after it.
        if (outgoingRate != 1f && previous.ff == null) previous.player.setRate(outgoingRate)
        if (incomingRate != 1f && active.ff == null) {
            blendRate = incomingRate
            easeBeatMs = beatMs
            active.player.setRate(incomingRate)
        }
        _mixing.value = ride?.isRealMix == true
        fadeJob = scope.launch {
            var elapsed = 0L
            while (elapsed < fadeMs) {
                delay(FADE_STEP_MS)
                // Paused, the blend waits where it is.
                if (_isPlaying.value) elapsed += FADE_STEP_MS
                val t = (elapsed.toDouble() / fadeMs).coerceIn(0.0, 1.0)
                if (ride != null) {
                    val moves = ride.at(t.toFloat())
                    active.fade = moves.incomingGain
                    previous.fade = moves.outgoingGain
                    active.dsp?.fx?.aim(moves.incomingLowPassHz, moves.incomingHighPassHz)
                    previous.dsp?.fx?.let { fx ->
                        fx.aim(moves.outgoingLowPassHz, moves.outgoingHighPassHz)
                        moves.echoSend?.let { fx.setEcho(ride.echoSeconds, it, moves.echoDry) }
                    }
                } else {
                    // Equal power, so the middle of the blend is not a dip.
                    active.fade = sin(t * PI / 2).toFloat()
                    previous.fade = cos(t * PI / 2).toFloat()
                }
                active.applyGain()
                previous.applyGain()
            }
            active.dsp?.fx?.park()
            // An echo out rings on over the new track: the old one plays on, silent but for its repeats.
            if (ride != null && ride.echoTailMs > 0L) {
                var tail = 0L
                while (tail < ride.echoTailMs && outgoing === previous) {
                    delay(FADE_STEP_MS)
                    if (_isPlaying.value) tail += FADE_STEP_MS
                }
            }
            fadeJob = null
            finishFade()
        }
    }

    /**
     * Moves the track being heard onto another copy of it — a better one found after it started —
     * at the same point. The waiting player opens it there, silent; once it is sounding it is put
     * back in step with the heard one and the two blend over [SWAP_FADE_MS], so the swap is heard
     * as a moment's overlap of the same music rather than a break. Refused while a crossfade runs or
     * the next track is already waiting. [onDone] says whether it happened.
     *
     * A version switch (the video and its audio release) passes the other cut's [newKey] and the
     * [offsetMs] its music runs ahead of this one by — smart audio alignment's measurement — so it
     * comes in on the same moment of the song, over [fadeMs].
     */
    fun swapStream(
        url: String,
        headers: Map<String, String>,
        loudnessDb: Double?,
        durationMs: Long,
        route: Route,
        newKey: String? = null,
        offsetMs: Long = 0L,
        fadeMs: Long = SWAP_FADE_MS,
        onDone: (Boolean) -> Unit,
    ) {
        if (isBlending || spare.holding || swapJob?.isActive == true) return onDone(false)
        val key = active.key
        val incoming = spare
        incoming.fade = 0f
        if (!incoming.hold(url, headers, loudnessDb, newKey ?: key, durationMs, route)) {
            incoming.clear()
            return onDone(false)
        }
        incoming.applyGain()
        swapJob = scope.launch {
            var swapped = false
            try {
                fun inStep() = ((active.seconds * 1000).toLong() + offsetMs).coerceAtLeast(0L)
                incoming.seekExactly(inStep() + SWAP_LEAD_MS)
                if (_isPlaying.value) incoming.play()
                // Ready means sounding when playing; when paused, opened and not failed.
                var waited = 0L
                while (waited < SWAP_READY_MS) {
                    if (active.key != key || isBlending || incoming !== spare || incoming.failed()) return@launch
                    // Follows a pause or a resume meanwhile.
                    if (_isPlaying.value) incoming.play() else incoming.pause()
                    val ready = if (_isPlaying.value) incoming.sounding else incoming.item?.status == AVPlayerItemStatusReadyToPlay || incoming.ff?.isReady == true
                    if (ready) break
                    delay(100)
                    waited += 100
                }
                if (waited >= SWAP_READY_MS) return@launch
                if (_isPlaying.value) {
                    // Back in step: it started late by however long it took to sound.
                    incoming.seekExactly(inStep())
                    delay(SWAP_SETTLE_MS)
                    if (active.key != key || isBlending || incoming !== spare) return@launch
                } else {
                    incoming.seekExactly(inStep())
                }
                // A copy that failed on its way in — a server refusing the second request — is
                // never put in front of the listener: they keep the one that plays.
                if (incoming.failed()) {
                    TrackLog.w(TAG, "swap abandoned: the new copy failed before it took over")
                    return@launch
                }
                val previous = active
                active = incoming
                spare = previous
                active.listen()
                previous.clearObservers()
                swapped = true
                // The other cut's clock: the bar and the lyrics jump to it rather than glide.
                if (newKey != null && newKey != key) {
                    _durationMs.value = 0L
                    position.seeks++
                }
                if (!_isPlaying.value) {
                    previous.clear()
                    active.fade = 1f
                    active.applyGain()
                    return@launch
                }
                outgoing = previous
                fadeJob = scope.launch {
                    var elapsed = 0L
                    while (elapsed < fadeMs) {
                        delay(FADE_STEP_MS)
                        elapsed += FADE_STEP_MS
                        // Failed mid-blend: the old copy, still sounding, takes the track back.
                        if (active.failed() && outgoing === previous) {
                            TrackLog.w(TAG, "swap undone: the new copy failed during the blend")
                            val failed = active
                            active = previous
                            spare = failed
                            outgoing = null
                            failed.clear()
                            active.listen()
                            active.fade = 1f
                            active.applyGain()
                            fadeJob = null
                            return@launch
                        }
                        val t = (elapsed.toDouble() / fadeMs).coerceIn(0.0, 1.0)
                        active.fade = sin(t * PI / 2).toFloat()
                        previous.fade = cos(t * PI / 2).toFloat()
                        active.applyGain()
                        previous.applyGain()
                    }
                    fadeJob = null
                    finishFade()
                }
            } finally {
                // Unless abandonSwap already let it go and gave the waiting player to something else.
                if (!swapped && swapJob === coroutineContext[Job] && incoming === spare) incoming.clear()
                if (swapJob === coroutineContext[Job]) swapJob = null
                onDone(swapped)
            }
        }
    }

    private var swapJob: Job? = null

    /** The FFmpeg player whose failure has been passed on, so it is passed on once. */
    private var failureReported: IosFfmpegPlayer? = null

    /** Whether [swapStream] is still bringing its copy in; the waiting player is its until then. */
    val isSwapping: Boolean get() = swapJob?.isActive == true

    /** Gives up a swap still waiting on its copy, freeing the waiting player at once. */
    private fun abandonSwap() {
        val job = swapJob ?: return
        swapJob = null
        job.cancel()
        spare.clear()
    }

    /** The rate Automix brought the new track in at, put back to 1 when the blend ends. */
    private var blendRate = 1f

    /** One beat of the blend's grid, for easing the incoming track back to its own tempo; 0 snaps. */
    private var easeBeatMs = 0L
    private var easeJob: Job? = null

    /** A transition doing what a plain crossfade could not is running: the scrubber lights up. */
    private val _mixing = MutableStateFlow(false)
    val mixing: StateFlow<Boolean> = _mixing.asStateFlow()

    /**
     * The heard track's speed, for advanced Automix ramping it up to the next one's tempo over its
     * last beats. An AVPlayer only, and only while it plays: a pause puts it back at 1x.
     */
    fun setHeardRate(rate: Float) {
        if (active.ff != null || active.player.rate == 0f || isBlending) return
        active.player.setRate(rate)
    }

    /**
     * Ends a blend: the new track at full volume, the old one gone. One that ran its course eases
     * the new track back to its own tempo a step a beat; one cut short puts it straight back.
     */
    private fun finishFade() {
        val cutShort = fadeJob != null
        fadeJob?.cancel()
        fadeJob = null
        _mixing.value = false
        active.dsp?.fx?.park()
        easeJob?.cancel()
        easeJob = null
        if (blendRate != 1f) {
            val from = blendRate
            blendRate = 1f
            val deck = active
            if (deck.ff == null && deck.player.rate != 0f) {
                if (cutShort || easeBeatMs <= 0L) {
                    deck.player.setRate(1f)
                } else {
                    val beat = easeBeatMs
                    easeJob = scope.launch {
                        var rate = from
                        while (kotlin.math.abs(rate - 1f) > 1e-4f) {
                            delay(beat)
                            if (deck !== active || deck.player.rate == 0f) return@launch
                            rate = if (rate > 1f) maxOf(1f, rate - EASE_STEP_PER_BEAT) else minOf(1f, rate + EASE_STEP_PER_BEAT)
                            deck.player.setRate(rate)
                        }
                    }
                }
            }
        }
        outgoing?.let { old ->
            outgoing = null
            old.dsp?.fx?.park()
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
        abandonSwap()
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
        val failure = ff.failure
        if (failure != null) {
            position.advancing = false
            _isBuffering.value = false
            // A deck that failed while it was waiting and was handed the track anyway: said once,
            // so the player moves it to another copy instead of sitting silent.
            if (failureReported !== ff) {
                failureReported = ff
                onFailed(failure)
            }
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
    private var watchStartSkipped = 0L
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
            watchStartSkipped = ff.skippedMs
            return
        }
        val wall = now - watchStartWall
        if (wall < WATCH_WINDOW_MS) return
        // Silence that skip silence cut is a jump of the playhead, not running ahead.
        val skipped = ff.skippedMs
        val advanced = pos - watchStartPos - (skipped - watchStartSkipped)
        if (advanced > wall * 3 / 2 + 500) {
            TrackLog.w(TAG, "FFmpeg ran ahead: ${advanced}ms in ${wall}ms: ${ff.diagnostics()}")
            watchedFf = null
            onFailed("ran ahead of real time")
            return
        }
        watchStartWall = now
        watchStartPos = pos
        watchStartSkipped = skipped
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
                    // A FLAC AVPlayer would have to recognise by Content-Type alone — an addon's
                    // `/file?eid=…` — goes to FFmpeg, which reads the stream itself. A `.flac`
                    // path stays on AVPlayer, which plays those.
                    ("flac" in mime && !path.endsWith(".flac")) ||
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
        /** Skip silence's floor, as on the phone: shorter gaps are musical and stay. */
        const val MIN_SILENCE_S = 1.0
        /** How fast a long gap goes by, muted, on AVPlayer. */
        const val GAP_RATE = 8f
        /** How far back a fast-forwarded gap rewinds once sound returns: more than it overshoots. */
        const val GAP_REWIND_S = 1.0
        /** A swap onto a better copy: how long the two copies overlap. */
        const val SWAP_FADE_MS = 300L
        /** How long the better copy may take to start sounding before the swap is given up. */
        const val SWAP_READY_MS = 25_000L
        /** Opened a little ahead of the playhead, so it is buffered where it will be wanted. */
        const val SWAP_LEAD_MS = 500L
        /** Time after re-aligning for the better copy to be playing smoothly again. */
        const val SWAP_SETTLE_MS = 400L
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

/** The phone's TEMPO_STEP_PER_BEAT: how far Automix moves a track's tempo each beat of a ramp or an ease back. */
internal const val EASE_STEP_PER_BEAT = 0.0075f
