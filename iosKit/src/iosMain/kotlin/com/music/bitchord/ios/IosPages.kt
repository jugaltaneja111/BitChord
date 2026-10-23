package com.music.bitchord.ios

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.ios.canvas.AppleArtistVideo
import com.music.bitchord.ios.canvas.IosCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.ui.screens.LibraryGridPage
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.screens.MoodGenrePlaylistsScreen
import com.music.bitchord.ui.screens.Shelf
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.on_device
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.filter

/** An album or playlist page as loaded: the first page of rows, and the token for the rest. */
private data class LoadedCollection(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
    val continuation: String?,
)

/**
 * What each page loaded, kept for the session so Back to a page shows it again rather than a
 * loader. Small: a page is a few dozen rows.
 */
private object PageCache {
    private const val MAX_PAGES = 24
    private val pages = LinkedHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? = pages[key] as T?

    fun put(key: String, value: Any) {
        pages.remove(key)
        pages[key] = value
        while (pages.size > MAX_PAGES) pages.remove(pages.keys.first())
    }
}

@Composable
internal fun IosPageHost(
    page: IosPage,
    navigator: IosNavigator,
    contentPadding: PaddingValues,
    topInset: Dp,
    onClearSearchHistory: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Pages with no artwork header of their own start under the back button.
        val underBack = topInset + 52.dp
        when (page) {
            is IosPage.Collection -> if (IosLocalPlaylists.isLocal(page.browseId)) {
                LocalPlaylistPage(page, contentPadding, topInset)
            } else {
                CollectionPage(page, contentPadding, topInset)
            }
            is IosPage.Artist -> ArtistPageView(page, navigator, contentPadding, topInset)
            is IosPage.Category -> CategoryPage(page, navigator, contentPadding, topInset)
            is IosPage.LibraryShelf -> LibraryShelfPage(page, navigator, contentPadding, topInset)
            is IosPage.Replay -> ReplayPage(page, navigator, contentPadding, topInset)
            IosPage.History -> HistoryPage(contentPadding, underBack)
            IosPage.Downloads -> DownloadsPage(contentPadding, underBack)
            IosPage.LocalMusic -> LocalMusicPage(navigator, contentPadding, underBack)
            is IosPage.LocalMusicGroup -> LocalMusicGroupPage(page, contentPadding, underBack)
            IosPage.Settings -> FormWidth { SettingsPage(contentPadding, underBack, onClearSearchHistory, onOpenLyricsSources = { navigator.open(IosPage.LyricsSources) }, onOpenTranslationLanguage = { navigator.open(IosPage.TranslationLanguage) }, onOpenSources = { navigator.open(IosPage.Sources) }, onOpenDownloads = { navigator.open(IosPage.Downloads) }, onOpenEqualizer = { navigator.open(IosPage.Equalizer) }, onOpenIntegrations = { navigator.open(IosPage.Integrations) }, onOpenReplay = { navigator.open(IosPage.Replay()) }, onOpenListenTogether = { navigator.open(IosPage.ListenTogether()) }) }
            IosPage.Integrations -> FormWidth { IntegrationsPage(contentPadding, underBack, onOpenSpotify = { navigator.open(IosPage.Spotify) }, onOpenDiscord = { navigator.open(IosPage.Discord) }) }
            IosPage.Spotify -> SpotifyPage(navigator, contentPadding, underBack)
            IosPage.Discord -> FormWidth { DiscordPage(contentPadding, underBack) }
            is IosPage.ListenTogether -> {
                var invite by remember(page) { mutableStateOf(page.inviteCode to page.inviteServer) }
                com.music.bitchord.ios.party.ListenTogetherPage(
                    contentPadding = contentPadding,
                    topPadding = underBack,
                    inviteCode = invite.first,
                    inviteServer = invite.second,
                    onInviteHandled = { invite = null to null },
                    onSignIn = { IosOverlays.login = true },
                )
            }
            is IosPage.SpotifyPlaylist -> SpotifyPlaylistPage(page, contentPadding, underBack, onOpen = navigator::open)
            IosPage.Equalizer -> FormWidth { EqualizerPage(contentPadding, underBack) }
            IosPage.LyricsSources -> FormWidth { LyricsSourcesPage(contentPadding, underBack) }
            IosPage.TranslationLanguage -> FormWidth { TranslationLanguagePage(contentPadding, underBack) }
            IosPage.Sources -> FormWidth { com.music.bitchord.ios.sources.SourcesPage(contentPadding, underBack) }
        }
        BackButton(onClick = { navigator.back() }, modifier = Modifier.padding(top = topInset + 4.dp, start = 8.dp))
    }
}

/**
 * A page of settings rows, held to a readable column and centred on an iPad's wide screen rather
 * than run from edge to edge; a phone is narrower than the cap, so nothing changes there.
 */
@Composable
private fun FormWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = FORM_MAX_WIDTH).fillMaxSize()) { content() }
    }
}

private val FORM_MAX_WIDTH = 720.dp

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back"), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CollectionPage(page: IosPage.Collection, contentPadding: PaddingValues, topInset: Dp) {
    val key = "collection:${page.browseId}"
    var reloads by remember { mutableIntStateOf(0) }
    var state by remember(page.browseId) {
        mutableStateOf<UiState<LoadedCollection>>(PageCache.get<LoadedCollection>(key)?.let { UiState.Success(it) } ?: UiState.Loading)
    }
    var loadingMore by remember(page.browseId) { mutableStateOf(false) }
    val type = page.fallback?.type?.takeIf { it != BrowseType.OTHER } ?: browseTypeOf(page.browseId)
    // A playlist can change under its cached copy — from this app or any other — so it is read
    // again on every visit and after every edit, the cached tracks showing meanwhile.
    val playlistEdits by IosAccount.playlistEdits.collectAsState()
    val edits = if (type == BrowseType.PLAYLIST) playlistEdits else 0
    LaunchedEffect(page.browseId, reloads, edits) {
        val shown = state is UiState.Success
        if (shown && type != BrowseType.PLAYLIST) return@LaunchedEffect
        if (!shown) state = UiState.Loading
        YtMusicRepository.browseSongs(page.browseId).fold(
            onSuccess = { loaded ->
                val header = loaded.header
                val collection = LoadedCollection(
                    title = header?.title?.takeIf(String::isNotBlank) ?: page.fallback?.title.orEmpty(),
                    subtitle = header?.subtitle?.takeIf(String::isNotBlank) ?: page.fallback?.subtitle.orEmpty(),
                    thumbnailUrl = header?.thumbnailUrl ?: page.fallback?.thumbnailUrl,
                    songs = loaded.songs,
                    continuation = loaded.continuation,
                )
                PageCache.put(key, collection)
                state = UiState.Success(collection)
            },
            onFailure = { error ->
                if (shown) return@fold
                // No connection, or no answer: a release saved whole still opens, from the phone.
                val saved = IosDownloads.releases.value.firstOrNull { it.browseId == page.browseId }
                val local = saved?.videoIds?.mapNotNull { id -> IosDownloads.tracks.value.firstOrNull { it.videoId == id } }
                state = if (saved != null && !local.isNullOrEmpty()) {
                    UiState.Success(
                        LoadedCollection(
                            title = saved.title,
                            subtitle = saved.subtitle,
                            thumbnailUrl = saved.thumbnailUrl,
                            songs = local.map(IosDownloads::song),
                            continuation = null,
                        ),
                    )
                } else {
                    UiState.Error(error.message ?: tr("Could not open this page"))
                }
            },
        )
    }
    val listState = rememberLazyListState()
    val loaded = (state as? UiState.Success)?.data
    // The rest of a long playlist, a page at a time as the list nears its end.
    LaunchedEffect(listState, loaded?.continuation) {
        val token = loaded?.continuation ?: return@LaunchedEffect
        snapshotFlow { listState.nearEnd() }.distinctUntilChanged().filter { it }.collect {
            if (loadingMore) return@collect
            loadingMore = true
            YtMusicRepository.moreSongs(token).onSuccess { more ->
                val updated = loaded.copy(songs = loaded.songs + more.songs, continuation = more.continuation)
                PageCache.put(key, updated)
                state = UiState.Success(updated)
            }.onFailure {
                state = UiState.Success(loaded.copy(continuation = null))
            }
            loadingMore = false
        }
    }

    val title = loaded?.title ?: page.fallback?.title.orEmpty()
    val songs = loaded?.songs.orEmpty().map { song ->
        song.copy(
            thumbnailUrl = song.thumbnailUrl ?: loaded?.thumbnailUrl,
            playbackSource = title,
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = page.browseId,
        )
    }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    // The account's own playlist offers Remove on each row; asked once per page.
    val signedIn by IosAccount.signedIn.collectAsState()
    var owned by remember(page.browseId) { mutableStateOf(false) }
    LaunchedEffect(page.browseId, signedIn) {
        owned = signedIn && type == BrowseType.PLAYLIST &&
            YtMusicRepository.playlistOwned(page.browseId).getOrNull() == true
    }

    // An album's animated cover, the player's own, on the header; albums only, as on the phone —
    // a playlist's artwork is a collage no label publishes a clip for.
    val canvasEnabled by IosSettings.animatedCanvas.flow.collectAsState()
    // The header's credit, as the phone reads it: the subtitle less its year and its "Album".
    val credit = (loaded?.subtitle ?: page.fallback?.subtitle.orEmpty())
        .split("•", "·").map(String::trim)
        .filter { it.isNotEmpty() && !(it.length == 4 && it.all(Char::isDigit)) && it.lowercase() !in RELEASE_KINDS }
        .joinToString(", ")
        .ifBlank { songs.firstOrNull()?.artist.orEmpty() }
    var canvas by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, title, credit, canvasEnabled) {
        if (!canvasEnabled || type != BrowseType.ALBUM || title.isBlank()) {
            canvas = null
            return@LaunchedEffect
        }
        // The credit fills in once the tracks load, so this can run twice; a clip already up stays.
        canvas = IosCanvas.canvasForAlbum(title, credit) ?: canvas
    }

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            CollectionHeader(
                canvas = canvas,
                title = title,
                subtitle = loaded?.subtitle ?: page.fallback?.subtitle.orEmpty(),
                thumbnailUrl = loaded?.thumbnailUrl ?: page.fallback?.thumbnailUrl,
                typeLabel = when (type) {
                    BrowseType.ALBUM -> tr("Album")
                    BrowseType.PLAYLIST -> tr("Playlist")
                    else -> ""
                },
                count = songs.size.takeIf { loaded != null },
                topInset = topInset,
                onPlay = { IosPlayer.playQueue(songs, 0) },
                onShuffle = { IosPlayer.playShuffled(songs) },
                onMore = {
                    IosMenus.browse = BrowseMenuRequest(
                        browseId = page.browseId,
                        title = title,
                        subtitle = loaded?.subtitle ?: page.fallback?.subtitle.orEmpty(),
                        thumbnailUrl = loaded?.thumbnailUrl ?: page.fallback?.thumbnailUrl,
                        fromPage = true,
                    )
                },
            )
        }
        when (val s = state) {
            UiState.Loading -> songListSkeleton()
            is UiState.Error -> item(key = "error") {
                MessageState(s.message, actionLabel = tr("Retry"), onAction = { reloads++ })
            }
            is UiState.Success -> {
                itemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                    val isCurrent = current?.videoId == song.videoId
                    SongRow(
                        song = song,
                        onClick = { IosPlayer.playQueue(songs, index) },
                        onLongPress = {
                            IosMenus.song = SongMenuRequest(
                                song = song,
                                fromPlaylistId = page.browseId.removePrefix("VL").takeIf { owned },
                                onRemoved = removed@{
                                    val now = (state as? UiState.Success)?.data ?: return@removed
                                    val updated = now.copy(songs = now.songs.filterNot { it.setVideoId == song.setVideoId })
                                    PageCache.put(key, updated)
                                    state = UiState.Success(updated)
                                },
                            )
                        },
                        onSwipeToQueue = { IosPlayer.swipe(song) },
                        trackNumber = if (type == BrowseType.ALBUM) song.trackNumber ?: (index + 1) else null,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                    )
                }
                if (songs.isEmpty()) {
                    item(key = "empty") { MessageState(tr("Nothing to play on this page")) }
                }
                if (loadingMore) item(key = "more") { PageSpinner() }
            }
        }
    }
}

/**
 * A playlist kept on this iPhone: read live from the store, no network, so an edit from its menu
 * or a row's lands at once. Its rows offer Remove from playlist; its header's menu, rename and delete.
 */
@Composable
private fun LocalPlaylistPage(page: IosPage.Collection, contentPadding: PaddingValues, topInset: Dp) {
    val playlists by IosLocalPlaylists.playlists.collectAsState()
    val playlist = remember(playlists, page.browseId) { IosLocalPlaylists.get(page.browseId) }
    val title = playlist?.title ?: page.fallback?.title.orEmpty()
    val subtitle = tr("On this device")
    val thumbnailUrl = playlist?.thumbnailUrl ?: page.fallback?.thumbnailUrl
    // A file on this iPhone takes its cover from the latest scan, which the playlist doesn't keep.
    val localTracks by IosLocalMusic.tracks.collectAsState()
    val songs = playlist?.songs.orEmpty().map { song ->
        IosLocalMusic.current(song, localTracks)
            .copy(playbackSource = title, playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = page.browseId)
    }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            CollectionHeader(
                title = title,
                subtitle = subtitle,
                thumbnailUrl = thumbnailUrl,
                typeLabel = tr("Playlist"),
                count = songs.size,
                topInset = topInset,
                onPlay = { if (songs.isNotEmpty()) IosPlayer.playQueue(songs, 0) },
                onShuffle = { if (songs.isNotEmpty()) IosPlayer.playShuffled(songs) },
                onMore = {
                    if (playlist != null) {
                        IosMenus.browse = BrowseMenuRequest(page.browseId, title, playlist.subtitle, thumbnailUrl, fromPage = true)
                    }
                },
            )
        }
        when {
            playlist == null -> item(key = "gone") { MessageState(tr("This playlist is no longer on this device")) }
            songs.isEmpty() -> item(key = "empty") { MessageState(tr("No songs yet. Add some with Add to playlist in any song’s menu.")) }
            else -> itemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                val isCurrent = current?.videoId == song.videoId
                SongRow(
                    song = song,
                    onClick = { IosPlayer.playQueue(songs, index) },
                    onLongPress = { IosMenus.song = SongMenuRequest(song = song, fromPlaylistId = page.browseId) },
                    onSwipeToQueue = { IosPlayer.swipe(song) },
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                )
            }
        }
    }
}

@Composable
internal fun CollectionHeader(
    canvas: CanvasArtwork? = null,
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    typeLabel: String,
    count: Int?,
    topInset: Dp,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMore: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = topInset + 56.dp, start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // As on the phone, held to half the window's height, so an iPad still shows the title under it.
        Box(
            Modifier
                .fillMaxWidth(0.68f)
                .widthIn(max = windowHeight() * 0.5f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            canvas?.let { HeaderClip(it, Modifier.matchParentSize()) }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            subtitle.takeIf(String::isNotBlank),
            count?.takeIf { it > 0 }?.let { trPlural(it, "{0} song", "{0} songs") },
        ).joinToString(" • ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (typeLabel.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(typeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
        PlayControls(onPlay = onPlay, onShuffle = onShuffle, onMore = onMore)
    }
}

/** The phone's release controls: a labelled Play pill and Shuffle. */
@Composable
internal fun PlayControls(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlay)
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(6.dp))
            Text(tr("Play"), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
        }
        CircleAction(BitChordIcons.Shuffle, tr("Shuffle"), onShuffle)
        onMore?.let { CircleAction(Icons.Rounded.MoreHoriz, tr("More"), it) }
    }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(GroupColor).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ArtistPageView(page: IosPage.Artist, navigator: IosNavigator, contentPadding: PaddingValues, topInset: Dp) {
    val key = "artist:${page.browseId}"
    var reloads by remember { mutableIntStateOf(0) }
    var state by remember(page.browseId) {
        mutableStateOf<UiState<ArtistPage>>(PageCache.get<ArtistPage>(key)?.let { UiState.Success(it) } ?: UiState.Loading)
    }
    LaunchedEffect(page.browseId, reloads) {
        if (state is UiState.Success) return@LaunchedEffect
        state = UiState.Loading
        YtMusicRepository.artistPage(page.browseId).fold(
            onSuccess = {
                PageCache.put(key, it)
                state = UiState.Success(it)
            },
            onFailure = { state = UiState.Error(it.message ?: tr("Could not open {0}", page.name)) },
        )
    }
    val artist = (state as? UiState.Success)?.data
    val name = artist?.name?.takeIf(String::isNotBlank) ?: page.name
    val top = artist?.songs.orEmpty().take(MAX_TOP_SONGS).map { song ->
        song.copy(playbackSource = name, playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = page.browseId)
    }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    // Some artists' Apple Music pages loop a clip where the photograph is; the phone plays it.
    val canvasEnabled by IosSettings.animatedCanvas.flow.collectAsState()
    var video by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(name, canvasEnabled) {
        video = if (canvasEnabled && name.isNotBlank()) AppleArtistVideo.videoFor(name)?.let { CanvasArtwork(url = it) } else null
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "banner") { ArtistBanner(artist?.thumbnailUrl, name, video) }
        when (val s = state) {
            UiState.Loading -> songListSkeleton(count = 5)
            is UiState.Error -> item(key = "error") {
                MessageState(s.message, actionLabel = tr("Retry"), onAction = { reloads++ })
            }
            is UiState.Success -> {
                val stats = listOfNotNull(
                    s.data.subscriberCountText?.let { tr("{0} subscribers", it.substringBefore(' ')) },
                    s.data.monthlyListenerCount?.let { tr("{0} monthly listeners", it.substringBefore(' ')) },
                ).joinToString(" • ")
                item(key = "actions") {
                    Column(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
                        if (stats.isNotBlank()) {
                            Text(stats, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                        }
                        PlayControls(
                            onPlay = { IosPlayer.playQueue(top, 0) },
                            onShuffle = { IosPlayer.playShuffled(top) },
                            onMore = {
                                IosMenus.browse = BrowseMenuRequest(page.browseId, name, stats, artist?.thumbnailUrl, fromPage = true)
                            },
                        )
                    }
                }
                if (top.isNotEmpty()) {
                    item(key = "top-title") { PageSectionTitle(tr("Top songs")) }
                    itemsIndexed(top, key = { index, song -> "top:${song.videoId}:$index" }) { index, song ->
                        val isCurrent = current?.videoId == song.videoId
                        SongRow(
                            song = song,
                            onClick = { IosPlayer.playQueue(top, index) },
                            onLongPress = { IosMenus.song = SongMenuRequest(song) },
                            onSwipeToQueue = { IosPlayer.swipe(song) },
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && isPlaying,
                        )
                    }
                }
                s.data.sections.forEachIndexed { index, shelf ->
                    item(key = "shelf:$index:${shelf.title}") {
                        Box(Modifier.padding(top = 18.dp)) {
                            Shelf(
                                shelf = shelf,
                                onItemClick = { navigator.openShelfItem(it, name) },
                                onItemLongPress = { IosMenus.openShelfItem(it, name) },
                                currentSong = current,
                                isPlaying = isPlaying,
                            )
                        }
                    }
                }
                s.data.description?.takeIf(String::isNotBlank)?.let { about ->
                    item(key = "about") {
                        Column {
                            PageSectionTitle(tr("About"))
                            Text(
                                about,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The photograph, full width, with the name across the foot of it. */
@Composable
private fun ArtistBanner(url: String?, name: String, video: CanvasArtwork?) {
    // Square on a phone; on an iPad no taller than 60% of the window, as the phone's tablet layout.
    Box(Modifier.fillMaxWidth().squareUpTo(windowHeight() * 0.6f).background(MaterialTheme.colorScheme.surface)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        video?.let { HeaderClip(it, Modifier.fillMaxSize()) }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to MaterialTheme.colorScheme.background),
            ),
        )
        Text(
            name,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
        )
    }
}

@Composable
private fun PageSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun CategoryPage(page: IosPage.Category, navigator: IosNavigator, contentPadding: PaddingValues, topInset: Dp) {
    val item = page.item
    val key = "category:${item.browseId}:${item.params}"
    var reloads by remember { mutableIntStateOf(0) }
    var state by remember(key) {
        mutableStateOf<UiState<List<HomeShelf>>>(PageCache.get<List<HomeShelf>>(key)?.let { UiState.Success(it) } ?: UiState.Loading)
    }
    LaunchedEffect(key, reloads) {
        if (state is UiState.Success) return@LaunchedEffect
        state = UiState.Loading
        YtMusicRepository.moodGenreShelves(item.browseId, item.params).fold(
            onSuccess = { shelves ->
                if (shelves.isEmpty()) {
                    state = UiState.Error(tr("Nothing in {0} right now", item.title))
                } else {
                    PageCache.put(key, shelves)
                    state = UiState.Success(shelves)
                }
            },
            onFailure = { state = UiState.Error(it.message ?: tr("Could not load this category")) },
        )
    }
    MoodGenrePlaylistsScreen(
        title = item.title,
        state = state,
        listState = rememberLazyListState(),
        onItemClick = { navigator.openShelfItem(it, item.title) },
        onRetry = { reloads++ },
        contentPadding = PaddingValues(
            top = topInset + 48.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
    )
}

@Composable
internal fun PageSpinner() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onSurface, strokeWidth = 2.dp)
    }
}

/** Within a few rows of the end of what is loaded. */
internal fun LazyListState.nearEnd(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return false
    return info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
}

private const val MAX_TOP_SONGS = 5

/**
 * The account's listening history, most recent first, as the phone's HistoryScreen draws it.
 * A row plays with the rest of the history behind it.
 */
@Composable
private fun HistoryPage(contentPadding: PaddingValues, topPadding: Dp) {
    var reloads by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState<List<Song>>>(UiState.Loading) }
    val generation by IosAccount.generation.collectAsState()
    LaunchedEffect(reloads, generation) {
        state = UiState.Loading
        state = YtMusicRepository.history().fold(
            onSuccess = { UiState.Success(it) },
            onFailure = { UiState.Error(it.message ?: tr("Could not load your history")) },
        )
    }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Text(
                tr("History"),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        when (val s = state) {
            UiState.Loading -> songListSkeleton(count = 10, keyPrefix = "skeleton:history")
            is UiState.Error -> item(key = "error") {
                MessageState(s.message, actionLabel = tr("Retry"), onAction = { reloads++ })
            }
            is UiState.Success -> {
                val songs = s.data
                if (songs.isEmpty()) {
                    item(key = "empty") { MessageState(tr("Nothing played on this account yet")) }
                }
                itemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                    val isCurrent = current?.videoId == song.videoId
                    SongRow(
                        song = song,
                        onClick = {
                            IosPlayer.playQueue(
                                songs.map { it.copy(playbackSource = tr("History"), playbackSourceType = PlaybackSourceType.HISTORY) },
                                index,
                            )
                        },
                        onLongPress = { IosMenus.song = SongMenuRequest(song) },
                        onSwipeToQueue = { IosPlayer.swipe(song) },
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        searchPlayingStyle = true,
                        activeTint = PlayingAccent,
                    )
                }
            }
        }
    }
}

/** A Library shelf laid out as a grid, its name beside the back button. */
@Composable
private fun LibraryShelfPage(page: IosPage.LibraryShelf, navigator: IosNavigator, contentPadding: PaddingValues, topInset: Dp) {
    val state by IosAccount.library.collectAsState()
    // The On Device shelf is drawn from the phone, not the account's feed.
    val onDevice = stringResource(Res.string.on_device)
    val deviceItems = deviceShelfItems()
    val shelf = if (page.title == onDevice) {
        HomeShelf(page.title, deviceItems)
    } else {
        (state as? UiState.Success)?.data?.shelves?.firstOrNull { it.title == page.title }
            ?: HomeShelf(page.title, emptyList())
    }
    val isPlaylists = page.title == YtMusicRepository.PLAYLISTS_SHELF
    LibraryGridPage(
        shelf = shelf,
        gridState = rememberLazyGridState(),
        onItemClick = { navigator.openShelfItem(it, null) },
        onItemLongPress = { IosMenus.openShelfItem(it) },
        contentPadding = PaddingValues(top = topInset + 60.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        onNewPlaylist = if (isPlaylists) {
            { IosMenus.newPlaylist = emptyList() }
        } else {
            null
        },
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(topInset + 52.dp)
            .background(Brush.verticalGradient(0.7f to MaterialTheme.colorScheme.background, 1f to Color.Transparent)),
    )
    Text(
        page.title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = topInset + 10.dp, start = 60.dp, end = 16.dp),
    )
}

/** Every track kept on the phone, newest first, with whatever is still coming down above them. */
@Composable
private fun DownloadsPage(contentPadding: PaddingValues, topPadding: Dp) {
    val tracks by IosDownloads.tracks.collectAsState()
    val progress by IosDownloads.progress.collectAsState()
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val songs = tracks.map { track ->
        IosDownloads.song(track).copy(playbackSource = tr("Downloads"), playbackSourceType = PlaybackSourceType.QUEUE)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(tr("Downloads"), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    tr("{0} · {1} MB on this iPhone", trPlural(songs.size, "{0} song", "{0} songs"), tracks.sumOf { it.bytes } / (1024 * 1024)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    PlayControls(
                        onPlay = { IosPlayer.playQueue(songs, 0) },
                        onShuffle = { IosPlayer.playShuffled(songs) },
                    )
                }
            }
        }
        if (progress.isNotEmpty()) {
            item(key = "pending:title") {
                Text(
                    tr("Downloading"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            progress.forEach { (videoId, state) ->
                item(key = "pending:$videoId") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            IosDownloads.pendingTitle(videoId),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            when (state) {
                                DownloadProgress.Queued -> tr("Waiting")
                                is DownloadProgress.Running -> "${(state.fraction * 100).toInt()}%"
                                is DownloadProgress.Failed -> tr("Failed")
                            },
                            color = if (state is DownloadProgress.Failed) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (state is DownloadProgress.Failed) tr("Dismiss") else tr("Cancel"),
                            color = PlayingAccent,
                            modifier = Modifier.clickable { IosDownloads.cancel(videoId) },
                        )
                    }
                }
            }
        }
        if (songs.isEmpty() && progress.isEmpty()) {
            item(key = "empty") {
                MessageState(tr("Songs you download play here with no connection. Hold a song, or open an album’s ⋯, and choose Download."))
            }
        }
        itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
            val isCurrent = current?.videoId == song.videoId
            SongRow(
                song = song,
                onClick = { IosPlayer.playQueue(songs, index) },
                onLongPress = { IosMenus.song = SongMenuRequest(song) },
                onSwipeToQueue = { IosPlayer.swipe(song) },
                downloadedTint = null,
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                searchPlayingStyle = true,
                activeTint = PlayingAccent,
            )
        }
    }
}

/** The words a release's subtitle uses for what it is, which are not who made it. */
private val RELEASE_KINDS = setOf("album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video")

/** The window's height, for capping what a phone sizes from the width alone. */
@Composable
internal fun windowHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

/** As wide as it is offered and as tall, but never taller than [maxHeight]. */
private fun Modifier.squareUpTo(maxHeight: Dp) = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = minOf(width, maxHeight.roundToPx())
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
