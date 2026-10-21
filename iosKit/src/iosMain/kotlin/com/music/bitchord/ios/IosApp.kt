package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import com.music.bitchord.ui.components.BottomTab
import com.music.bitchord.ui.components.FloatingBottomBar
import com.music.bitchord.ui.components.MiniPlayer
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
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
import com.music.bitchord.data.settings.LastPlayerScreen
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
import com.music.bitchord.data.model.ShelfItem
import androidx.compose.material.icons.rounded.DownloadDone
import com.music.bitchord.ui.screens.LibraryLink
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
        // Lyrics follow whatever is playing, player open or not, and look again when a lyrics
        // setting changes so it applies to the track already playing.
        val durationMs by IosPlayer.durationMs.collectAsState()
        val syncedLyrics by IosSettings.syncedLyrics.flow.collectAsState()
        val lyricsSources by IosSettings.lyricsSources.flow.collectAsState()
        val lyricsOrder by IosSettings.lyricsSourceOrder.flow.collectAsState()
        LaunchedEffect(song?.videoId, durationMs > 0, syncedLyrics, lyricsSources, lyricsOrder) {
            song?.let { IosLyrics.load(it, durationMs) }
        }
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val hazeState = remember { HazeState() }
        val density = LocalDensity.current
        // The floating bars' measured height, so the last row of a page clears them.
        var barsHeight by remember { mutableIntStateOf(0) }
        val contentPadding = PaddingValues(bottom = with(density) { barsHeight.toDp() } + 8.dp)

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
            // The page is what the bars frost over.
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                val page = navigator.top
                if (page != null) {
                    IosPageHost(page, navigator, contentPadding, statusBar, onClearSearchHistory = search::clearHistory)
                } else {
                    // Root pages start under the top bar, not under the clock.
                    val pageTop = statusBar + TOP_BAR_HEIGHT
                    when (navigator.tab) {
                        IosTab.HOME -> HomePage(feeds, navigator, song, isPlaying, contentPadding, pageTop)
                        IosTab.EXPLORE -> ExplorePage(feeds, navigator, contentPadding, pageTop)
                        IosTab.LIBRARY -> LibraryPage(navigator, contentPadding, pageTop)
                        IosTab.SEARCH -> SearchPage(search, navigator, song, isPlaying, contentPadding)
                    }
                }
            }
            // What scrolls up fades out under the clock and the bar rather than running into them.
            val scrimHeight = statusBar + if (navigator.top == null && navigator.tab != IosTab.SEARCH) TOP_BAR_HEIGHT else 0.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(scrimHeight + 12.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black,
                            0.6f to Color.Black.copy(alpha = 0.85f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            // The phone's History and account pill, over every root tab but Search's field.
            if (navigator.top == null && navigator.tab != IosTab.SEARCH) {
                TopActions(
                    onOpenHistory = { navigator.open(IosPage.History) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = statusBar + (TOP_BAR_HEIGHT - 46.dp) / 2, end = 16.dp),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { barsHeight = it.height },
            ) {
                song?.let { current ->
                    val loading by IosPlayer.isLoading.collectAsState()
                    val party by com.music.bitchord.ios.party.IosListenTogether.state.collectAsState()
                    MiniPlayer(
                        song = current,
                        isPlaying = isPlaying,
                        isLoading = loading || party.awaitingStart,
                        controlsLocked = party.controlsLocked,
                        onBlockedControl = { IosPlayerHost.showMessage("Only the host can change what’s playing") },
                        hazeState = hazeState,
                        onPlayPause = IosPlayer::togglePlayPause,
                        onNext = IosPlayer::next,
                        onPrevious = IosPlayer::previous,
                        onExpand = { playerOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                FloatingBottomBar(
                    tabs = BottomTabs,
                    selectedIndex = navigator.tab.ordinal,
                    onTabSelected = { navigator.select(IosTab.entries[it]) },
                    hazeState = hazeState,
                )
            }
            AnimatedVisibility(
                visible = playerOpen && song != null,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(260)) { it },
            ) {
                IosNowPlaying(navigator, onDismiss = { playerOpen = false })
            }
            if (IosOverlays.switcher) {
                AccountSwitcher(
                    hazeState = hazeState,
                    onOpenSettings = { navigator.open(IosPage.Settings) },
                    onDismiss = { IosOverlays.switcher = false },
                )
            }
            AnimatedVisibility(
                visible = IosOverlays.login,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(260)) { it },
            ) {
                IosLoginScreen(onClose = {
                    IosOverlays.login = false
                    if (IosAccount.signedIn.value && navigator.top == null) navigator.select(IosTab.LIBRARY)
                })
            }
            IosDeepLink.invite?.let { invite ->
                LaunchedEffect(invite) {
                    playerOpen = false
                    navigator.open(IosPage.ListenTogether(invite.code, invite.serverUrl))
                    IosDeepLink.invite = null
                }
            }
            AnimatedVisibility(
                visible = IosOverlays.spotifyLogin,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(260)) { it },
            ) {
                IosSpotifyLoginScreen(onClose = { IosOverlays.spotifyLogin = false })
            }
            AnimatedVisibility(
                visible = IosOverlays.discordLogin,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(260)) { it },
            ) {
                IosDiscordLoginScreen(onClose = { IosOverlays.discordLogin = false })
            }
            if (IosOverlays.spotifyImport) SpotifyImportDialog(onDismiss = { IosOverlays.spotifyImport = false })
            IosReplay.story?.let { (summary, start) ->
                ReplayStories(
                    summary = summary,
                    start = start,
                    onClose = { IosReplay.story = null },
                    onShare = { shareLink(IosReplay.shareText(summary)) },
                )
            }
            // Above the pages and the player: a menu can be opened from either.
            IosMenuHost(navigator, onLeavePlayer = { playerOpen = false })
            IosToast.Host(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { barsHeight.toDp() } + 12.dp, start = 24.dp, end = 24.dp),
            )
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
    topPadding: Dp,
) {
    val state by feeds.home.collectAsState()
    val loadingMore by feeds.homeLoadingMore.collectAsState()
    val signedIn by IosAccount.signedIn.collectAsState()
    val recentsLoading by feeds.recentsLoading.collectAsState()
    HomeScreen(
        state = state,
        listState = rememberLazyListState(),
        onItemClick = { item, shelfTitle -> navigator.openShelfItem(item, shelfTitle) },
        onItemLongPress = { IosMenus.openShelfItem(it) },
        onRetry = feeds::loadHome,
        refreshing = false,
        onRefresh = feeds::loadHome,
        pullState = rememberPullToRefreshState(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
        title = "Listen Now",
        signedIn = signedIn,
        onSignIn = { IosOverlays.login = true },
        onLoadMore = feeds::loadMoreHome,
        loadingMore = loadingMore,
        recentlyPlayedLoading = recentsLoading,
        currentSong = song,
        isPlaying = isPlaying,
    )
}

@Composable
private fun ExplorePage(feeds: IosFeeds, navigator: IosNavigator, contentPadding: PaddingValues, topPadding: Dp) {
    val state by feeds.explore.collectAsState()
    ExploreScreen(
        state = state,
        listState = rememberLazyListState(),
        onCategoryClick = { navigator.open(IosPage.Category(it)) },
        onRetry = feeds::loadExplore,
        refreshing = false,
        onRefresh = feeds::loadExplore,
        pullState = rememberPullToRefreshState(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    )
}

@Composable
private fun LibraryPage(navigator: IosNavigator, contentPadding: PaddingValues, topPadding: Dp) {
    val signedIn by IosAccount.signedIn.collectAsState()
    val state by IosAccount.library.collectAsState()
    val releases by IosDownloads.releases.collectAsState()
    LibraryScreen(
        signedIn = signedIn,
        state = state,
        listState = rememberLazyListState(),
        onShelfItemClick = { navigator.openShelfItem(it, null) },
        onShelfItemLongPress = { IosMenus.openShelfItem(it) },
        onNewPlaylist = { if (signedIn) IosMenus.newPlaylist = emptyList() else IosOverlays.login = true },
        onImportSpotifyPlaylist = { if (signedIn) IosOverlays.spotifyImport = true else IosOverlays.login = true },
        onShowAll = { navigator.open(IosPage.LibraryShelf(it.title)) },
        replay = { LibraryReplayCards(navigator) },
        onSignIn = { IosOverlays.login = true },
        onRetry = IosAccount::loadLibrary,
        refreshing = false,
        onRefresh = IosAccount::loadLibrary,
        pullState = rememberPullToRefreshState(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
        links = listOf(
            LibraryLink(
                item = ShelfItem(
                    title = "Downloads",
                    subtitle = "Songs on this iPhone",
                    thumbnailUrl = null,
                    videoId = null,
                    browseId = DOWNLOADS_LINK,
                ),
                icon = Icons.Rounded.DownloadDone,
            ),
        ),
        deviceItems = releases.map { release ->
            ShelfItem(release.title, release.subtitle, release.thumbnailUrl, videoId = null, browseId = release.browseId)
        },
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
        onSongLongPress = { IosMenus.song = SongMenuRequest(it) },
        onSongSwipe = IosPlayer::swipe,
        onBrowseLongPress = { item ->
            IosMenus.browse = BrowseMenuRequest(item.browseId, item.title, item.subtitle, item.thumbnailUrl)
        },
        onTypeaheadLongPress = { IosMenus.song = SongMenuRequest(it) },
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

private val IosTab.label: String
    get() = when (this) {
        IosTab.HOME -> "Home"
        IosTab.EXPLORE -> "Explore"
        IosTab.LIBRARY -> "Library"
        IosTab.SEARCH -> "Search"
    }

private val IosTab.icon: ImageVector
    get() = when (this) {
        IosTab.HOME -> BitChordIcons.Home
        IosTab.EXPLORE -> BitChordIcons.TabExplore
        IosTab.LIBRARY -> BitChordIcons.TabLibrary
        IosTab.SEARCH -> BitChordIcons.TabSearch
    }

private val BottomTabs = IosTab.entries.map { BottomTab(it.label, it.icon) }

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
    IosPlayer.song.value?.let { IosMenus.song = SongMenuRequest(it) }
    step("menu")
    delay(TOUR_STEP_MS)
    IosMenus.song = null
    setPlayerOpen(false)
    // The player reopens on the screen it was last left on, so this opens it on the lyrics.
    IosPlayerSettings.setLastPlayerScreen(LastPlayerScreen.LYRICS)
    delay(500)
    setPlayerOpen(true)
    step("lyrics ${IosLyrics.source.value?.label} ${IosLyrics.lyrics.value?.size} lines, checked=${IosLyrics.checked.value}")
    delay(TOUR_STEP_MS)
    println("tour: lyrics after wait ${IosLyrics.source.value?.label} ${IosLyrics.lyrics.value?.size} lines")
    setPlayerOpen(false)
    IosPlayerSettings.setLastPlayerScreen(LastPlayerScreen.MAIN)
    IosOverlays.login = true
    step("login")
    delay(TOUR_STEP_MS)
    IosOverlays.login = false
    navigator.open(IosPage.Settings)
    step("settings")
    delay(TOUR_STEP_MS)
    navigator.back()
    navigator.select(IosTab.SEARCH)
    search.submit(autoSearchQuery() ?: "daft punk")
    step("search")
}

private const val TOUR_STEP_MS = 10_000L

/** The top bar the History and account pill sit in, above each root page's big title. */
private val TOP_BAR_HEIGHT = 52.dp
