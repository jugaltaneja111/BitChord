package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.wallClockMs
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplicationDidBecomeActiveNotification

/**
 * "Keep playlists updated" (upstream v1.9's `PlaylistSync`): the playlists downloaded whole are
 * checked against their online copies, and songs added since are downloaded.
 *
 * The phone checks twice a day from WorkManager, with the app closed. iOS gives a sideloaded app
 * no dependable equivalent, so here a check runs when the switch is turned on and whenever the
 * app comes to the front with the last whole check more than [CATCH_UP_MS] old: the phone's own
 * catch-up rule, made the whole schedule. Its notifications become a toast, and its "tap to
 * download" for a big batch an alert ([ask]).
 *
 * As on the phone, what is new is worked out fresh on every check and nothing is stored until it
 * is dealt with, and it only ever adds: a track the listener deleted is still named by the
 * release's record, so it doesn't come back, and a track removed online keeps its file.
 */
internal object IosPlaylistSync {
    private const val TAG = "BitChord"
    private const val KEY_LAST_CHECK = "bitchord.playlist_sync.last_complete_check"

    /** How stale the last whole check may be when the app comes to the front. */
    private const val CATCH_UP_MS = 6L * 60 * 60 * 1000

    /** The most one playlist may fetch without being asked; about 1.7 GB of lossless. */
    const val ASK_ABOVE = 50

    /** Between requests for pages and for playlists: none of this is in a hurry. */
    private const val PAUSE_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val running = Mutex()
    private val defaults = NSUserDefaults.standardUserDefaults

    /** A playlist with more new songs than [ASK_ABOVE], waiting on the listener's yes. */
    data class Ask(val browseId: String, val title: String, val count: Int)

    private val _ask = MutableStateFlow<Ask?>(null)
    val ask: StateFlow<Ask?> = _ask.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var wasOn: Boolean? = null
            IosSettings.syncDownloadedPlaylists.flow.collect { on ->
                if (on && wasOn == false) checkNow()
                if (on && wasOn == null && catchUpDue()) checkNow()
                wasOn = on
            }
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { _ ->
            if (IosSettings.syncDownloadedPlaylists.flow.value && catchUpDue()) checkNow()
        }
    }

    /** The listener's answer to [ask]: yes fetches that playlist's batch, whatever its size. */
    fun answer(download: Boolean) {
        val asked = _ask.value ?: return
        _ask.value = null
        if (download) checkNow(approved = asked.browseId)
    }

    private fun checkNow(approved: String? = null) {
        scope.launch { check(approved) }
    }

    private fun catchUpDue(): Boolean = wallClockMs() - defaults.doubleForKey(KEY_LAST_CHECK).toLong() >= CATCH_UP_MS

    /** Downloads may run now: over Wi-Fi, or with "Download over Wi-Fi only" off. */
    private fun downloadsAllowedNow(): Boolean =
        !IosSettings.downloadWifiOnly.flow.value || IosNetwork.metered.value == false

    /**
     * Every eligible playlist in turn. Only a check that read every playlist to its end is stamped
     * as done, so one that failed partway is due again the next time the app comes to the front.
     */
    private suspend fun check(approved: String?) {
        // An approval waits its turn; anything else would only repeat the check already running.
        if (approved != null) running.lock() else if (!running.tryLock()) return
        try {
            var whole = true
            IosDownloads.releases.value.map { it.browseId }.forEach { id ->
                if (!IosSettings.syncDownloadedPlaylists.flow.value || !downloadsAllowedNow()) return
                val release = IosDownloads.releases.value.firstOrNull { it.browseId == id } ?: return@forEach
                val browseId = syncableBrowseId(release.browseId) ?: return@forEach
                if (approved != null && approved != release.browseId) return@forEach
                val listing = readWholePlaylist(
                    first = { YtMusicRepository.browseSongs(browseId) },
                    more = { YtMusicRepository.moreSongs(it) },
                )
                if (!listing.complete) whole = false
                update(release, listing, approved == release.browseId)
                delay(PAUSE_MS)
            }
            if (whole && approved == null) defaults.setDouble(wallClockMs().toDouble(), KEY_LAST_CHECK)
        } finally {
            running.unlock()
        }
    }

    private fun update(release: DownloadedRelease, listing: Listing, approved: Boolean) {
        val fresh = newTracks(listing.songs, release.videoIds)
        if (fresh.isEmpty()) return
        if (fresh.size > ASK_ABOVE && !approved) {
            TrackLog.d(TAG, "playlist sync: ${release.title} has ${fresh.size} new songs; asking first")
            if (_ask.value == null) _ask.value = Ask(release.browseId, release.title, fresh.size)
            return
        }
        // Read again: the listener may have deleted this download meanwhile, and writing the
        // record back would bring it back from the dead.
        val current = IosDownloads.releases.value.firstOrNull { it.browseId == release.browseId } ?: return
        val cover = listing.cover ?: current.thumbnailUrl
        val handled = fresh.mapTo(HashSet()) { it.videoId }
        val order = listingToRecord(listing.songs, current.videoIds, handled).map { song ->
            if (song.thumbnailUrl.isNullOrBlank() && cover != null) song.copy(thumbnailUrl = cover) else song
        }
        TrackLog.d(TAG, "playlist sync: ${release.title}: ${fresh.size} new songs")
        // Only the new ones are fetched: a track the listener deleted from this download is still
        // named by it, and stays deleted.
        IosDownloads.downloadRelease(current, order, fetch = fresh, announce = false)
        IosPlayerHost.showMessage(tr("Added to {0}: {1}", current.title, fresh.size))
    }

    /** @property complete whether this reached the end of the playlist. */
    internal class Listing(val songs: List<Song>, val complete: Boolean, val cover: String? = null)

    /**
     * Every track in a playlist, however many pages it runs to, and only the rows a page lists as
     * its own, so the suggestions YouTube offers to round a playlist out are never taken for it.
     */
    internal suspend fun readWholePlaylist(
        first: suspend () -> Result<YtMusicRepository.SongPage>,
        more: suspend (String) -> Result<YtMusicRepository.SongPage>,
    ): Listing {
        val opening = first().getOrNull() ?: return Listing(emptyList(), complete = false)
        val cover = opening.header?.thumbnailUrl
        val songs = LinkedHashMap<String, Song>()
        opening.songs.forEach { if (it.videoId !in songs) songs[it.videoId] = it }
        var next = opening.continuation
        while (next != null) {
            delay(PAUSE_MS)
            val page = more(next).getOrNull() ?: return Listing(songs.values.toList(), complete = false, cover)
            val before = songs.size
            page.songs.forEach { if (it.videoId !in songs) songs[it.videoId] = it }
            // A page with nothing new is a feed that has looped back on itself.
            if (songs.size == before) break
            next = page.continuation
        }
        return Listing(songs.values.toList(), complete = true, cover)
    }

    /**
     * What a check looks at, under the id YouTube answers to: a playlist is filed under whichever
     * id it was downloaded from, with or without its `VL`. Null for an album, and for playlists
     * with no YouTube copy to compare against (Spotify's own, and those kept on this iPhone).
     */
    internal fun syncableBrowseId(id: String): String? = when {
        ':' in id || id.startsWith("MP") || IosLocalPlaylists.isLocal(id) -> null
        id.startsWith("VL") -> id
        else -> "VL$id"
    }

    /** The tracks in [listing] that the release has never named. */
    internal fun newTracks(listing: List<Song>, known: List<String>): List<Song> {
        val names = known.toHashSet()
        return listing.filter { it.videoId !in names }
    }

    /** [listing] without whatever is still to be dealt with, in the playlist's own order. */
    internal fun listingToRecord(listing: List<Song>, known: List<String>, handled: Set<String>): List<Song> {
        val names = known.toHashSet()
        return listing.filter { it.videoId in names || it.videoId in handled }
    }
}
