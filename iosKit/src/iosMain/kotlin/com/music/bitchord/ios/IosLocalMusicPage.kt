package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Favorite
import platform.Foundation.create
import platform.Foundation.stringByRemovingPercentEncoding
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

private enum class LocalMusicTab(val label: String) {
    SONGS(tr("Songs")),
    ARTISTS(tr("Artists")),
    ALBUMS(tr("Albums")),
    // v1.9's offline player: Liked and the playlists kept on this iPhone, and the files by folder.
    PLAYLISTS(tr("Playlists")),
    FOLDERS(tr("Folders")),
}

/**
 * The folder a file of the listener's sits in, as a key: the path under its root with the file
 * name off (`tag/Albums/Kind of Blue`), or the WebDAV URL's directory.
 */
internal fun folderOf(song: Song): String = when {
    IosLocalMusic.isLocal(song) -> song.videoId.removePrefix(LOCAL_SONG_PREFIX).substringBeforeLast('/', "")
    else -> song.videoId.substringAfter(':').substringBeforeLast('/', "")
}

/** A folder key's own name, as a row shows it: its last part, or "Music" for a root. */
internal fun folderLabel(folder: String): String {
    val last = folder.trimEnd('/').substringAfterLast('/')
    val decoded = runCatching { last.decodeUrlPart() }.getOrDefault(last)
    // A root is its tag alone (no slash under it): the Files folder or the picked one.
    return if ('/' !in folder || decoded.isBlank()) tr("Music") else decoded
}

private fun String.decodeUrlPart(): String =
    if ('%' in this) (platform.Foundation.NSString.create(string = this).stringByRemovingPercentEncoding ?: this) else this

private const val LOCAL_SOURCE = "Local music"

private const val WEBDAV_SOURCE = "WebDAV"

private fun List<Song>.playable(webdav: Boolean = false) =
    map { it.copy(playbackSource = if (webdav) WEBDAV_SOURCE else LOCAL_SOURCE, playbackSourceType = PlaybackSourceType.QUEUE) }

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
 *
 * With [webdav], the same page for the music folder on the listener's WebDAV server, as the phone
 * draws its WebDAV library with its Local Music view.
 */
@Composable
internal fun LocalMusicPage(navigator: IosNavigator, contentPadding: PaddingValues, topPadding: Dp, webdav: Boolean = false) {
    val all by (if (webdav) IosWebDav.songs else IosLocalMusic.songs).collectAsState()
    val scanning by (if (webdav) IosWebDav.loading else IosLocalMusic.scanning).collectAsState()
    val webdavError by IosWebDav.error.collectAsState()
    val webdavConfigured by IosWebDav.configured.collectAsState()
    val sortName by IosSettings.localMusicSort.flow.collectAsState()
    val sort = LocalMusicSort.entries.firstOrNull { it.name == sortName } ?: LocalMusicSort.TITLE
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(LocalMusicTab.SONGS) }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val songs = remember(all, query, sort) { localSongsMatching(all, query).sortedFor(sort).playable(webdav) }
    val localPlaylists by IosLocalPlaylists.playlists.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(if (webdav) WEBDAV_SOURCE else tr(LOCAL_SOURCE), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(trPlural(all.size, "{0} song", "{0} songs"))
                            append(if (webdav) tr(" · On your server") else tr(" · On this iPhone"))
                            if (scanning) append(tr(" · Scanning…"))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (webdav && webdavConfigured && !scanning) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("Refresh"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = IosWebDav::refresh).padding(4.dp),
                        )
                    }
                }
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
                } else if (webdav) {
                    if (!webdavConfigured) {
                        MessageState(
                            tr("Connect a server under Sources"),
                            actionLabel = tr("Sources"),
                            onAction = { navigator.open(IosPage.Sources) },
                        )
                    } else {
                        MessageState(
                            webdavError ?: tr("No audio files found on the WebDAV server. Check the URL in Settings."),
                            actionLabel = tr("Retry"),
                            onAction = IosWebDav::refresh,
                        )
                    }
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
                        subtitle = trPlural(tracks.size, "{0} song", "{0} songs"),
                        imageUrl = tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        round = true,
                    ) { navigator.open(IosPage.LocalMusicGroup(album = false, name = artist, webdav = webdav)) }
                }
            }
            LocalMusicTab.PLAYLISTS -> {
                item(key = "liked") { LikedRow(webdav, navigator) }
                val inLibrary = all.mapTo(HashSet()) { it.videoId }
                val playlists = localPlaylists.filter { list -> list.songs.any { it.videoId in inLibrary } }
                items(playlists, key = { "playlist:${it.id}" }) { playlist ->
                    GroupRow(
                        title = playlist.title,
                        subtitle = trPlural(playlist.songs.size, "{0} song", "{0} songs"),
                        imageUrl = playlist.thumbnailUrl,
                        round = false,
                    ) { navigator.open(IosPage.Collection(playlist.browseId)) }
                }
                if (playlists.isEmpty()) {
                    item(key = "no-playlists") {
                        MessageState(tr("No playlists yet. Make one from Add to playlist in any song’s menu."))
                    }
                }
            }
            LocalMusicTab.FOLDERS -> {
                val folders = songs.groupBy(::folderOf).entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { folderLabel(it.key) })
                if (folders.isEmpty()) item(key = "none") { MessageState(tr("Nothing here matches “{0}”", query.trim())) }
                items(folders, key = { "folder:${it.key}" }) { (folder, tracks) ->
                    GroupRow(
                        title = folderLabel(folder),
                        subtitle = trPlural(tracks.size, "{0} song", "{0} songs"),
                        imageUrl = tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        round = false,
                    ) { navigator.open(IosPage.LocalMusicGroup(album = false, name = folder, webdav = webdav, folder = true)) }
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
                    ) { navigator.open(IosPage.LocalMusicGroup(album = true, name = album, webdav = webdav)) }
                }
            }
        }
    }
}

/** One artist's local songs, by album and track, or one album's in its running order. */
@Composable
internal fun LocalMusicGroupPage(page: IosPage.LocalMusicGroup, contentPadding: PaddingValues, topPadding: Dp) {
    val all by (if (page.webdav) IosWebDav.songs else IosLocalMusic.songs).collectAsState()
    val liked by IosOfflineLikes.songs.collectAsState()
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val songs = remember(all, liked, page) {
        if (page.liked) {
            val here = all.mapTo(HashSet()) { it.videoId }
            liked.filter { it.videoId in here }
        } else if (page.folder) {
            all.filter { folderOf(it) == page.name }.sortedFor(LocalMusicSort.ALBUM)
        } else if (page.album) {
            all.filter { it.albumName == page.name }.sortedFor(LocalMusicSort.ALBUM)
        } else {
            all.filter { it.artist == page.name }.sortedFor(LocalMusicSort.ARTIST)
        }.playable(page.webdav)
    }
    val artists = songs.map { it.artist }.distinct()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    when {
                        page.liked -> tr("Liked")
                        page.folder -> folderLabel(page.name)
                        else -> page.name
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    buildString {
                        if (page.album) append(if (artists.size == 1) "${artists.first()} · " else tr("Various artists · "))
                        append(
                            if (page.webdav) tr("{0} · On your server", trPlural(songs.size, "{0} song", "{0} songs"))
                            else tr("{0} · On this iPhone", trPlural(songs.size, "{0} song", "{0} songs")),
                        )
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
        if (songs.isEmpty()) {
            item(key = "gone") {
                MessageState(
                    when {
                        page.liked -> tr("Songs you like on this device show up here")
                        page.webdav -> tr("These songs are no longer on your server")
                        else -> tr("These songs are no longer on this iPhone")
                    },
                )
            }
        }
        itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
            val isCurrent = current?.videoId == song.videoId
            SongRow(song, isCurrent, isCurrent && isPlaying, trackNumber = song.trackNumber.takeIf { page.album }) {
                IosPlayer.playQueue(songs, index)
            }
        }
    }
}

/** The offline Liked playlist's row, first on the Playlists tab: a heart for a cover. */
@Composable
private fun LikedRow(webdav: Boolean, navigator: IosNavigator) {
    val liked by IosOfflineLikes.songs.collectAsState()
    val count = liked.count { if (webdav) IosWebDav.isWebDav(it) else IosLocalMusic.isLocal(it) }
    Row(
        Modifier.fillMaxWidth()
            .clickable { navigator.open(IosPage.LocalMusicGroup(album = false, name = "", webdav = webdav, liked = true)) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(PlayingAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = PlayingAccent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Liked"), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(
                trPlural(count, "{0} song", "{0} songs"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
