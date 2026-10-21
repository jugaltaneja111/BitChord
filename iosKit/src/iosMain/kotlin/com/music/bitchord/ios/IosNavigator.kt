package com.music.bitchord.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

/** The phone's four tabs, in its order. */
internal enum class IosTab { HOME, EXPLORE, LIBRARY, SEARCH }

/** A page opened on top of a tab. */
internal sealed interface IosPage {
    /** An album or playlist; [fallback] is what the card that opened it already knew. */
    data class Collection(val browseId: String, val fallback: BrowseItem? = null) : IosPage
    data class Artist(val browseId: String, val name: String) : IosPage
    data class Category(val item: MoodGenre) : IosPage
    /** What the signed-in account has been listening to, most recent first. */
    /** A Library shelf's Show all: every card on it, read live so an edit lands here too. */
    data class LibraryShelf(val title: String) : IosPage
    /** Replay, scrolled to the chart [landing] names. */
    data class Replay(val landing: ReplayStoryPage = ReplayStoryPage.INTRO) : IosPage
    data object History : IosPage
    /** The tracks kept on the phone. */
    data object Downloads : IosPage
    data object Settings : IosPage
    data object LyricsSources : IosPage
    data object Equalizer : IosPage
    data object Integrations : IosPage
    data object Spotify : IosPage
    data object Discord : IosPage
    data class SpotifyPlaylist(val id: String, val title: String, val imageUrl: String?) : IosPage
}

/**
 * Which tab is showing and the pages opened on each, as the phone keeps them: every tab has its
 * own stack, and tapping the tab already showing goes back to its root.
 */
internal class IosNavigator {
    var tab by mutableStateOf(IosTab.HOME)
        private set

    private val stacks = IosTab.entries.associateWith { mutableStateListOf<IosPage>() }

    /** The page on top of the current tab, or null for the tab's own page. */
    val top: IosPage? get() = stacks.getValue(tab).lastOrNull()

    fun select(target: IosTab) {
        if (target == tab) stacks.getValue(target).clear() else tab = target
    }

    fun open(page: IosPage) {
        val stack = stacks.getValue(tab)
        if (stack.lastOrNull() != page) stack.add(page)
    }

    /** False when the tab is already at its root. */
    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun openBrowse(item: BrowseItem) = open(
        if (item.type == BrowseType.ARTIST || browseTypeOf(item.browseId) == BrowseType.ARTIST) {
            IosPage.Artist(item.browseId, item.title)
        } else {
            IosPage.Collection(item.browseId, item)
        },
    )

    /** A card on Home, Explore or an artist's page: a track plays, anything else opens. */
    fun openShelfItem(item: ShelfItem, shelfTitle: String?) {
        val videoId = item.videoId
        val browseId = item.browseId
        when {
            browseId == DOWNLOADS_LINK -> open(IosPage.Downloads)
            videoId != null -> IosPlayer.playRadio(item.toSong(shelfTitle))
            browseId != null -> openBrowse(
                BrowseItem(
                    browseId = browseId,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    type = browseTypeOf(browseId),
                ),
            )
        }
    }
}

/** Library's Downloads entry, under the phone's own id for it. */
internal const val DOWNLOADS_LINK = "local:downloads"

/** What a browse id says it is, as the desktop reads it (DesktopSearchClient). */
internal fun browseTypeOf(browseId: String): BrowseType = when {
    browseId.startsWith("MPREb_") || browseId.startsWith("OLAK5uy_") -> BrowseType.ALBUM
    browseId.startsWith("VL") -> BrowseType.PLAYLIST
    browseId.startsWith("UC") -> BrowseType.ARTIST
    else -> BrowseType.OTHER
}

/** A track card as a song: the artist is the first part of the subtitle that is not a type or a tally. */
internal fun ShelfItem.toSong(shelfTitle: String? = null): Song {
    val parts = subtitle.split(" • ", " · ", " | ").filter(String::isNotBlank)
    val artist = parts.firstOrNull {
        !it.matches(SHELF_DURATION) && it.lowercase() !in SHELF_TYPE_WORDS && !it.matches(SHELF_TALLY)
    } ?: "Unknown Artist"
    return Song(
        videoId = videoId.orEmpty(),
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = parts.firstOrNull { it.matches(SHELF_DURATION) },
        playbackSource = shelfTitle,
        playbackSourceType = shelfTitle?.let { PlaybackSourceType.HOME },
    )
}

private val SHELF_DURATION = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
private val SHELF_TALLY = Regex("[0-9,.]+\\s*(?:views?|likes?|songs?)", RegexOption.IGNORE_CASE)
private val SHELF_TYPE_WORDS = setOf("song", "video", "album", "ep", "single", "playlist", "mix")
