package com.music.bitchord.ios

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.ios.party.IosListenTogether
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.NowPlayingScreen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The full player over everything, from the mini player. Dragged down past a quarter of the
 * screen it goes; the shared screen's own layers (lyrics, queue, sheets) take Back first.
 *
 * Lyrics are looked up app-wide as the track changes ([IosLyrics], started in [IosApp]); this
 * only draws them.
 */
@Composable
internal fun IosNowPlaying(
    navigator: IosNavigator,
    onDismiss: () -> Unit,
) {
    val current by IosPlayer.song.collectAsState()
    val song = current ?: return
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val playerLoading by IosPlayer.isLoading.collectAsState()
    val versionSwitching by IosPlayer.versionSwitching.collectAsState()
    val party by IosListenTogether.state.collectAsState()
    // A party play waits for the server's start: the spinner covers that wait.
    val isLoading = playerLoading || party.awaitingStart
    val durationMs by IosPlayer.durationMs.collectAsState()
    val queue by IosPlayer.queue.collectAsState()
    val index by IosPlayer.index.collectAsState()
    val shuffle by IosPlayer.shuffle.collectAsState()
    val repeatMode by IosPlayer.repeatMode.collectAsState()
    val autoplay by IosPlayer.autoplay.collectAsState()
    val signedIn by IosAccount.signedIn.collectAsState()
    val account by IosAccount.account.collectAsState()
    val likes by LikeState.overrides.collectAsState()
    val likeStatus = likes[song.videoId] ?: LikeStatus.INDIFFERENT
    val lyrics by IosLyrics.lyrics.collectAsState()
    val lyricsSource by IosLyrics.source.collectAsState()
    val lyricsChecked by IosLyrics.checked.collectAsState()
    val lyricsProviderStates by IosLyrics.providerStates.collectAsState()

    // Whatever started the track knew its title and artwork, rarely which album and artist pages
    // it belongs to. Asked while the player is up, as the phone does.
    var links by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(song.videoId) {
        links = null
        if (song.albumId != null && song.artistId != null) return@LaunchedEffect
        if (isOwnFile(song)) return@LaunchedEffect
        links = YtMusicRepository.trackLinks(song.videoId).getOrNull()
    }
    val playerSong = links?.takeIf { it.videoId == song.videoId }?.let { extra ->
        song.copy(
            artistId = song.artistId ?: extra.artistId,
            artists = song.artists.ifEmpty { extra.artists },
            albumId = song.albumId ?: extra.albumId,
            albumName = song.albumName ?: extra.albumName,
        )
    } ?: song

    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var height by remember { mutableStateOf(0f) }
    fun leaveTo(open: () -> Unit) {
        onDismiss()
        open()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, drag.value.roundToInt()) }
            .background(Color.Black)
            .draggable(
                state = rememberDraggableState { delta ->
                    scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (drag.value > height * 0.25f || velocity > 2_000f) onDismiss() else drag.animateTo(0f)
                },
            ),
    ) {
        height = constraints.maxHeight.toFloat()
        NowPlayingScreen(
            song = playerSong,
            isPlaying = isPlaying,
            isLoading = isLoading,
            position = IosPlayer.position,
            durationMs = durationMs,
            audioVersionSwitching = versionSwitching,
            qualityUpgraded = false,
            queue = queue,
            queueIndex = index,
            hasPrevious = index > 0,
            hasNext = index < queue.lastIndex || autoplay,
            repeatMode = repeatMode,
            shuffleEnabled = shuffle,
            autoplayEnabled = autoplay,
            signedIn = signedIn,
            accountName = account?.name?.takeIf(String::isNotBlank),
            likeStatus = likeStatus,
            onToggleLike = {
                when {
                    IosLocalMusic.isLocal(song) -> IosPlayerHost.showMessage(tr("Songs on this iPhone can’t be liked on YouTube Music"))
                    IosWebDav.isWebDav(song) -> IosPlayerHost.showMessage(tr("Songs on your server can’t be liked on YouTube Music"))
                    signedIn -> IosAccount.toggleLike(song, likeStatus)
                    else -> leaveTo { IosOverlays.login = true }
                }
            },
            onPlayPause = IosPlayer::togglePlayPause,
            onNext = IosPlayer::next,
            onPrevious = IosPlayer::previous,
            onBlockedControl = { IosPlayerHost.showMessage(tr("Only the host can change what’s playing")) },
            onSeek = IosPlayer::seekTo,
            onSeekFraction = { fraction ->
                val duration = IosPlayer.durationMs.value
                if (duration > 0) IosPlayer.seekTo((fraction * duration).toLong())
            },
            onToggleShuffle = IosPlayer::toggleShuffle,
            onCycleRepeat = IosPlayer::cycleRepeat,
            onToggleAutoplay = IosPlayer::toggleAutoplay,
            onJumpTo = IosPlayer::jumpTo,
            onRemoveFromQueue = IosPlayer::removeAt,
            onMoveInQueue = IosPlayer::move,
            onClearQueue = IosPlayer::clearQueue,
            onOpenMenu = { IosMenus.song = SongMenuRequest(playerSong, fromPlayer = true) },
            onOpenAlbum = { id -> leaveTo { navigator.open(IosPage.Collection(id)) } },
            // The lead credit carries its channel id; anyone else on the line is found by name.
            onOpenArtist = { id, name ->
                leaveTo {
                    if (id != null) {
                        navigator.open(IosPage.Artist(id, name))
                    } else {
                        scope.launch {
                            YtMusicRepository.findArtistPageId(name).getOrNull()
                                ?.let { found -> navigator.open(IosPage.Artist(found, name)) }
                        }
                    }
                }
            },
            onOpenPlaybackSource = {
                val id = playerSong.playbackSourceId
                if (playerSong.playbackSourceType == PlaybackSourceType.BROWSE && id != null) {
                    leaveTo {
                        if (browseTypeOf(id) == com.music.bitchord.data.model.BrowseType.ARTIST) {
                            navigator.open(IosPage.Artist(id, playerSong.playbackSource.orEmpty()))
                        } else {
                            navigator.open(IosPage.Collection(id))
                        }
                    }
                }
            },
            onListenTogether = { leaveTo { navigator.open(IosPage.ListenTogether()) } },
            onQueueDragActiveChange = IosPlayer::queueDrag,
            playedBy = party.playback?.takeIf { it.track?.videoId == song.videoId }?.let { playback ->
                playback.startedByName ?: party.members.firstOrNull { it.memberId == playback.startedBy }?.displayName
            },
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = IosLyrics::select,
            lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
            lyricsOffsetOpen = IosMenus.lyricsOffset,
            onDismissLyricsOffset = { IosMenus.lyricsOffset = false },
            windowWidth = maxWidth,
            windowHeight = maxHeight,
        )
    }
}
