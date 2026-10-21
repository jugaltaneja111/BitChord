package com.music.bitchord.ios.canvas

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * The iOS app's way in to Canvas — the looping video behind a track's or a
 * release's cover art. A thin front on [CanvasRepository], which holds the
 * sources, their order and the cache.
 *
 * Everything here answers null rather than throwing: the still art is always
 * underneath, so a failed lookup should look exactly like a track that has no
 * canvas. The one exception is cancellation, which is passed on so a caller
 * that has moved to the next track stops paying for this one.
 */
internal object IosCanvas {

    private const val TAG = "IosCanvas"

    /**
     * Whether Spotify's own Canvas is asked for first rather than last. Set at
     * launch to read the integration setting; until then the default order
     * stands. Read on every lookup, so a toggle takes effect on the next one.
     */
    var prioritizeSpotify: () -> Boolean = { false }

    /** The canvas for [song], or null when there isn't one. */
    suspend fun canvasFor(song: Song): CanvasArtwork? = guarded("canvasFor") {
        CanvasRepository.canvasFor(song)
    }

    /** A canvas already worked out for [song], without going near the network. */
    fun cached(song: Song): CanvasArtwork? =
        runCatching { CanvasRepository.cached(song) }.getOrNull()

    /** The canvas for a release, for the album page's header artwork. */
    suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? = guarded("canvasForAlbum") {
        CanvasRepository.canvasForAlbum(album, artist)
    }

    private suspend fun guarded(what: String, block: suspend () -> CanvasArtwork?): CanvasArtwork? =
        try {
            // Off the caller's thread: these are usually asked from the UI, and
            // the matching and token scraping are not free even between requests.
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackLog.w(TAG, "$what failed: ${e.message}")
            null
        }
}
