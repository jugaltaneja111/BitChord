package com.music.bitchord.ios

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import com.music.bitchord.data.IosAppLanguage
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.LocaleTags
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.primaryLanguage
import com.music.bitchord.ios.canvas.IosCanvas
import com.music.bitchord.ios.party.IosListenTogether
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.MIN_LYRICS_OFFSET_MS
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

    // ── The bars under the pages ─────────────────────────────────────────────────────────────

    /** Whether Swift draws the mini player and the tabs; Compose then leaves them out. */
    internal var nativeBars by mutableStateOf(false)
        private set

    /** How much of the screen's foot Swift's bars cover, in points, safe area included. */
    internal var nativeBarsHeight by mutableStateOf(0.0)
        private set

    private val chrome = MutableStateFlow(ChromeState(tab = 0, visible = false, dark = true))

    /** Called by Swift at launch, before the Compose view is made, so its bars never show. */
    fun useNativeBars() {
        nativeBars = true
    }

    fun setBarsHeight(points: Double) {
        nativeBarsHeight = points
    }

    /** What the app beneath is doing, given by [IosApp] as it changes. */
    internal fun showChrome(state: ChromeState) {
        chrome.value = state
    }

    fun observeChrome(onChange: (ChromeState) -> Unit): PlayerSubscription =
        PlayerSubscription(scope.launch { chrome.collect(onChange) })

    /** The tabs' names, in their order, in the app's language. */
    fun tabTitles(): List<String> = IosTab.entries.map { it.label }

    /** A tab; the one already showing goes back to its own page, as the Compose bar does. */
    fun selectTab(index: Int) {
        IosTab.entries.getOrNull(index)?.let { navigator?.select(it) }
    }

    /** The mini player's open: the screen, or the Compose player if Swift never said how. */
    fun openPlayer() {
        present()
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
    /**
     * The lyrics as the panel draws them: the original lines, each with the translation or the
     * romanisation under it when one of those is on, as the Compose panel's sub-lines are.
     */
    fun observeLyrics(onChange: (LyricsView) -> Unit): PlayerSubscription {
        // A new track starts in its own words.
        val reset = scope.launch {
            IosPlayer.song.map { it?.videoId }.distinctUntilChanged().collect { resetLyricsMode() }
        }
        val lines = combine(IosLyrics.lyrics, IosLyrics.checked, IosLyrics.source) { lines, checked, source -> Triple(lines, checked, source) }
        val extra = combine(lyricsMode, subLines, lyricsNote, IosSettings.lyricsOffsetMs.flow) { mode, sub, note, offset ->
            LyricsExtra(mode, sub, note, offset)
        }
        val job = scope.launch {
            combine(lines, extra) { (all, checked, source), x ->
                val original = all.orEmpty()
                val under = x.sub?.takeIf { it.forLines == original }?.lines
                val rows = original.withIndex()
                    .filterNot { (_, line) -> line.isGap && line.timeMs == 0L }
                    .let { shown ->
                        shown.mapIndexed { i, (index, line) ->
                            val sub = under?.getOrNull(index)?.text?.takeIf { it.isNotBlank() && it != line.text }
                            LyricRow(line.timeMs, line.text, shown.getOrNull(i + 1)?.value?.timeMs ?: Long.MAX_VALUE, sub)
                        }
                    }
                LyricsView(
                    rows = rows,
                    synced = rows.any { it.timeMs > 0 },
                    loading = !checked && original.isEmpty(),
                    sourceName = source?.label,
                    mode = x.mode,
                    working = x.mode != LyricsModes.ORIGINAL && under == null,
                    note = x.note,
                    offsetMs = x.offset,
                )
            }.distinctUntilChanged().collect(onChange)
        }
        return PlayerSubscription(reset, job)
    }

    private class LyricsExtra(val mode: Int, val sub: SubLines?, val note: String?, val offset: Int)

    /** A translation or romanisation, and the lines it was made from. */
    private class SubLines(val forLines: List<LyricLine>, val lines: List<LyricLine>)

    private val lyricsMode = MutableStateFlow(LyricsModes.ORIGINAL)
    private val subLines = MutableStateFlow<SubLines?>(null)
    private val lyricsNote = MutableStateFlow<String?>(null)
    private var subJob: Job? = null

    private fun resetLyricsMode() {
        subJob?.cancel()
        lyricsMode.value = LyricsModes.ORIGINAL
        subLines.value = null
        lyricsNote.value = null
    }

    fun toggleTranslation() = toggleSubLines(LyricsModes.TRANSLATED)
    fun toggleRomanization() = toggleSubLines(LyricsModes.ROMANIZED)

    /** Turns [mode] on, fetching it, or off when it is the one showing. */
    private fun toggleSubLines(mode: Int) {
        val wasOn = lyricsMode.value == mode
        resetLyricsMode()
        if (wasOn) return
        val song = IosPlayer.song.value ?: return
        val lines = IosLyrics.lyrics.value?.takeIf { it.isNotEmpty() } ?: return
        lyricsMode.value = mode
        subJob = scope.launch {
            val target = translationTarget()
            val made: List<LyricLine>? = if (mode == LyricsModes.TRANSLATED) {
                when (val result = LyricsTranslation.translate(song.videoId, lines, target)) {
                    is LyricsTranslation.Result.Translated -> result.lines
                    is LyricsTranslation.Result.SameLanguage -> {
                        lyricsNote.value = tr("Lyrics are already in {0}", translationLanguageName(target, deviceLanguageTag()))
                        null
                    }
                    LyricsTranslation.Result.Unavailable -> {
                        lyricsNote.value = tr("Translation isn’t available right now")
                        null
                    }
                }
            } else {
                when (val result = LyricsTranslation.romanize(song.videoId, lines, target)) {
                    is LyricsTranslation.RomanizationResult.Romanized -> result.lines
                    LyricsTranslation.RomanizationResult.AlreadyRomanized -> {
                        lyricsNote.value = tr("Lyrics are already in Latin script")
                        null
                    }
                    LyricsTranslation.RomanizationResult.Unavailable -> {
                        lyricsNote.value = tr("Romanization isn’t available right now")
                        null
                    }
                }
            }
            if (made == null) lyricsMode.value = LyricsModes.ORIGINAL else subLines.value = SubLines(lines, made)
        }
    }

    /**
     * The language to translate into: Settings' choice, else the app's, reduced to its base
     * language except Chinese, whose script matters (PlayerLyrics' rule).
     */
    private fun translationTarget(): String = IosSettings.translationLanguage.flow.value.ifBlank {
        val app = IosAppLanguage.tag() ?: deviceLanguageTag()
        if (LocaleTags.isChineseTag(app)) LocaleTags.translationWireTarget(app) else primaryLanguage(app).ifBlank { "en" }
    }

    /** The lyrics providers in the listener's order, each with how its lookup for this track went. */
    fun observeLyricsProviders(onChange: (List<LyricsProviderRow>) -> Unit): PlayerSubscription = PlayerSubscription(
        scope.launch {
            combine(IosLyrics.providerStates, IosLyrics.source, IosSettings.lyricsSourceOrder.flow) { states, current, order ->
                LyricsSource.ordered(order).map { source ->
                    val state = states[source] ?: LyricsProviderState.NOT_FETCHED
                    LyricsProviderRow(
                        key = source.name,
                        name = source.label,
                        detail = source.detail,
                        state = when (state) {
                            LyricsProviderState.NOT_FETCHED -> 0
                            LyricsProviderState.FETCHING -> 1
                            LyricsProviderState.FOUND -> 2
                            LyricsProviderState.NOT_FOUND -> 3
                        },
                        selected = source == current,
                    )
                }
            }.distinctUntilChanged().collect(onChange)
        },
    )

    fun selectLyricsProvider(key: String) {
        LyricsSource.entries.firstOrNull { it.name == key }?.let(IosLyrics::select)
    }

    /** Shifts every synced line against the clock; positive shows them later. */
    fun setLyricsOffset(ms: Int) {
        IosSettings.lyricsOffsetMs.set(ms.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS))
    }

    /**
     * The track's Canvas, the looping clip in place of its cover, found as the Compose player finds
     * it ([com.music.bitchord.ui.player.rememberCanvasArtwork]): only while the setting allows it
     * here and now, anything cached at once, the album name given a moment to land, and a clip kept
     * when a second look comes back empty.
     */
    fun observeCanvas(onChange: (CanvasClip?) -> Unit): PlayerSubscription = PlayerSubscription(
        scope.launch {
            val allowed = combine(
                IosSettings.animatedCanvas.flow,
                IosSettings.canvasOverCellular.flow,
                IosNetwork.metered,
                IosSettings.prioritizeSpotifyCanvas.flow,
            ) { on, overCellular, metered, spotifyFirst -> (on && (metered != true || overCellular)) to spotifyFirst }
            val song = combine(IosPlayer.song, links) { _, _ -> playerSong() }
                .map { it?.let { s -> CanvasKey(s, s.title, s.artist, s.videoId, s.albumName) } }
                .distinctUntilChanged()
            var shown: CanvasArtwork? = null
            var shownFor: Pair<String, String>? = null
            combine(song, allowed) { key, allow -> key to allow }.collectLatest { (key, allow) ->
                val track = key?.song
                if (track == null || !allow.first) {
                    shown = null
                    onChange(null)
                    return@collectLatest
                }
                if (shownFor != track.title to track.artist) shown = null
                shownFor = track.title to track.artist
                shown = IosCanvas.cached(track) ?: shown?.takeIf { it.matches(track.title, track.artist, track.albumName) }
                onChange(shown?.let(::clipOf))
                if (shown == null && track.albumName == null) delay(ALBUM_SETTLE_MS)
                shown = IosCanvas.canvasFor(track) ?: shown
                onChange(shown?.let(::clipOf))
            }
        },
    )

    private class CanvasKey(val song: Song, val title: String, val artist: String, val videoId: String, val album: String?) {
        override fun equals(other: Any?) =
            other is CanvasKey && title == other.title && artist == other.artist && videoId == other.videoId && album == other.album
        override fun hashCode() = videoId.hashCode()
    }

    private fun clipOf(canvas: CanvasArtwork) =
        CanvasClip(canvas.url, canvas.fallbackUrl, fullScreen = canvas.source == CanvasSource.SPOTIFY)

    private const val ALBUM_SETTLE_MS = 700L

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

    /**
     * Whether the menus are drawn over the native screen by [menuViewController] rather than by the
     * app beneath it, which would put them behind the screen.
     */
    internal var menusHosted by mutableStateOf(false)

    /**
     * The track's menu, for [menuViewController] to draw over the screen, which stays up under it as
     * the Compose player does; only what leads to a page closes it.
     */
    fun openMenu() {
        val song = playerSong() ?: return
        menusHosted = true
        IosMenus.song = SongMenuRequest(song, fromPlayer = true)
    }

    /**
     * The song menu, and the playlist pickers it leads to, in a see-through controller for Swift to
     * present over the screen. [onDismiss] once none of them is open.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun menuViewController(onDismiss: () -> Unit): UIViewController = ComposeUIViewController(configure = { opaque = false }) {
        DisposableEffect(Unit) { onDispose { menusHosted = false } }
        val open = IosMenus.song != null || IosMenus.browse != null || IosMenus.addToPlaylist != null || IosMenus.newPlaylist != null
        LaunchedEffect(open) { if (!open) onDismiss() }
        navigator?.let { nav ->
            IosMenuHost(nav, onLeavePlayer = {
                onDismiss()
                presenter?.invoke(false)
            })
        }
    }

    /** Whether the menu asked for the lyrics timing sheet, which the screen draws itself. Asking clears it. */
    fun takeLyricsTimingRequest(): Boolean {
        val asked = IosMenus.lyricsOffset
        IosMenus.lyricsOffset = false
        return asked
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
data class LyricRow(val timeMs: Long, val text: String, val endMs: Long, val subText: String? = null)

/** 0 the original words, 1 with their translation under them, 2 with their romanisation. */
object LyricsModes {
    const val ORIGINAL = 0
    const val TRANSLATED = 1
    const val ROMANIZED = 2
}

/** Everything the lyrics panel shows. [working] while the chosen sub-lines are being fetched. */
data class LyricsView(
    val rows: List<LyricRow>,
    val synced: Boolean,
    val loading: Boolean,
    val sourceName: String?,
    val mode: Int,
    val working: Boolean,
    val note: String?,
    val offsetMs: Int,
)

/** A lyrics provider; [state] 0 not asked, 1 asking, 2 has this track, 3 doesn't. */
data class LyricsProviderRow(val key: String, val name: String, val detail: String, val state: Int, val selected: Boolean)

/**
 * The bars' state: the selected [tab], whether they show at all (not over the welcome, the sign-in
 * screens or Replay's stories, which the Compose bars sat under), and the app's theme.
 */
data class ChromeState(val tab: Int, val visible: Boolean, val dark: Boolean)

/** A Canvas clip. [fullScreen] for Spotify's tall ones, drawn behind the whole screen. */
data class CanvasClip(val url: String, val fallbackUrl: String?, val fullScreen: Boolean)

data class QueueRow(val videoId: String, val title: String, val artist: String, val artworkUrl: String?)

/** What an `observe` started; [close] stops it. */
class PlayerSubscription internal constructor(private vararg val jobs: Job) {
    fun close() = jobs.forEach { it.cancel() }
}
