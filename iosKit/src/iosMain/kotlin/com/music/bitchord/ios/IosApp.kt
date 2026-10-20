package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.PlayerBack
import com.music.bitchord.ui.screens.ExploreScreen
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.screens.LibraryScreen
import kotlin.math.abs
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.screens.SearchScreen

/** The phone's dark scheme (app/…/ui/theme/Theme.kt); iOS draws in its own system face, SF Pro. */
private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
)

/** The phone's type scale, in the system face. */
private val BitChordTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp),
)

private val MiniPlayerHeight = 64.dp
private val TabBarHeight = 56.dp

/** The phone's shell: four tabs, the pages opened on them, the mini player and the full player. */
@Composable
internal fun IosApp() {
    MaterialTheme(colorScheme = DarkColors, typography = BitChordTypography) {
        val navigator = remember { IosNavigator() }
        val search = remember { IosSearch() }
        val feeds = remember { IosFeeds() }
        var playerOpen by remember { mutableStateOf(false) }
        val song by IosPlayer.song.collectAsState()
        val isPlaying by IosPlayer.isPlaying.collectAsState()
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val chrome = navigationBar + TabBarHeight + if (song != null) MiniPlayerHeight + 8.dp else 0.dp
        val contentPadding = PaddingValues(bottom = chrome + 8.dp)

        fun back() {
            if (playerOpen) {
                if (!PlayerBack.dispatch()) playerOpen = false
            } else {
                navigator.back()
            }
        }

        LaunchedEffect(Unit) {
            autoSearchQuery()?.takeUnless { tourRequested() }?.let { query ->
                navigator.select(IosTab.SEARCH)
                search.submit(query)
            }
        }
        if (tourRequested()) {
            LaunchedEffect(Unit) { runTour(navigator, search, feeds, setPlayerOpen = { playerOpen = it }) }
        }

        Box(Modifier.fillMaxSize().background(Color.Black).edgeSwipeBack(::back)) {
            val page = navigator.top
            if (page != null) {
                IosPageHost(page, navigator, contentPadding, statusBar)
            } else {
                when (navigator.tab) {
                    IosTab.HOME -> HomePage(feeds, navigator, song, isPlaying, contentPadding, statusBar)
                    IosTab.EXPLORE -> ExplorePage(feeds, navigator, contentPadding, statusBar)
                    IosTab.SEARCH -> SearchPage(search, navigator, song, isPlaying, contentPadding)
                    IosTab.LIBRARY -> LibraryPage(contentPadding, statusBar)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                song?.let { current ->
                    MiniPlayer(
                        song = current,
                        onOpen = { playerOpen = true },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    )
                }
                TabBar(navigator.tab, onSelect = navigator::select, bottomInset = navigationBar)
            }
            AnimatedVisibility(
                visible = playerOpen && song != null,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(260)) { it },
            ) {
                IosNowPlaying(navigator, onDismiss = { playerOpen = false })
            }
        }
    }
}

@Composable
private fun HomePage(
    feeds: IosFeeds,
    navigator: IosNavigator,
    song: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    statusBar: Dp,
) {
    val state by feeds.home.collectAsState()
    val loadingMore by feeds.homeLoadingMore.collectAsState()
    HomeScreen(
        state = state,
        listState = rememberLazyListState(),
        onItemClick = { item, shelfTitle -> navigator.openShelfItem(item, shelfTitle) },
        onRetry = feeds::loadHome,
        refreshing = false,
        onRefresh = feeds::loadHome,
        pullState = rememberPullToRefreshState(),
        contentPadding = PaddingValues(top = statusBar + 8.dp, bottom = contentPadding.calculateBottomPadding()),
        title = "Home",
        signedIn = false,
        onSignIn = null,
        onLoadMore = feeds::loadMoreHome,
        loadingMore = loadingMore,
        currentSong = song,
        isPlaying = isPlaying,
    )
}

@Composable
private fun ExplorePage(feeds: IosFeeds, navigator: IosNavigator, contentPadding: PaddingValues, statusBar: Dp) {
    val state by feeds.explore.collectAsState()
    ExploreScreen(
        state = state,
        listState = rememberLazyListState(),
        onCategoryClick = { navigator.open(IosPage.Category(it)) },
        onRetry = feeds::loadExplore,
        refreshing = false,
        onRefresh = feeds::loadExplore,
        pullState = rememberPullToRefreshState(),
        contentPadding = PaddingValues(top = statusBar + 8.dp, bottom = contentPadding.calculateBottomPadding()),
    )
}

/** Signed out for now: accounts come with a later step of the port. */
@Composable
private fun LibraryPage(contentPadding: PaddingValues, statusBar: Dp) {
    LibraryScreen(
        signedIn = false,
        state = UiState.Loading,
        listState = rememberLazyListState(),
        onShelfItemClick = {},
        onShelfItemLongPress = {},
        onNewPlaylist = { IosPlayerHost.showMessage("Playlists need an account") },
        onShowAll = {},
        replay = {},
        onSignIn = { IosPlayerHost.showMessage("Sign-in is not in the iOS app yet") },
        onRetry = {},
        refreshing = false,
        onRefresh = {},
        pullState = rememberPullToRefreshState(),
        contentPadding = PaddingValues(top = statusBar + 8.dp, bottom = contentPadding.calculateBottomPadding()),
        links = emptyList(),
        deviceItems = emptyList(),
    )
}

@Composable
private fun SearchPage(
    search: IosSearch,
    navigator: IosNavigator,
    song: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
) {
    val query by search.query.collectAsState()
    val filter by search.filter.collectAsState()
    val results by search.results.collectAsState()
    val loadingMore by search.loadingMore.collectAsState()
    val suggestions by search.suggestions.collectAsState()
    val typeahead by search.typeahead.collectAsState()
    val history by search.history.collectAsState()
    // The keyboard comes up on an empty page, not over results already there.
    var focusRequested by remember { mutableStateOf(query.isBlank()) }
    val listState = rememberLazyListState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    fun play(song: Song) {
        search.record(
            SearchHistoryEntity(
                id = song.videoId,
                title = song.title,
                subtitle = song.artist,
                artworkUrl = song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ),
        )
        IosPlayer.playRadio(song.copy(playbackSource = query, playbackSourceType = PlaybackSourceType.SEARCH))
    }

    fun open(item: BrowseItem) {
        search.record(
            SearchHistoryEntity(
                id = item.browseId,
                title = item.title,
                subtitle = item.subtitle,
                artworkUrl = item.thumbnailUrl,
                entityType = when (item.type) {
                    BrowseType.ALBUM -> EntityType.ALBUM
                    BrowseType.ARTIST -> EntityType.ARTIST
                    else -> EntityType.PLAYLIST
                },
            ),
        )
        navigator.openBrowse(item)
    }

    SearchScreen(
        query = query,
        onQueryChange = search::onQueryChange,
        filter = filter,
        currentSong = song,
        isPlaying = isPlaying,
        onFilterChange = search::onFilterChange,
        results = results,
        loadingMore = loadingMore,
        onLoadMore = search::loadMore,
        listState = listState,
        scrollResetTrigger = 0,
        focusRequested = focusRequested,
        onFocusHandled = { focusRequested = false },
        onSongClick = { songs, index -> songs.getOrNull(index)?.let(::play) },
        onSongLongPress = {},
        onSongSwipe = {},
        onTopResultPlay = ::play,
        onTopResultPlaylist = ::play,
        onBrowseClick = ::open,
        history = history,
        suggestions = suggestions,
        typeaheadResults = typeahead,
        onSubmit = { search.submit() },
        onSuggestionClick = { search.submit(it) },
        // A past search is opened or played again as it was, not searched for by its name.
        onHistoryClick = { entity ->
            when (entity.entityType) {
                EntityType.TRACK -> IosPlayer.playRadio(
                    Song(videoId = entity.id, title = entity.title, artist = entity.subtitle, thumbnailUrl = entity.artworkUrl),
                )
                EntityType.ARTIST -> navigator.open(IosPage.Artist(entity.id, entity.title))
                EntityType.ALBUM, EntityType.PLAYLIST -> navigator.open(
                    IosPage.Collection(
                        entity.id,
                        BrowseItem(entity.id, entity.title, entity.subtitle, entity.artworkUrl, browseTypeOf(entity.id)),
                    ),
                )
            }
        },
        onHistoryRemove = search::removeHistory,
        onHistoryClear = search::clearHistory,
        contentPadding = contentPadding,
        topPadding = statusBar + 8.dp,
    )
}

@Composable
private fun TabBar(selected: IosTab, onSelect: (IosTab) -> Unit, bottomInset: Dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF20D0D0F))
            .padding(bottom = bottomInset)
            .height(TabBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IosTab.entries.forEach { tab ->
            val active = tab == selected
            val tint = if (active) Color.White else Color(0xFF8E8E93)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = null, indication = null) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(3.dp))
                Text(tab.label, color = tint, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val IosTab.label: String
    get() = when (this) {
        IosTab.HOME -> "Home"
        IosTab.EXPLORE -> "Explore"
        IosTab.SEARCH -> "Search"
        IosTab.LIBRARY -> "Library"
    }

private val IosTab.icon: ImageVector
    get() = when (this) {
        IosTab.HOME -> BitChordIcons.Home
        IosTab.EXPLORE -> BitChordIcons.TabExplore
        IosTab.SEARCH -> BitChordIcons.TabSearch
        IosTab.LIBRARY -> BitChordIcons.TabLibrary
    }

/**
 * iOS's back gesture: a drag that starts at the left edge and travels right. Watched before the
 * page sees it, and only taken once it is clearly that, so rows and shelves still scroll.
 */
private fun Modifier.edgeSwipeBack(onBack: () -> Unit): Modifier = pointerInput(Unit) {
    val edge = 24.dp.toPx()
    val distance = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edge) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            dx += change.position.x - change.previousPosition.x
            dy += change.position.y - change.previousPosition.y
            if (abs(dy) > abs(dx) * 1.5f && abs(dy) > distance / 3) break
            if (dx > distance && dx > abs(dy) * 2) {
                change.consume()
                onBack()
                // The rest of this drag is spent.
                while (event.changes.any { it.pressed }) {
                    val rest = awaitPointerEvent(PointerEventPass.Initial)
                    rest.changes.forEach { it.consume() }
                    if (rest.changes.none { it.pressed }) break
                }
                break
            }
        }
    }
}

/** See [tourRequested]. */
private suspend fun runTour(
    navigator: IosNavigator,
    search: IosSearch,
    feeds: IosFeeds,
    setPlayerOpen: (Boolean) -> Unit,
) {
    fun step(name: String) = println("tour: $name")
    step("home")
    delay(TOUR_STEP_MS)
    navigator.select(IosTab.EXPLORE)
    step("explore")
    delay(TOUR_STEP_MS)
    val category = (feeds.explore.value as? UiState.Success)?.data?.firstOrNull()?.items?.firstOrNull()
    if (category != null) navigator.open(IosPage.Category(category))
    step("category ${category?.title}")
    delay(TOUR_STEP_MS)
    val album = YtMusicRepository.search("daft punk discovery", SearchFilter.ALBUMS).getOrNull()
        ?.filterIsInstance<SearchResult.Browse>()?.firstOrNull()?.item
    navigator.select(IosTab.HOME)
    if (album != null) navigator.openBrowse(album)
    step("album ${album?.title}")
    delay(TOUR_STEP_MS)
    val artist = YtMusicRepository.findArtistPageId("Daft Punk").getOrNull()
    if (artist != null) navigator.open(IosPage.Artist(artist, "Daft Punk"))
    step("artist $artist")
    delay(TOUR_STEP_MS)
    setPlayerOpen(true)
    step("player")
    delay(TOUR_STEP_MS)
    setPlayerOpen(false)
    navigator.select(IosTab.SEARCH)
    search.submit(autoSearchQuery() ?: "daft punk")
    step("search")
}

private const val TOUR_STEP_MS = 10_000L

@Composable
private fun MiniPlayer(song: Song, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val isLoading by IosPlayer.isLoading.collectAsState()
    val error by IosPlayer.error.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                error ?: song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) Color(0xFFFF6B6B) else Color(0xFF8E8E93),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = IosPlayer::togglePlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }
        }
        IconButton(onClick = IosPlayer::next) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White)
        }
    }
}
