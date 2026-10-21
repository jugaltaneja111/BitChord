package com.music.bitchord.ios

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ReplayCardRowSkeleton
import kotlinx.coroutines.launch

/** The Replay stories, over everything while they play. */
internal object IosReplay {
    var story by mutableStateOf<Pair<ReplaySummary, ReplayStoryPage>?>(null)

    /** What the share sheet sends for a Replay: its headline numbers, as text. */
    fun shareText(summary: ReplaySummary): String = buildString {
        append("My BitChord Replay · ${summary.localizedLabel()}\n")
        append("${formatMinutes(summary.totalMs)} minutes · ${replayCount(summary.totalPlays, "replay_play_count")}\n")
        summary.songs.firstOrNull()?.let { append("Top song: ${it.song.title} · ${it.song.artist}\n") }
        summary.artists.firstOrNull()?.let { append("Top artist: ${it.title}\n") }
        summary.albums.firstOrNull()?.let { append("Top album: ${it.title}\n") }
    }.trim()
}

/** Library's row of Replay cards, each opening Replay at the chart it sums up. */
@Composable
internal fun LibraryReplayCards(navigator: IosNavigator) {
    val (state, _) = rememberReplayState(active = true)
    val account by IosAccount.account.collectAsState()
    val summary = state.summary
    when {
        state.loading -> ReplayCardRowSkeleton()
        summary != null && !summary.isEmpty -> ReplayCardRow(
            cards = summary.cards(),
            holder = account?.name.orEmpty(),
            memberSince = state.memberSince,
            onCardClick = { page -> navigator.open(IosPage.Replay(page)) },
            modifier = Modifier.padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        )
    }
}

/** The whole Replay: period picker, cards, charts and habits, as the phone's page. */
@Composable
internal fun ReplayPage(page: IosPage.Replay, navigator: IosNavigator, contentPadding: PaddingValues, topInset: Dp) {
    val (state, setPeriod) = rememberReplayState(active = true)
    val account by IosAccount.account.collectAsState()
    val scope = rememberCoroutineScope()
    ReplayScreen(
        state = state,
        holder = account?.name.orEmpty(),
        onPeriodChange = setPeriod,
        onOpenStory = { start -> state.summary?.takeIf { !it.isEmpty }?.let { IosReplay.story = it to start } },
        onPlaySong = { IosPlayer.playRadio(it) },
        onOpenArtist = { id, name ->
            if (id != null) {
                navigator.open(IosPage.Artist(id, name))
            } else {
                scope.launch {
                    YtMusicRepository.findArtistPageId(name).getOrNull()?.let { navigator.open(IosPage.Artist(it, name)) }
                }
            }
        },
        onOpenAlbum = { id, title, subtitle, artwork ->
            if (id != null) {
                navigator.openBrowse(BrowseItem(browseId = id, title = title, subtitle = subtitle.orEmpty(), thumbnailUrl = artwork, type = BrowseType.ALBUM))
            }
        },
        onShare = { state.summary?.let { shareLink(IosReplay.shareText(it)) } },
        contentPadding = PaddingValues(top = topInset, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        landingPage = page.landing,
    )
}
