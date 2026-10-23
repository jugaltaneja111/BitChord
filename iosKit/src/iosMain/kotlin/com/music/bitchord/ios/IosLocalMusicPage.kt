package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.SearchField

/** How the local songs are listed; stored by name. */
internal enum class LocalMusicSort(val label: String) {
    TITLE(tr("Title")),
    ARTIST(tr("Artist")),
    ALBUM(tr("Album")),
    RECENT(tr("Recently added")),
}

private enum class LocalMusicTab(val label: String) { SONGS(tr("Songs")), ARTISTS(tr("Artists")), ALBUMS(tr("Albums")) }

private const val LOCAL_SOURCE = "Local music"

private fun List<Song>.playable() = map { it.copy(playbackSource = LOCAL_SOURCE, playbackSourceType = PlaybackSourceType.QUEUE) }

private fun List<Song>.sortedFor(sort: LocalMusicSort): List<Song> = when (sort) {
    LocalMusicSort.TITLE -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Song::title))
    LocalMusicSort.ARTIST -> sortedWith(
        compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.albumName.orEmpty() }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER, Song::title),
    )
    LocalMusicSort.ALBUM -> sortedWith(
        compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.albumName ?: "￿" }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER, Song::title),
    )
    LocalMusicSort.RECENT -> sortedWith(
        compareByDescending<Song> { it.localDateAddedSeconds ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER, Song::title),
    )
}

private fun Song.matches(words: String): Boolean =
    title.contains(words, ignoreCase = true) || artist.contains(words, ignoreCase = true) ||
        albumName?.contains(words, ignoreCase = true) == true

/** The local songs [query] finds, in the page's order: for the Library source on the search page too. */
internal fun localSongsMatching(songs: List<Song>, query: String): List<Song> {
    val words = query.trim()
    return if (words.isEmpty()) songs else songs.filter { it.matches(words) }
}

/**
 * The phone's Local Music page, laid out like Downloads: the songs found in BitChord's folder in
 * Files and in the folder picked in Settings, searched and sorted, or grouped by artist or album.
 * A tap plays the list as it stands, from that song.
 */
@Composable
internal fun LocalMusicPage(navigator: IosNavigator, contentPadding: PaddingValues, topPadding: Dp) {
    val all by IosLocalMusic.songs.collectAsState()
    val scanning by IosLocalMusic.scanning.collectAsState()
    val sortName by IosSettings.localMusicSort.flow.collectAsState()
    val sort = LocalMusicSort.entries.firstOrNull { it.name == sortName } ?: LocalMusicSort.TITLE
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(LocalMusicTab.SONGS) }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val songs = remember(all, query, sort) { localSongsMatching(all, query).sortedFor(sort).playable() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(LOCAL_SOURCE, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    buildString {
                        append("${all.size} ${if (all.size == 1) "song" else "songs"} · On this iPhone")
                        if (scanning) append(" · Scanning…")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (all.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    PlayControls(
                        onPlay = { IosPlayer.playQueue(songs, 0) },
                        onShuffle = { IosPlayer.playShuffled(songs) },
                    )
                }
            }
        }
        if (all.isEmpty()) {
            item(key = "empty") {
                if (scanning) {
                    PageSpinner()
                } else {
                    MessageState(
                        tr("Play your own audio files. Choose a folder from the Files app, or copy songs into On My iPhone › BitChord › Music in Files. Settings › Local music has both."),
                        actionLabel = tr("Choose a folder"),
                        onAction = IosLocalMusic::chooseFolder,
                    )
                }
            }
            return@LazyColumn
        }
        item(key = "search") {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSubmit = {},
                placeholder = tr("Songs, artists and albums"),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        item(key = "tabs") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TabSwitch(tab, onSelect = { tab = it }, modifier = Modifier.weight(1f))
                if (tab == LocalMusicTab.SONGS) {
                    Spacer(Modifier.width(10.dp))
                    SortButton(sort)
                }
            }
        }
        when (tab) {
            LocalMusicTab.SONGS -> {
                if (songs.isEmpty()) item(key = "none") { MessageState(tr("Nothing here matches “{0}”", query.trim())) }
                itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
                    val isCurrent = current?.videoId == song.videoId
                    SongRow(song, isCurrent, isCurrent && isPlaying) { IosPlayer.playQueue(songs, index) }
                }
            }
            LocalMusicTab.ARTISTS -> {
                val artists = songs.groupBy { it.artist }.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                if (artists.isEmpty()) item(key = "none") { MessageState(tr("Nothing here matches “{0}”", query.trim())) }
                items(artists, key = { "artist:${it.key}" }) { (artist, tracks) ->
                    GroupRow(
                        title = artist,
                        subtitle = "${tracks.size} ${if (tracks.size == 1) "song" else "songs"}",
                        imageUrl = tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        round = true,
                    ) { navigator.open(IosPage.LocalMusicGroup(album = false, name = artist)) }
                }
            }
            LocalMusicTab.ALBUMS -> {
                val albums = songs.filter { it.albumName != null }.groupBy { it.albumName!! }.entries
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                if (albums.isEmpty()) item(key = "none") { MessageState(tr("No albums: these files’ tags don’t name one")) }
                items(albums, key = { "album:${it.key}" }) { (album, tracks) ->
                    val artists = tracks.map { it.artist }.distinct()
                    GroupRow(
                        title = album,
                        subtitle = if (artists.size == 1) artists.first() else tr("Various artists"),
                        imageUrl = tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        round = false,
                    ) { navigator.open(IosPage.LocalMusicGroup(album = true, name = album)) }
                }
            }
        }
    }
}

/** One artist's local songs, by album and track, or one album's in its running order. */
@Composable
internal fun LocalMusicGroupPage(page: IosPage.LocalMusicGroup, contentPadding: PaddingValues, topPadding: Dp) {
    val all by IosLocalMusic.songs.collectAsState()
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val songs = remember(all, page) {
        if (page.album) {
            all.filter { it.albumName == page.name }.sortedFor(LocalMusicSort.ALBUM)
        } else {
            all.filter { it.artist == page.name }.sortedFor(LocalMusicSort.ARTIST)
        }.playable()
    }
    val artists = songs.map { it.artist }.distinct()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(page.name, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    buildString {
                        if (page.album) append(if (artists.size == 1) "${artists.first()} · " else tr("Various artists · "))
                        append(tr("{0} · On this iPhone", trPlural(songs.size, "{0} song", "{0} songs")))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    PlayControls(onPlay = { IosPlayer.playQueue(songs, 0) }, onShuffle = { IosPlayer.playShuffled(songs) })
                }
            }
        }
        if (songs.isEmpty()) item(key = "gone") { MessageState(tr("These songs are no longer on this iPhone")) }
        itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
            val isCurrent = current?.videoId == song.videoId
            SongRow(song, isCurrent, isCurrent && isPlaying, trackNumber = song.trackNumber.takeIf { page.album }) {
                IosPlayer.playQueue(songs, index)
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, isCurrent: Boolean, isPlaying: Boolean, trackNumber: Int? = null, onClick: () -> Unit) {
    com.music.bitchord.ui.components.SongRow(
        song = song,
        onClick = onClick,
        onLongPress = { IosMenus.song = SongMenuRequest(song) },
        onSwipeToQueue = { IosPlayer.swipe(song) },
        trackNumber = trackNumber,
        downloadedTint = null,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        searchPlayingStyle = true,
        activeTint = PlayingAccent,
    )
}

/** Songs, Artists, Albums, in the search page's segmented style. */
@Composable
private fun TabSwitch(selected: LocalMusicTab, onSelect: (LocalMusicTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LocalMusicTab.entries.forEach { tab ->
            val chosen = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { if (!chosen) onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** The order the Songs tab lists in, kept between visits. */
@Composable
private fun SortButton(sort: LocalMusicSort) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sort.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = tr("Sort"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LocalMusicSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = if (option == sort) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        IosSettings.localMusicSort.set(option.name)
                    },
                )
            }
        }
    }
}

/** An artist or album row: its cover (an artist's is round), name and a line under it. */
@Composable
private fun GroupRow(title: String, subtitle: String, imageUrl: String?, round: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp)).background(PlaceholderColor),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
