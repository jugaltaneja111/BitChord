package com.music.bitchord.ios

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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Pages with no artwork header of their own start under the back button.
        val underBack = topInset + 52.dp
        when (page) {
            is IosPage.Collection -> CollectionPage(page, contentPadding, topInset)
            is IosPage.Artist -> ArtistPageView(page, navigator, contentPadding, topInset)
            is IosPage.Category -> CategoryPage(page, navigator, contentPadding, topInset)
            is IosPage.LibraryShelf -> LibraryShelfPage(page, navigator, contentPadding, topInset)
            is IosPage.Replay -> ReplayPage(page, navigator, contentPadding, topInset)
            IosPage.History -> HistoryPage(contentPadding, underBack)
            IosPage.Downloads -> DownloadsPage(contentPadding, underBack)
            IosPage.Settings -> SettingsPage(contentPadding, underBack, onClearSearchHistory, onOpenLyricsSources = { navigator.open(IosPage.LyricsSources) }, onOpenDownloads = { navigator.open(IosPage.Downloads) })
            IosPage.LyricsSources -> LyricsSourcesPage(contentPadding, underBack)
        }
        BackButton(onClick = { navigator.back() }, modifier = Modifier.padding(top = topInset + 4.dp, start = 8.dp))
    }
}

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                    UiState.Error(error.message ?: "Could not open this page")
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

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            CollectionHeader(
                title = title,
                subtitle = loaded?.subtitle ?: page.fallback?.subtitle.orEmpty(),
                thumbnailUrl = loaded?.thumbnailUrl ?: page.fallback?.thumbnailUrl,
                typeLabel = when (type) {
                    BrowseType.ALBUM -> "Album"
                    BrowseType.PLAYLIST -> "Playlist"
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
                MessageState(s.message, actionLabel = "Retry", onAction = { reloads++ })
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
                    item(key = "empty") { MessageState("Nothing to play on this page") }
                }
                if (loadingMore) item(key = "more") { PageSpinner() }
            }
        }
    }
}

@Composable
private fun CollectionHeader(
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
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            subtitle.takeIf(String::isNotBlank),
            count?.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" },
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
private fun PlayControls(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .clickable(onClick = onPlay)
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black)
            Spacer(Modifier.width(6.dp))
            Text("Play", color = Color.Black, style = MaterialTheme.typography.titleMedium)
        }
        CircleAction(BitChordIcons.Shuffle, "Shuffle", onShuffle)
        onMore?.let { CircleAction(Icons.Rounded.MoreHoriz, "More", it) }
    }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF1C1C1E)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
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
            onFailure = { state = UiState.Error(it.message ?: "Could not open ${page.name}") },
        )
    }
    val artist = (state as? UiState.Success)?.data
    val name = artist?.name?.takeIf(String::isNotBlank) ?: page.name
    val top = artist?.songs.orEmpty().take(MAX_TOP_SONGS).map { song ->
        song.copy(playbackSource = name, playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = page.browseId)
    }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "banner") { ArtistBanner(artist?.thumbnailUrl, name) }
        when (val s = state) {
            UiState.Loading -> songListSkeleton(count = 5)
            is UiState.Error -> item(key = "error") {
                MessageState(s.message, actionLabel = "Retry", onAction = { reloads++ })
            }
            is UiState.Success -> {
                val stats = listOfNotNull(
                    s.data.subscriberCountText?.let { "${it.substringBefore(' ')} subscribers" },
                    s.data.monthlyListenerCount?.let { "${it.substringBefore(' ')} monthly listeners" },
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
                    item(key = "top-title") { PageSectionTitle("Top songs") }
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
                            PageSectionTitle("About")
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
private fun ArtistBanner(url: String?, name: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF111113))) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black),
            ),
        )
        Text(
            name,
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
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
        color = Color.White,
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
                    state = UiState.Error("Nothing in ${item.title} right now")
                } else {
                    PageCache.put(key, shelves)
                    state = UiState.Success(shelves)
                }
            },
            onFailure = { state = UiState.Error(it.message ?: "Could not load this category") },
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
        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
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
            onFailure = { UiState.Error(it.message ?: "Could not load your history") },
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
                "History",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        when (val s = state) {
            UiState.Loading -> songListSkeleton(count = 10, keyPrefix = "skeleton:history")
            is UiState.Error -> item(key = "error") {
                MessageState(s.message, actionLabel = "Retry", onAction = { reloads++ })
            }
            is UiState.Success -> {
                val songs = s.data
                if (songs.isEmpty()) {
                    item(key = "empty") { MessageState("Nothing played on this account yet") }
                }
                itemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                    val isCurrent = current?.videoId == song.videoId
                    SongRow(
                        song = song,
                        onClick = {
                            IosPlayer.playQueue(
                                songs.map { it.copy(playbackSource = "History", playbackSourceType = PlaybackSourceType.HISTORY) },
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
    val shelf = (state as? UiState.Success)?.data?.shelves?.firstOrNull { it.title == page.title }
        ?: HomeShelf(page.title, emptyList())
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
            .background(Brush.verticalGradient(0.7f to Color.Black, 1f to Color.Transparent)),
    )
    Text(
        page.title,
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
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
        IosDownloads.song(track).copy(playbackSource = "Downloads", playbackSourceType = PlaybackSourceType.QUEUE)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Downloads", style = MaterialTheme.typography.displayLarge, color = Color.White)
                Text(
                    "${songs.size} ${if (songs.size == 1) "song" else "songs"} · ${(tracks.sumOf { it.bytes } / (1024 * 1024))} MB on this iPhone",
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
                    "Downloading",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
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
                                DownloadProgress.Queued -> "Waiting"
                                is DownloadProgress.Running -> "${(state.fraction * 100).toInt()}%"
                                is DownloadProgress.Failed -> "Failed"
                            },
                            color = if (state is DownloadProgress.Failed) Color(0xFFFF6B6B) else Color.White,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (state is DownloadProgress.Failed) "Dismiss" else "Cancel",
                            color = PlayingAccent,
                            modifier = Modifier.clickable { IosDownloads.cancel(videoId) },
                        )
                    }
                }
            }
        }
        if (songs.isEmpty() && progress.isEmpty()) {
            item(key = "empty") {
                MessageState("Songs you download play here with no connection. Hold a song, or open an album’s ⋯, and choose Download.")
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
