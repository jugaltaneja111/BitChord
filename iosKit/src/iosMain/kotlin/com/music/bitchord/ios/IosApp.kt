package com.music.bitchord.ios

import androidx.compose.foundation.background
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

/** Search, and the track playing under it. The rest of the phone's tabs follow. */
@Composable
internal fun IosApp() {
    MaterialTheme(colorScheme = DarkColors, typography = BitChordTypography) {
        val search = remember { IosSearch() }
        val song by IosPlayer.song.collectAsState()
        val isPlaying by IosPlayer.isPlaying.collectAsState()
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottom = navigationBar + if (song != null) MiniPlayerHeight + 16.dp else 0.dp
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SearchPage(search, song, isPlaying, PaddingValues(bottom = bottom))
            song?.let { current ->
                MiniPlayer(
                    song = current,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navigationBar + 8.dp, start = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchPage(search: IosSearch, song: Song?, isPlaying: Boolean, contentPadding: PaddingValues) {
    val query by search.query.collectAsState()
    val filter by search.filter.collectAsState()
    val results by search.results.collectAsState()
    val loadingMore by search.loadingMore.collectAsState()
    val suggestions by search.suggestions.collectAsState()
    val typeahead by search.typeahead.collectAsState()
    val history by search.history.collectAsState()
    var focusRequested by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LaunchedEffect(Unit) {
        autoSearchQuery()?.let { query ->
            focusRequested = false
            search.submit(query)
        }
    }

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
        IosPlayer.playRadio(song)
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
        onTopResultPlaylist = {},
        // Albums, artists and playlists open pages the iOS app does not have yet.
        onBrowseClick = {},
        history = history,
        suggestions = suggestions,
        typeaheadResults = typeahead,
        onSubmit = { search.submit() },
        onSuggestionClick = { search.submit(it) },
        onHistoryClick = { entity -> search.submit(entity.title) },
        onHistoryRemove = search::removeHistory,
        onHistoryClear = search::clearHistory,
        contentPadding = contentPadding,
        topPadding = statusBar + 8.dp,
    )
}

@Composable
private fun MiniPlayer(song: Song, modifier: Modifier = Modifier) {
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val isLoading by IosPlayer.isLoading.collectAsState()
    val error by IosPlayer.error.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = IosPlayer::togglePlayPause)
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
