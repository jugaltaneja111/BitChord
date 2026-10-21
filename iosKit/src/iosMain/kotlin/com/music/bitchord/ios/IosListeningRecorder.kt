package com.music.bitchord.ios

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.wallClockMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Turns playback into [IosListeningStats] entries, as the Android app's ListeningRecorder does:
 * sampled every few seconds while something plays, the time since the last sample added to the
 * track. A play is a whole listen, counted once a track has had half its length — at least
 * [PLAY_FLOOR_MS], at most [PLAY_CEILING_MS] — the scrobbler's own rule.
 *
 * A new track anchors the clock and adds nothing yet, and a gap longer than [MAX_STEP_MS] (the
 * app suspended, a stall) is capped, so time is undercounted rather than invented.
 */
internal object IosListeningRecorder {
    private var currentId: String? = null
    private var lastSampleAt = 0L
    private var playedThisTrack = 0L
    private var playCounted = false
    private var samplesSinceFlush = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val extras = HashMap<String, Song>()
    private val asked = HashSet<String>()

    fun onSample(song: Song, durationMs: Long) {
        val now = wallClockMs()
        if (song.videoId != currentId) {
            currentId = song.videoId
            lastSampleAt = now
            playedThisTrack = 0L
            playCounted = false
            return
        }
        val step = (now - lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        lastSampleAt = now
        if (step <= 0L) return
        playedThisTrack += step

        val length = durationMs.takeIf { it > 0 } ?: song.durationMillis()
        val threshold = if (length > 0) min(length / 2, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS) else PLAY_FLOOR_MS
        val counts = !playCounted && playedThisTrack >= threshold
        if (counts) playCounted = true

        IosListeningStats.record(enriched(song), step, counts)
        if (++samplesSinceFlush >= FLUSH_EVERY) {
            samplesSinceFlush = 0
            IosListeningStats.flush()
        }
    }

    /** Paused or stopped: the next sample starts the clock again rather than counting the gap. */
    fun onStopped() {
        currentId = null
        playedThisTrack = 0L
        playCounted = false
        samplesSinceFlush = 0
        IosListeningStats.flush()
    }

    /** The album and artist ids the queue didn't carry, asked once per track. */
    private fun enriched(song: Song): Song {
        extras[song.videoId]?.let { extra ->
            return song.copy(
                artistId = song.artistId ?: extra.artistId,
                albumId = song.albumId ?: extra.albumId,
                albumName = song.albumName ?: extra.albumName,
            )
        }
        if (song.albumName != null && song.artistId != null) return song
        if (song.videoId.length != YOUTUBE_ID_LENGTH) return song
        if (asked.add(song.videoId)) {
            scope.launch {
                YtMusicRepository.trackLinks(song.videoId).getOrNull()?.let { extras[song.videoId] = it }
            }
        }
        return song
    }

    private const val MAX_STEP_MS = 8_000L
    private const val PLAY_FLOOR_MS = 30_000L
    private const val PLAY_CEILING_MS = 4 * 60 * 1000L
    private const val FLUSH_EVERY = 6
    private const val YOUTUBE_ID_LENGTH = 11

    /** How often playback is sampled. */
    const val SAMPLE_MS = 5_000L
}
