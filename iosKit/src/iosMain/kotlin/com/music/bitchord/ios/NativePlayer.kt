package com.music.bitchord.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ios.party.IosListenTogether
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

/**
 * The player as the SwiftUI Now Playing screen (iosApp/iosApp/NowPlaying) reads and drives it.
 *
 * Swift can't collect a Flow, so each piece is a plain class handed to a callback on the main
 * thread whenever it changes, and each `observe` returns a [PlayerSubscription] the screen closes
 * when it goes, so nothing runs for it while it's away. The position is the one thing not pushed:
 * the screen reads [positionMs] on its own clock while it is up, instead of being sent sixty
 * updates a second.
 *
 * What it does mirrors [IosNowPlaying], the Compose player it replaces on this branch: the same
 * like rules, the same pages behind album and artist, and the same way out, closing the player
 * before a page opens under it.
 */
object NativePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var presenter: ((Boolean) -> Unit)? = null

    /** The app's page stack, given by [IosApp] once it is up. */
    internal var navigator: IosNavigator? = null

    /** The track's album and artist pages, looked up once a track when what started it didn't say. */
    private val links = MutableStateFlow<Song?>(null)
    private var linksJob: Job? = null

    /** Swift's way to show or close the screen, set once at launch. */
    fun setPresenter(present: (Boolean) -> Unit) {
        presenter = present
    }

    private var panelHandler: ((String) -> Unit)? = null

    /** How Swift switches the open screen between "cover", "lyrics" and "queue". */
    fun setPanelHandler(show: (String) -> Unit) {
        panelHandler = show
    }

    /** The simulator tour: the screen open on [panel], or closed with null. */
    internal fun tour(panel: String?) {
        if (panel == null) {
            presenter?.invoke(false)
        } else {
            presenter?.invoke(true)
            panelHandler?.invoke(panel)
        }
    }

    /** Opens the screen; false when Swift hasn't said how, so the Compose player opens instead. */
    internal fun present(): Boolean {
        val show = presenter ?: return false
        show(true)
        return true
    }

    private fun leaveTo(open: () -> Unit) {
        presenter?.invoke(false)
        open()
    }

    // ── What the screen shows ──────────────────────────────────────────────────────────────────

    fun observe(onChange: (NowPlayingSnapshot) -> Unit): PlayerSubscription {
        val controls = combine(IosPlayer.queue, IosPlayer.index, IosPlayer.shuffle, IosPlayer.repeatMode, IosPlayer.autoplay) {
                queue, index, shuffle, repeat, autoplay ->
            Controls(hasPrevious = index > 0, hasNext = index < queue.lastIndex || autoplay, shuffle, repeat, autoplay)
        }
        val playback = combine(IosPlayer.isPlaying, IosPlayer.isLoading, IosPlayer.durationMs, IosListenTogether.state) {
                playing, loading, duration, party ->
            Playback(playing, loading || party.awaitingStart, duration, party.controlsLocked)
        }
        val quality = combine(NerdStats.current, NerdStats.racingLossless) { stats, racing -> stats to racing }
        val job = scope.launch {
            IosPlayer.song.map { it?.videoId }.distinctUntilChanged().collect { lookUpLinks(IosPlayer.song.value) }
        }
        val job2 = scope.launch {
            combine(IosPlayer.song, links, controls, playback, combine(quality, LikeState.overrides) { q, likes -> q to likes }) {
                    song, extra, c, p, (q, likes) ->
                song?.let { snapshotOf(it, extra, c, p, q.first, q.second, likes[it.videoId] ?: LikeStatus.INDIFFERENT) }
            }.distinctUntilChanged().collect { it?.let(onChange) }
        }
        return PlayerSubscription(job, job2)
    }

    /** The lyrics as lines with their start and end, and whether they are timed at all. */
    fun observeLyrics(onChange: (List<LyricRow>, Boolean) -> Unit): PlayerSubscription = PlayerSubscription(
        scope.launch {
            IosLyrics.lyrics.collect { lines ->
                val shown = lines.orEmpty().filterNot { it.isGap && it.timeMs == 0L }
                val synced = shown.any { it.timeMs > 0 }
                val rows = shown.mapIndexed { i, line ->
                    LyricRow(line.timeMs, line.text, shown.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE)
                }
                onChange(rows, synced)
            }
        },
    )

    fun observeQueue(onChange: (List<QueueRow>, Int) -> Unit): PlayerSubscription = PlayerSubscription(
        scope.launch {
            combine(IosPlayer.queue, IosPlayer.index) { queue, index -> queue to index }.collect { (queue, index) ->
                onChange(queue.map { QueueRow(it.videoId, it.title, it.artist, artworkOf(it, ROW_ART_PX)) }, index)
            }
        },
    )

    fun positionMs(): Long = IosPlayer.position.positionMs

    // ── What the screen does ───────────────────────────────────────────────────────────────────

    private fun guarded(action: () -> Unit) {
        if (IosListenTogether.state.value.controlsLocked) {
            IosPlayerHost.showMessage(tr("Only the host can change what’s playing"))
        } else {
            action()
        }
    }

    fun playPause() = guarded(IosPlayer::togglePlayPause)
    fun next() = guarded(IosPlayer::next)
    fun previous() = guarded(IosPlayer::previous)
    fun seekTo(positionMs: Long) = guarded { IosPlayer.seekTo(positionMs) }
    fun toggleShuffle() = IosPlayer.toggleShuffle()
    fun cycleRepeat() = IosPlayer.cycleRepeat()
    fun toggleAutoplay() = IosPlayer.toggleAutoplay()
    fun jumpTo(index: Int) = guarded { IosPlayer.jumpTo(index) }
    fun removeAt(index: Int) = IosPlayer.removeAt(index)
    fun move(from: Int, to: Int) = IosPlayer.move(from, to)

    fun toggleLike() {
        val song = IosPlayer.song.value ?: return
        val status = LikeState.overrides.value[song.videoId] ?: LikeStatus.INDIFFERENT
        when {
            IosLocalMusic.isLocal(song) -> IosPlayerHost.showMessage(tr("Songs on this iPhone can’t be liked on YouTube Music"))
            IosWebDav.isWebDav(song) -> IosPlayerHost.showMessage(tr("Songs on your server can’t be liked on YouTube Music"))
            IosAccount.signedIn.value -> IosAccount.toggleLike(song, status)
            else -> leaveTo { IosOverlays.login = true }
        }
    }

    fun openMenu() {
        val song = playerSong() ?: return
        leaveTo { IosMenus.song = SongMenuRequest(song, fromPlayer = true) }
    }

    fun openAlbum() {
        val id = playerSong()?.albumId ?: return
        leaveTo { navigator?.open(IosPage.Collection(id)) }
    }

    /** The lead credit's page; one without a channel id is found by name. */
    fun openArtist() {
        val song = playerSong() ?: return
        val lead = song.artists.firstOrNull()
        val name = lead?.name ?: song.artist
        val id = lead?.browseId ?: song.artistId
        leaveTo {
            if (id != null) {
                navigator?.open(IosPage.Artist(id, name))
            } else {
                scope.launch {
                    YtMusicRepository.findArtistPageId(name).getOrNull()?.let { navigator?.open(IosPage.Artist(it, name)) }
                }
            }
        }
    }

    /** Where the track was started from (its playlist, album or artist), when that is a page. */
    fun openPlaybackSource() {
        val song = IosPlayer.song.value ?: return
        val id = song.playbackSourceId ?: return
        if (song.playbackSourceType != PlaybackSourceType.BROWSE) return
        leaveTo {
            if (browseTypeOf(id) == com.music.bitchord.data.model.BrowseType.ARTIST) {
                navigator?.open(IosPage.Artist(id, song.playbackSource.orEmpty()))
            } else {
                navigator?.open(IosPage.Collection(id))
            }
        }
    }

    /**
     * The Compose Audio Pipeline dialog, in a see-through controller of its own for Swift to present
     * over the player: the dialog draws its own scrim.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun pipelineViewController(onDismiss: () -> Unit): UIViewController = ComposeUIViewController(configure = { opaque = false }) {
        IosAudioPipelineDialog(hazeState = HazeState(), onDismiss = onDismiss)
    }

    // ── Building the snapshot ──────────────────────────────────────────────────────────────────

    private fun playerSong(): Song? {
        val song = IosPlayer.song.value ?: return null
        val extra = links.value?.takeIf { it.videoId == song.videoId } ?: return song
        return song.copy(
            artistId = song.artistId ?: extra.artistId,
            artists = song.artists.ifEmpty { extra.artists },
            albumId = song.albumId ?: extra.albumId,
            albumName = song.albumName ?: extra.albumName,
        )
    }

    private fun lookUpLinks(song: Song?) {
        linksJob?.cancel()
        links.value = null
        if (song == null || (song.albumId != null && song.artistId != null) || isOwnFile(song)) return
        linksJob = scope.launch { links.value = YtMusicRepository.trackLinks(song.videoId).getOrNull() }
    }

    private class Controls(val hasPrevious: Boolean, val hasNext: Boolean, val shuffle: Boolean, val repeat: Int, val autoplay: Boolean)
    private class Playback(val playing: Boolean, val loading: Boolean, val durationMs: Long, val locked: Boolean)

    private fun snapshotOf(
        song: Song,
        extra: Song?,
        c: Controls,
        p: Playback,
        stats: NerdStats.Snapshot?,
        racing: Set<String>,
        like: LikeStatus,
    ): NowPlayingSnapshot {
        val links = extra?.takeIf { it.videoId == song.videoId }
        val stillRacing = song.videoId in racing && stats?.isLossless != true
        return NowPlayingSnapshot(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            album = song.albumName ?: links?.albumName,
            artworkUrl = artworkOf(song, PLAYER_ART_PX),
            isPlaying = p.playing,
            isLoading = p.loading,
            durationMs = p.durationMs,
            hasPrevious = c.hasPrevious,
            hasNext = c.hasNext,
            shuffle = c.shuffle,
            repeatMode = c.repeat,
            autoplay = c.autoplay,
            liked = like == LikeStatus.LIKE,
            controlsLocked = p.locked,
            canOpenAlbum = (song.albumId ?: links?.albumId) != null,
            canOpenSource = song.playbackSourceType == PlaybackSourceType.BROWSE && song.playbackSourceId != null,
            playingFrom = song.playbackSource,
            qualityLine = stats?.let(::describe),
            badge = when {
                stillRacing -> tr("Upgrading quality")
                stats?.isLossless == true -> if (stats.isHiRes) tr("Hi-Res Lossless") else tr("Lossless")
                stats?.isDolbyAtmos == true -> "Dolby Atmos"
                stats?.isHiQuality == true -> tr("High quality")
                else -> null
            },
            badgeLossless = !stillRacing && stats?.isLossless == true,
            badgePulsing = stillRacing,
        )
    }

    /** "FLAC · 24-bit · 96.0 kHz · 4608 kbps · Stereo": what the Compose player's stats line says. */
    private fun describe(stats: NerdStats.Snapshot): String? = buildList {
        codecLabel(stats.mimeType)?.let(::add)
        stats.bitDepth?.let { add("$it-bit") }
        stats.sampleRateHz?.let { hz ->
            add(NerdStats.megahertzLabel(hz) ?: "${hz / 1000}.${(hz % 1000) / 100} kHz")
        }
        stats.bitrateKbps?.let { add("$it kbps") }
        stats.channels?.let {
            add(
                when (it) {
                    1 -> tr("Mono")
                    2 -> tr("Stereo")
                    else -> tr("{0} channels", it)
                },
            )
        }
    }.joinToString(" · ").takeIf { it.isNotEmpty() }

    private fun codecLabel(mimeType: String?): String? = when {
        mimeType == null -> null
        mimeType.endsWith("opus") -> "Opus"
        mimeType.endsWith("mp4a-latm") -> "AAC"
        mimeType.endsWith("vorbis") -> "Vorbis"
        mimeType.endsWith("mpeg") -> "MP3"
        mimeType.endsWith("flac") -> "FLAC"
        mimeType.endsWith("alac") -> "ALAC"
        else -> mimeType.substringAfter('/').uppercase()
    }

    /** A web address, or a file path (local music's own artwork) Swift reads from disk. */
    private fun artworkOf(song: Song, px: Int): String? =
        song.thumbnailUrl?.let { if (it.startsWith("/")) it else song.artworkAt(px) ?: it }

    private const val PLAYER_ART_PX = 1200
    private const val ROW_ART_PX = 120
}

/** One look at the player, for the SwiftUI screen. Equal when nothing it shows changed. */
data class NowPlayingSnapshot(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val durationMs: Long,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val shuffle: Boolean,
    /** 0 off, 1 one, 2 all: [com.music.bitchord.ui.player.RepeatModes]. */
    val repeatMode: Int,
    val autoplay: Boolean,
    val liked: Boolean,
    val controlsLocked: Boolean,
    val canOpenAlbum: Boolean,
    val canOpenSource: Boolean,
    val playingFrom: String?,
    /** The measured format, or null before the decoder has said. */
    val qualityLine: String?,
    /** "Hi-Res Lossless", "Lossless", "Dolby Atmos", "High quality", "Upgrading quality", or none. */
    val badge: String?,
    val badgeLossless: Boolean,
    val badgePulsing: Boolean,
)

/** A lyric line from [timeMs] until the next one starts at [endMs]. */
data class LyricRow(val timeMs: Long, val text: String, val endMs: Long)

data class QueueRow(val videoId: String, val title: String, val artist: String, val artworkUrl: String?)

/** What an `observe` started; [close] stops it. */
class PlayerSubscription internal constructor(private vararg val jobs: Job) {
    fun close() = jobs.forEach { it.cancel() }
}
