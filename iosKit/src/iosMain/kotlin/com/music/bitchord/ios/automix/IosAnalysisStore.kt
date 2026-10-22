package com.music.bitchord.ios.automix

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.MixCandidate
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.math.roundToLong

/**
 * Analyses kept on the phone, so a track is measured once and stays measured: the iOS side of
 * AnalysisStore (jvmSharedMain, java.io), with the same files — one per track, named by a hash of
 * its id, schema [SCHEMA_VERSION], at most [MAX_ENTRIES], curves rounded to milliseconds — in
 * Application Support/BitChord/smart_analysis, kept out of the phone's backup: every one of them
 * can be measured again.
 *
 * [load] and [markMissing] are the main thread's; [save] runs off it and touches no shared state.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosAnalysisStore {
    private val files = NSFileManager.defaultManager

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val directory: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        "$support/BitChord/$DIRECTORY".also { path ->
            files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
            NSURL.fileURLWithPath(path).setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
        }
    }

    /** Ids known to have no file, so a track measured nowhere doesn't hit the disk every tick. */
    private val missing = HashSet<String>()

    /** [trackId]'s stored analysis, or null. Main thread. */
    fun load(trackId: String): TrackAnalysis? {
        if (trackId.isBlank() || trackId in missing) return null
        val path = "$directory/${fileNameFor(trackId)}"
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        if (text == null) {
            missing += trackId
            return null
        }
        return runCatching {
            val stored = json.decodeFromString(Stored.serializer(), text)
            // A wrong beat grid is worse than none: an older schema's numbers are re-earned.
            require(stored.version == SCHEMA_VERSION) { "schema ${stored.version}" }
            stored.toAnalysis(trackId)
        }.onFailure {
            files.removeItemAtPath(path, null)
            missing += trackId
        }.getOrNull()
    }

    /** A track that was just stored is no longer known missing. Main thread. */
    fun markStored(trackId: String) {
        missing -= trackId
    }

    /**
     * Stores [analysis], then keeps the directory under [MAX_ENTRIES]. A result with no usable tempo
     * isn't kept: the reason is usually the bytes, not the track. Any thread.
     */
    fun save(trackId: String, analysis: TrackAnalysis) {
        if (trackId.isBlank() || !analysis.isUsable) return
        val text = json.encodeToString(Stored.serializer(), Stored.of(analysis))
        // Atomically: written aside and moved into place, so a kill mid-write keeps the old entry.
        NSString.create(string = text)
            .writeToFile("$directory/${fileNameFor(trackId)}", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        prune()
    }

    private fun prune() {
        val names = files.contentsOfDirectoryAtPath(directory, null)?.filterIsInstance<String>() ?: return
        if (names.size <= MAX_ENTRIES) return
        names.sortedBy { name ->
            (files.attributesOfItemAtPath("$directory/$name", null)?.get(NSFileModificationDate) as? NSDate)
                ?.timeIntervalSince1970 ?: 0.0
        }.take(names.size - MAX_ENTRIES).forEach { files.removeItemAtPath("$directory/$it", null) }
    }

    /** Hashed rather than used raw: a track id is not guaranteed to be a legal file name. */
    private fun fileNameFor(trackId: String): String = "${trackId.hashCode().toUInt()}_${trackId.length}.json"

    /** The persisted subset, as AnalysisStore's: adding a field to [TrackAnalysis] is not silently a schema change. */
    @Serializable
    private data class Stored(
        val version: Int = SCHEMA_VERSION,
        val duration: Double = 0.0,
        val bpm: Double = 0.0,
        val beatInterval: Double = 0.0,
        val beatConfidence: Double = 0.0,
        val firstBeat: Double = 0.0,
        val downbeats: List<Double> = emptyList(),
        val phraseBoundaries: List<Double> = emptyList(),
        val key: String = "",
        val keyConfidence: Double = 0.0,
        val audibleStartTime: Double? = null,
        val pickupTime: Double? = null,
        val introEndTime: Double = 0.0,
        val outroStartTime: Double = 0.0,
        val contentEndTime: Double = 0.0,
        val mixInTime: Double = 0.0,
        val mixOutTime: Double = 0.0,
        val mixInCandidates: List<StoredCue> = emptyList(),
        val mixOutCandidates: List<StoredCue> = emptyList(),
        val energyCurve: List<StoredEnergy> = emptyList(),
        val lowEnergyCurve: List<StoredEnergy> = emptyList(),
        val vocalActivityMask: List<Double> = emptyList(),
        val vocalProbability: Double = 0.0,
    ) {
        fun toAnalysis(trackId: String) = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = duration,
            bpm = bpm,
            beatInterval = beatInterval,
            beatConfidence = beatConfidence,
            firstBeat = firstBeat,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
            key = key,
            keyConfidence = keyConfidence,
            audibleStartTime = audibleStartTime,
            pickupTime = pickupTime,
            introEndTime = introEndTime,
            outroStartTime = outroStartTime,
            contentEndTime = contentEndTime,
            mixInTime = mixInTime,
            mixOutTime = mixOutTime,
            mixInCandidates = mixInCandidates.map { it.toCue() },
            mixOutCandidates = mixOutCandidates.map { it.toCue() },
            energyCurve = energyCurve.map { it.toSample() },
            lowEnergyCurve = lowEnergyCurve.map { it.toSample() },
            vocalActivityMask = vocalActivityMask,
            vocalProbability = vocalProbability,
        )

        companion object {
            fun of(analysis: TrackAnalysis) = Stored(
                duration = analysis.duration,
                bpm = analysis.bpm,
                beatInterval = analysis.beatInterval,
                beatConfidence = analysis.beatConfidence,
                firstBeat = analysis.firstBeat,
                downbeats = analysis.downbeats.map(::round),
                phraseBoundaries = analysis.phraseBoundaries.map(::round),
                key = analysis.key,
                keyConfidence = analysis.keyConfidence,
                audibleStartTime = analysis.audibleStartTime,
                pickupTime = analysis.pickupTime,
                introEndTime = analysis.introEndTime,
                outroStartTime = analysis.outroStartTime,
                contentEndTime = analysis.contentEndTime,
                mixInTime = analysis.mixInTime,
                mixOutTime = analysis.mixOutTime,
                mixInCandidates = analysis.mixInCandidates.map(StoredCue::of),
                mixOutCandidates = analysis.mixOutCandidates.map(StoredCue::of),
                energyCurve = analysis.energyCurve.map(StoredEnergy::of),
                lowEnergyCurve = analysis.lowEnergyCurve.map(StoredEnergy::of),
                vocalActivityMask = analysis.vocalActivityMask.map(::round),
                vocalProbability = analysis.vocalProbability,
            )
        }
    }

    @Serializable
    private data class StoredCue(val time: Double, val score: Double, val type: String) {
        fun toCue() = MixCandidate(time = time, score = score, type = type)

        companion object {
            fun of(cue: MixCandidate) = StoredCue(round(cue.time), round(cue.score), cue.type)
        }
    }

    @Serializable
    private data class StoredEnergy(val time: Double, val energy: Double) {
        fun toSample() = EnergySample(time = time, energy = energy)

        companion object {
            fun of(sample: EnergySample) = StoredEnergy(round(sample.time), round(sample.energy))
        }
    }

    private companion object {
        /** Android's directory name, under the app's own folder. */
        const val DIRECTORY = "smart_analysis"

        /** AnalysisStore's: bumped whenever a stored number starts being computed differently. */
        const val SCHEMA_VERSION = 1

        const val MAX_ENTRIES = 2_000

        /** Milliseconds is finer than anything downstream distinguishes. */
        fun round(value: Double): Double =
            if (value.isFinite()) (value * 1000.0).roundToLong() / 1000.0 else 0.0
    }
}
