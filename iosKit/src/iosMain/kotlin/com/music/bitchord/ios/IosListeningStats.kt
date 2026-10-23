package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.wallClockMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * What this phone has listened to, kept on this phone: the Android app's ListeningStats, with
 * the same files in the same format, so a backup moves between the two.
 *
 * Aggregates rather than an event log — a track's total is a counter that goes up while it plays,
 * and a Replay is a merge of a handful of finished sums. One JSON file per calendar month in the
 * device's own time zone, so "this year" and "all time" are the same operation on more files,
 * and only the month being written is held in memory. Nothing leaves the phone.
 *
 * Everything here runs on the main thread except the merge, which reads files and works on
 * copies: the writer is one sampler ticking every few seconds, and a month is a few dozen
 * kilobytes.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal object IosListeningStats {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val files = NSFileManager.defaultManager

    private val directory: String by lazy {
        appDataPath("listening").also {
            files.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    /** The month being written to, held open between samples. */
    private var open: OpenBucket? = null

    /** Set when [open] has changes not yet on disk. */
    private var dirty = false

    /** Bumped by every write, so [summary] can tell whether the answer it has is still the answer. */
    private var version = 0L

    private var cached: Cached? = null

    private class Cached(
        val period: ReplayPeriod,
        val version: Long,
        val facts: Int,
        val day: Day,
        val summary: ReplaySummary,
    )

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Adds [playedMs] of [song] to the current month. [countsAsPlay] is a whole listen, counted
     * once by [IosListeningRecorder]; the minutes are added on every sample.
     */
    fun record(song: Song, playedMs: Long, countsAsPlay: Boolean) {
        if (playedMs <= 0 && !countsAsPlay) return
        if (song.videoId.isBlank()) return
        val now = wallClockMs()
        val at = localMoment(now / 1000.0)
        val dayKey = at.day.toString()
        val bucket = bucketFor(at.day.monthKey)
        val track = bucket.tracks.getOrPut(song.videoId) {
            TrackEntry(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                albumId = song.albumId,
                artistId = song.artistId,
                art = song.thumbnailUrl?.takeUnless { it.startsWith("/") },
            )
        }
        track.ms += playedMs
        track.last = now
        // Filled in as it becomes known: a track queued from search reaches the player with no
        // album, and the lookup that finds one lands while it is already playing.
        if (track.album == null) track.album = song.albumName
        if (track.albumId == null) track.albumId = song.albumId
        if (track.artistId == null) track.artistId = song.artistId
        if (track.art == null) track.art = song.thumbnailUrl?.takeUnless { it.startsWith("/") }
        if (countsAsPlay) track.plays++
        bucket.trackDays.add(dayKey, playedMs, countsAsPlay)
        track.days.add(dayKey, playedMs, countsAsPlay)

        // The lead artist, not the credit as a string: "A & B" is not a third artist.
        primaryArtist(song.artist)?.let { name ->
            IosArtistFacts.noticed(name)
            val artist = bucket.artists.getOrPut(name.lowercase()) {
                NameEntry(name = name, art = song.thumbnailUrl?.takeUnless { it.startsWith("/") })
            }
            artist.ms += playedMs
            if (countsAsPlay) artist.plays++
            if (artist.id == null) artist.id = song.artistId
            bucket.artistDays.add(dayKey, playedMs, countsAsPlay)
            artist.days.add(dayKey, playedMs, countsAsPlay)
        }

        song.albumName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            // Keyed on album and artist: "Greatest Hits" is not one release.
            val album = bucket.albums.getOrPut(albumKey(name, song.artist)) {
                NameEntry(name = name, sub = song.artist, art = song.thumbnailUrl?.takeUnless { it.startsWith("/") })
            }
            album.ms += playedMs
            if (countsAsPlay) album.plays++
            if (album.id == null) album.id = song.albumId
            bucket.albumDays.add(dayKey, playedMs, countsAsPlay)
            album.days.add(dayKey, playedMs, countsAsPlay)
        }

        bucket.hours[at.hour] += playedMs
        val day = at.day.dayOfMonth
        bucket.days[day] = (bucket.days[day] ?: 0L) + playedMs
        dirty = true
    }

    private fun MutableMap<String, DayStats>.add(key: String, ms: Long, play: Boolean) {
        getOrPut(key) { DayStats() }.also {
            it.ms += ms
            if (play) it.plays++
        }
    }

    /** Writes the open month out, if anything has changed. On a cadence, not every sample. */
    fun flush() {
        if (!dirty) return
        val bucket = open ?: return
        dirty = false
        write(bucket.key, bucket.snapshot())
    }

    /** Written aside and moved into place, so a kill mid-write leaves the previous month. */
    private fun write(key: String, snapshot: StoredBucket) {
        runCatching {
            val text = json.encodeToString(StoredBucket.serializer(), snapshot)
            NSString.create(string = text).writeToFile("$directory/$key.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
            version++
        }.onFailure { TrackLog.w(TAG, "Could not write listening bucket $key: ${it.message}") }
    }

    /** The bucket for [month], loading or creating it and retiring the last one. */
    private fun bucketFor(month: MonthKey): OpenBucket {
        val key = month.toString()
        open?.takeIf { it.key == key }?.let { return it }
        // The month rolled over mid-session: whatever the last one held reaches disk first.
        open?.let { previous -> write(previous.key, previous.snapshot()) }
        val loaded = read(key) ?: StoredBucket(month = key)
        return OpenBucket.of(key, loaded).also {
            open = it
            prune()
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The Replay for [period], merged off disk, the open month flushed first. */
    suspend fun summary(period: ReplayPeriod): ReplaySummary {
        flush()
        val today = Day.today()
        val facts = IosArtistFacts.revision.value
        cached?.takeIf {
            it.period == period && it.version == version && it.facts == facts && it.day == today
        }?.let { return it.summary }
        val asked = version
        val summary = withContext(Dispatchers.Default) {
            val merged = MergedBucket()
            val range = period.range(today)
            if (period.needsDayFilter() && range != null) {
                // A week may span two months: both are read, and filtered by day.
                months().filter { month -> !(month.lastDay() < range.first || month.firstDay() > range.second) }
                    .forEach { month -> read(month.toString())?.let { bucket -> merged.add(bucket, range) } }
            } else {
                months().filter { period.covers(it, today) }
                    .forEach { month -> read(month.toString())?.let(merged::add) }
            }
            merged.toSummary(period, today)
        }
        cached = Cached(period, asked, facts, today, summary)
        return summary
    }

    /** Every month held, the open one flushed first: what a backup carries. */
    fun exportAll(): List<StoredBucket> {
        flush()
        return months().mapNotNull { read(it.toString()) }
    }

    /**
     * Replaces everything held with [buckets], as the phone's import does: a restore, never a merge,
     * since two copies of a month added together would double every number with no way to tell.
     */
    fun importAll(buckets: List<StoredBucket>) {
        open = null
        dirty = false
        files.contentsOfDirectoryAtPath(directory, null).orEmpty().forEach { files.removeItemAtPath("$directory/$it", null) }
        buckets.forEach { bucket ->
            val key = MonthKey.parse(bucket.month)?.toString() ?: return@forEach
            write(key, bucket)
        }
        version++
        cached = null
    }

    /** Every month with a file, oldest first. */
    fun months(): List<MonthKey> =
        files.contentsOfDirectoryAtPath(directory, null).orEmpty()
            .mapNotNull { (it as String).removeSuffix(".json").takeIf { name -> name != it }?.let(MonthKey::parse) }
            .sorted()

    private fun read(key: String): StoredBucket? {
        val text = NSString.stringWithContentsOfFile("$directory/$key.json", NSUTF8StringEncoding, null) ?: return null
        return runCatching { json.decodeFromString(StoredBucket.serializer(), text) }
            .onFailure { TrackLog.w(TAG, "Discarding unreadable listening bucket $key") }
            .getOrNull()
    }

    // ── Housekeeping ────────────────────────────────────────────────────────

    /** Keeps the folder to [KEEP_MONTHS], and the open bucket to its caps, by time played. */
    private fun prune() {
        val existing = months()
        if (existing.size > KEEP_MONTHS) {
            existing.take(existing.size - KEEP_MONTHS).forEach { files.removeItemAtPath("$directory/$it.json", null) }
        }
        val bucket = open ?: return
        bucket.tracks.trimTo(MAX_TRACKS) { it.ms }
        bucket.artists.trimTo(MAX_NAMES) { it.ms }
        bucket.albums.trimTo(MAX_NAMES) { it.ms }
    }

    /** Anything recomputed onto the same key is added together rather than overwritten. */
    private inline fun <T : Any> List<T>.mergedBy(
        into: LinkedHashMap<String, T>,
        key: (T) -> String,
    ): LinkedHashMap<String, T> {
        forEach { entry ->
            val k = key(entry)
            val existing = into[k]
            if (existing == null) {
                into[k] = entry
            } else {
                @Suppress("UNCHECKED_CAST")
                (existing as NameEntry).absorb(entry as NameEntry)
            }
        }
        return into
    }

    private inline fun <K, V> MutableMap<K, V>.trimTo(limit: Int, crossinline weight: (V) -> Long) {
        if (size <= limit) return
        entries.sortedBy { weight(it.value) }
            .take(size - limit)
            .map { it.key }
            .forEach { remove(it) }
    }

    private fun <K, V> MutableMap<K, V>.merge(key: K, value: V, combine: (V, V) -> V) {
        val existing = get(key)
        put(key, if (existing == null) value else combine(existing, value))
    }

    // ── Shapes ──────────────────────────────────────────────────────────────

    /** The open month, in mutable form. */
    private class OpenBucket(
        val key: String,
        val tracks: MutableMap<String, TrackEntry>,
        val artists: MutableMap<String, NameEntry>,
        val albums: MutableMap<String, NameEntry>,
        val hours: LongArray,
        val days: MutableMap<Int, Long>,
        val trackDays: MutableMap<String, DayStats> = mutableMapOf(),
        val artistDays: MutableMap<String, DayStats> = mutableMapOf(),
        val albumDays: MutableMap<String, DayStats> = mutableMapOf(),
    ) {
        fun snapshot() = StoredBucket(
            month = key,
            tracks = tracks.values.map { it.copy(days = it.days.toMutableMap()) },
            artists = artists.map { (key, entry) -> entry.copy(key = key, days = entry.days.toMutableMap()) },
            albums = albums.map { (key, entry) -> entry.copy(key = key, days = entry.days.toMutableMap()) },
            hours = hours.toList(),
            days = days.toMap(),
            trackDays = trackDays.mapValues { it.value.copy() },
            artistDays = artistDays.mapValues { it.value.copy() },
            albumDays = albumDays.mapValues { it.value.copy() },
        )

        companion object {
            fun of(key: String, stored: StoredBucket) = OpenBucket(
                key = key,
                tracks = stored.tracks.associateByTo(LinkedHashMap()) { it.id },
                // Re-keyed on the lead artist, so entries filed under a whole credit fold in.
                artists = stored.artists
                    .map { it.copy(name = primaryArtist(it.name) ?: it.name) }
                    .mergedBy(LinkedHashMap()) { it.name.lowercase() },
                albums = stored.albums.mergedBy(LinkedHashMap()) { albumKey(it.name, it.sub.orEmpty()) },
                hours = LongArray(24) { stored.hours.getOrElse(it) { 0L } },
                days = stored.days.toMutableMap(),
                trackDays = stored.trackDays?.toMutableMap() ?: mutableMapOf(),
                artistDays = stored.artistDays?.toMutableMap() ?: mutableMapOf(),
                albumDays = stored.albumDays?.toMutableMap() ?: mutableMapOf(),
            )
        }
    }

    /** Several months added together, on the way to a [ReplaySummary]. */
    private class MergedBucket {
        val tracks = HashMap<String, TrackEntry>()
        val artists = HashMap<String, NameEntry>()
        val albums = HashMap<String, NameEntry>()
        val hours = LongArray(24)
        val days = HashMap<String, Long>()
        /** Per-day sub-buckets for tracks. Key: "YYYY-MM-DD". */
        val trackDays = HashMap<String, DayStats>()
        /** Per-day sub-buckets for artists. Key: "YYYY-MM-DD". */
        val artistDays = HashMap<String, DayStats>()
        /** Per-day sub-buckets for albums. Key: "YYYY-MM-DD". */
        val albumDays = HashMap<String, DayStats>()
        var earliest: String? = null

        fun add(bucket: StoredBucket) {
            bucket.tracks.forEach { entry ->
                tracks.merge(entry.id, entry.copy()) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.artists.forEach { entry ->
                val lead = entry.copy(name = primaryArtist(entry.name) ?: entry.name)
                artists.merge(lead.name.lowercase(), lead) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.albums.forEach { entry ->
                val key = albumKey(entry.name, entry.sub.orEmpty())
                albums.merge(key, entry.copy()) { a, b -> a.also { it.absorb(b) } }
            }
            repeat(24) { hours[it] += bucket.hours.getOrElse(it) { 0L } }
            bucket.days.forEach { (day, ms) ->
                val date = "${bucket.month}-${day.toString().padStart(2, '0')}"
                days[date] = (days[date] ?: 0L) + ms
            }
            if (earliest == null || bucket.month < earliest!!) earliest = bucket.month
        }

        /** Add all day-level sub-buckets for weekly queries. */
        fun addDaySubs(bucket: StoredBucket) {
            bucket.trackDays?.forEach { (dateStr, stats) ->
                trackDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
            }
            bucket.artistDays?.forEach { (dateStr, stats) ->
                artistDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
            }
            bucket.albumDays?.forEach { (dateStr, stats) ->
                albumDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
            }
        }

        /** Add only the days that fall within [range], keyed as "YYYY-MM-DD". */
        fun add(bucket: StoredBucket, range: Pair<Day, Day>) {
            // Merge entity-level data (tracks/artists/albums) so weekRange() can read them.
            bucket.tracks.forEach { entry ->
                tracks.merge(entry.id, entry.copy()) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.artists.forEach { entry ->
                val lead = entry.copy(name = primaryArtist(entry.name) ?: entry.name)
                artists.merge(lead.name.lowercase(), lead) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.albums.forEach { entry ->
                val key = albumKey(entry.name, entry.sub.orEmpty())
                albums.merge(key, entry.copy()) { a, b -> a.also { it.absorb(b) } }
            }
            // Merge day-level sub-buckets for the flat days map and cross-month totals.
            addDaySubs(bucket)
            bucket.days.forEach { (day, ms) ->
                val candidate = "${bucket.month}-${day.toString().padStart(2, '0')}"
                val date = Day.parse(candidate) ?: return@forEach
                if (date >= range.first && date <= range.second) {
                    days[candidate] = (days[candidate] ?: 0L) + ms
                }
            }
        }

        fun toSummary(period: ReplayPeriod, today: Day): ReplaySummary {
            // For weekly mode, compute per-entity totals from day-level sub-buckets.
            val weekRange = if (period.needsDayFilter()) period.range(today) else null

            val rankedSongs = if (weekRange != null) {
                tracks.values
                    .mapNotNull { entry ->
                        val (ms, plays) = entry.weekRange(weekRange)
                        if (ms > 0) entry to (ms to plays) else null
                    }
                    .sortedWith(compareByDescending<Pair<TrackEntry, Pair<Long, Int>>> { it.second.first }
                        .thenByDescending { it.second.second })
                    .map { (entry, msPlays) ->
                        val (ms, plays) = msPlays
                        RankedSong(
                            song = Song(
                                videoId = entry.id,
                                title = entry.title,
                                artist = entry.artist,
                                thumbnailUrl = entry.art,
                                artistId = entry.artistId,
                                albumId = entry.albumId,
                                albumName = entry.album,
                            ),
                            ms = ms,
                            plays = plays,
                        )
                    }
            } else {
                tracks.values
                    .sortedWith(compareByDescending<TrackEntry> { it.ms }.thenByDescending { it.plays })
                    .map {
                        RankedSong(
                            song = Song(
                                videoId = it.id,
                                title = it.title,
                                artist = it.artist,
                                thumbnailUrl = it.art,
                                artistId = it.artistId,
                                albumId = it.albumId,
                                albumName = it.album,
                            ),
                            ms = it.ms,
                            plays = it.plays,
                        )
                    }
            }

            // The artist's own picture and page where one has been found, and
            // the track's sleeve where one hasn't -- see [ArtistFacts]. Resolved
            // here rather than stored in the bucket because it is a fact about
            // the artist, not about the evening they were played on: written
            // into every month it would freeze at whatever was known the first
            // time they came up, and be missing from every month before that.
            val rankedArtists = if (weekRange != null) {
                artists.values
                    .mapNotNull { entry ->
                        val (ms, plays) = entry.weekRange(weekRange)
                        if (ms > 0) entry to (ms to plays) else null
                    }
                    .sortedWith(compareByDescending<Pair<NameEntry, Pair<Long, Int>>> { it.second.first }
                        .thenByDescending { it.second.second })
                    .map { (entry, msPlays) ->
                        val (ms, plays) = msPlays
                        RankedEntry(
                            title = entry.name,
                            subtitle = entry.sub,
                            artworkUrl = IosArtistFacts.imageFor(entry.name) ?: entry.art,
                            browseId = entry.id ?: IosArtistFacts.browseIdFor(entry.name),
                            ms = ms,
                            plays = plays,
                        )
                    }
            } else {
                artists.values
                    .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                    .map {
                        RankedEntry(
                            title = it.name,
                            subtitle = it.sub,
                            artworkUrl = IosArtistFacts.imageFor(it.name) ?: it.art,
                            browseId = it.id ?: IosArtistFacts.browseIdFor(it.name),
                            ms = it.ms,
                            plays = it.plays,
                        )
                    }
            }

            val rankedAlbums = if (weekRange != null) {
                albums.values
                    .mapNotNull { entry ->
                        val (ms, plays) = entry.weekRange(weekRange)
                        if (ms > 0) entry to (ms to plays) else null
                    }
                    .sortedWith(compareByDescending<Pair<NameEntry, Pair<Long, Int>>> { it.second.first }
                        .thenByDescending { it.second.second })
                    .map { (entry, msPlays) ->
                        val (ms, plays) = msPlays
                        RankedEntry(entry.name, entry.sub, entry.art, entry.id, ms, plays)
                    }
            } else {
                albums.values
                    .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                    .map { RankedEntry(it.name, it.sub, it.art, it.id, it.ms, it.plays) }
            }

            // Genres are derived rather than stored — see [ArtistFacts]. An
            // artist with no tag yet simply doesn't vote, which is why this can
            // be an empty list on a page whose other charts are full.
            val genreMs = LinkedHashMap<String, Long>()
            val genrePlays = LinkedHashMap<String, Int>()
            val genreArt = LinkedHashMap<String, String?>()
            rankedArtists.forEach { artist ->
                IosArtistFacts.genresFor(artist.title).forEach { genre ->
                    genreMs[genre] = (genreMs[genre] ?: 0L) + artist.ms
                    genrePlays[genre] = (genrePlays[genre] ?: 0) + artist.plays
                    if (genreArt[genre] == null) genreArt[genre] = artist.artworkUrl
                }
            }
            // Anything still missing a picture is asked about now, so opening
            // the page is a second way for these to fill in — the first being
            // playing the music, which is no help to someone who has just
            // installed a build that started collecting them.
            rankedArtists.take(ARTISTS_TO_RESOLVE)
                .filter { it.artworkUrl == null || it.browseId == null }
                .forEach { IosArtistFacts.noticed(it.title) }

            val rankedGenres = genreMs.entries
                .sortedByDescending { it.value }
                .map {
                    RankedEntry(
                        title = it.key,
                        subtitle = null,
                        artworkUrl = genreArt[it.key],
                        browseId = null,
                        ms = it.value,
                        plays = genrePlays[it.key] ?: 0,
                    )
                }

            val busiest = days.entries.maxByOrNull { it.value }
            return ReplaySummary(
                period = period,
                label = period.label(today),
                totalMs = rankedSongs.sumOf { it.ms },
                totalPlays = rankedSongs.sumOf { it.plays },
                songs = rankedSongs,
                artists = rankedArtists,
                albums = rankedAlbums,
                genres = rankedGenres,
                hourOfDay = hours.toList(),
                busiestDay = busiest?.key,
                busiestDayMs = busiest?.value ?: 0L,
                distinctSongs = rankedSongs.size,
                distinctArtists = rankedArtists.size,
                distinctAlbums = rankedAlbums.size,
                since = earliest,
            )
        }
    }

    /** How an album is filed: name and artist, lower case, joined on a character neither has. */
    private fun albumKey(name: String, artist: String): String =
        name.lowercase() + Char(UNIT_SEPARATOR).toString() + artist.lowercase()

    private const val UNIT_SEPARATOR = 31

    /**
     * The first person named on a credit. Split on punctuation and feature markers, which is how
     * catalogues join two artists; " and " is left alone, being as often part of a band's name.
     */
    fun primaryArtist(credit: String): String? {
        lastCredit?.let { (raw, name) -> if (raw == credit) return name }
        val name = credit.split(CREDIT_SEPARATORS)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        lastCredit = credit to name
        return name
    }

    private var lastCredit: Pair<String, String?>? = null

    private val CREDIT_SEPARATORS = Regex(
        """\s*,\s*|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+""",
        RegexOption.IGNORE_CASE,
    )

    private const val TAG = "IosListening"

    /** How far down the artist chart a page opening sends lookups for. */
    private const val ARTISTS_TO_RESOLVE = 15

    /** Three years, longer than "all time" means to most people. */
    private const val KEEP_MONTHS = 36

    private const val MAX_TRACKS = 600
    private const val MAX_NAMES = 400
}

/** Daily-level listening for a single track or artist. */
@Serializable
internal data class DayStats(
    var ms: Long = 0L,
    var plays: Int = 0,
)

/** One track's totals inside a bucket. */
@Serializable
internal data class TrackEntry(
    val id: String,
    val title: String = "",
    val artist: String = "",
    var album: String? = null,
    var albumId: String? = null,
    var artistId: String? = null,
    var art: String? = null,
    var ms: Long = 0L,
    var plays: Int = 0,
    var last: Long = 0L,
    /** Per-day breakdown for weekly queries. Key: "YYYY-MM-DD". */
    var days: MutableMap<String, DayStats> = mutableMapOf(),
) {
    fun absorb(other: TrackEntry) {
        ms += other.ms
        plays += other.plays
        last = maxOf(last, other.last)
        if (album == null) album = other.album
        if (albumId == null) albumId = other.albumId
        if (artistId == null) artistId = other.artistId
        if (art == null) art = other.art
        // Merge per-day sub-buckets.
        if (other.days.isNotEmpty()) {
            other.days.forEach { (dateKey, stats) ->
                days.getOrPut(dateKey) { DayStats() }.also { existing ->
                    existing.ms += stats.ms
                    existing.plays += stats.plays
                }
            }
        }
    }

    /** Sum ms/plays for days that fall within [range]. Falls back to full totals if .days is empty. */
    fun weekRange(range: Pair<Day, Day>): Pair<Long, Int> {
        var totalMs = 0L
        var totalPlays = 0
        if (days.isNotEmpty()) {
            for ((dateStr, stats) in days) {
                val date = Day.parse(dateStr) ?: continue
                if (date >= range.first && date <= range.second) {
                    totalMs += stats.ms
                    totalPlays += stats.plays
                }
            }
        } else {
            // Legacy data: .days was never populated — use overall totals.
            return ms to plays
        }
        return totalMs to totalPlays
    }
}

/**
 * One artist's or album's totals.
 *
 * [key] is the map key it was stored under. Written for a reader's benefit and
 * for anything downstream of an export; it is deliberately *not* read back.
 *
 * Reading it was the original design, on the reasoning that a stored key cannot
 * be spelt differently by a later build. What that actually buys is the
 * opposite: an entry keyed by an older build keeps its old key forever, so the
 * same artist ends up in two rows the moment the spelling changes and there is
 * no way back. Recomputing on load means a change heals itself — see
 * [OpenBucket.of] and the merge it does on the way in.
 */
@Serializable
internal data class NameEntry(
    val name: String = "",
    val sub: String? = null,
    var art: String? = null,
    var id: String? = null,
    var ms: Long = 0L,
    var plays: Int = 0,
    val key: String? = null,
    /** Per-day breakdown for weekly queries. Key: "YYYY-MM-DD". */
    var days: MutableMap<String, DayStats> = mutableMapOf(),
) {
    fun absorb(other: NameEntry) {
        ms += other.ms
        plays += other.plays
        if (art == null) art = other.art
        if (id == null) id = other.id
        // Merge per-day sub-buckets.
        if (other.days.isNotEmpty()) {
            other.days.forEach { (dateKey, stats) ->
                days.getOrPut(dateKey) { DayStats() }.also { existing ->
                    existing.ms += stats.ms
                    existing.plays += stats.plays
                }
            }
        }
    }

    /** Sum ms/plays for days that fall within [range]. Falls back to full totals if .days is empty. */
    fun weekRange(range: Pair<Day, Day>): Pair<Long, Int> {
        var totalMs = 0L
        var totalPlays = 0
        if (days.isNotEmpty()) {
            for ((dateStr, stats) in days) {
                val date = Day.parse(dateStr) ?: continue
                if (date >= range.first && date <= range.second) {
                    totalMs += stats.ms
                    totalPlays += stats.plays
                }
            }
        } else {
            // Legacy data: .days was never populated — use overall totals.
            return ms to plays
        }
        return totalMs to totalPlays
    }
}

/** One calendar month on disk. */
@Serializable
internal data class StoredBucket(
    val version: Int = 1,
    val month: String,
    val tracks: List<TrackEntry> = emptyList(),
    val artists: List<NameEntry> = emptyList(),
    val albums: List<NameEntry> = emptyList(),
    /** Milliseconds played per hour of the day, 0..23. */
    val hours: List<Long> = List(24) { 0L },
    /** Milliseconds played per day of the month. */
    val days: Map<Int, Long> = emptyMap(),
    /** Per-day sub-buckets for tracks. Key: "YYYY-MM-DD". */
    val trackDays: Map<String, DayStats>? = null,
    /** Per-day sub-buckets for artists. Key: "YYYY-MM-DD". */
    val artistDays: Map<String, DayStats>? = null,
    /** Per-day sub-buckets for albums. Key: "YYYY-MM-DD". */
    val albumDays: Map<String, DayStats>? = null,
)

/** A row on one of the four charts. */
internal data class RankedEntry(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val browseId: String?,
    val ms: Long,
    val plays: Int,
)

/** A song row, which keeps the whole [Song] so tapping it can play it. */
internal data class RankedSong(val song: Song, val ms: Long, val plays: Int)

/** How far back a Replay reaches. */
internal enum class ReplayPeriod(val chip: String) {
    THIS_WEEK(tr("This week")),
    THIS_MONTH(tr("This month")),
    THIS_YEAR(tr("This year")),
    ALL_TIME(tr("All time")),
    ;

    /** The inclusive date range this period covers. Null for ALL_TIME. */
    fun range(today: Day): Pair<Day, Day>? = when (this) {
        THIS_WEEK -> today.startOfWeek() to today
        THIS_MONTH -> today.monthKey.firstDay() to today.monthKey.lastDay()
        THIS_YEAR -> Day(today.year, 1, 1) to Day(today.year, 12, 31)
        ALL_TIME -> null
    }

    fun covers(month: MonthKey, today: Day): Boolean = when (this) {
        THIS_WEEK -> !(today < month.firstDay() || today.startOfWeek() > month.lastDay())
        THIS_MONTH -> month == today.monthKey
        THIS_YEAR -> month.year == today.year
        ALL_TIME -> true
    }

    /** Whether this period needs day-level filtering (week spans potentially multiple months). */
    fun needsDayFilter(): Boolean = this == THIS_WEEK

    fun label(today: Day): String = when (this) {
        THIS_WEEK -> tr("This week")
        THIS_MONTH -> "${today.monthKey.name} ${today.year}"
        THIS_YEAR -> today.year.toString()
        ALL_TIME -> tr("All time")
    }
}

/**
 * Everything the Replay page and the stories draw, worked out once.
 *
 * Deliberately a plain value with the charts already sorted: the stories flip
 * between eight views of the same numbers, and recomputing a ranking per page
 * would put a sort on the swipe.
 */
internal data class ReplaySummary(
    val period: ReplayPeriod,
    val label: String,
    val totalMs: Long,
    val totalPlays: Int,
    val songs: List<RankedSong>,
    val artists: List<RankedEntry>,
    val albums: List<RankedEntry>,
    val genres: List<RankedEntry>,
    val hourOfDay: List<Long>,
    /** `YYYY-MM-DD` of the day with the most listening, or null. */
    val busiestDay: String?,
    val busiestDayMs: Long,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val distinctAlbums: Int,
    /** The earliest month with anything in it, `YYYY-MM`. */
    val since: String?,
) {
    val minutes: Long get() = totalMs / 60_000
    val hours: Long get() = totalMs / 3_600_000

    /**
     * Whether there is anything at all to draw.
     *
     * One track is enough. There was a minimum here — five minutes, on the
     * reasoning that a chart of two songs is not a chart — and it was wrong in
     * the only way that matters: someone who has just played a couple of songs
     * and gone looking for the page they heard about is told they have not
     * listened to anything, which is both untrue and indistinguishable from the
     * feature being broken. A small Replay is a small Replay, and it grows.
     */
    val isEmpty: Boolean get() = songs.isEmpty()

    /** The hour of the day with the most listening, or null if nothing was. */
    val peakHour: Int? get() = hourOfDay.withIndex()
        .filter { it.value > 0 }
        .maxByOrNull { it.value }
        ?.index
}
