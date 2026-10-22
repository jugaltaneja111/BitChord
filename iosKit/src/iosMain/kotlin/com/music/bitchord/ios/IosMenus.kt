package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongMenu
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.launch
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/** A track's menu, and the playlist it was opened from when that playlist is the account's own. */
/** A track's menu. [fromPlayer] adds what only applies to the playing track, like lyrics timing. */
internal data class SongMenuRequest(
    val song: Song,
    val fromPlaylistId: String? = null,
    val onRemoved: (() -> Unit)? = null,
    val fromPlayer: Boolean = false,
)

/** An album, playlist or artist card's menu. [fromPage] leaves off what the page's own header has. */
internal data class BrowseMenuRequest(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val fromPage: Boolean = false,
)

internal fun ShelfItem.toBrowseMenu(): BrowseMenuRequest? =
    browseId?.let { BrowseMenuRequest(it, title, subtitle, thumbnailUrl) }

/** The menus, opened from wherever their row or card is, drawn once over everything. */
internal object IosMenus {
    var song by mutableStateOf<SongMenuRequest?>(null)
    var browse by mutableStateOf<BrowseMenuRequest?>(null)
    /** The tracks being added to a playlist. */
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    /** A new playlist being named, holding these tracks (none from Library's tile). */
    var newPlaylist by mutableStateOf<List<Song>?>(null)
    /** The player's lyrics timing sheet. */
    var lyricsOffset by mutableStateOf(false)

    /** A held row: a track's menu, or a card's. */
    fun openShelfItem(item: ShelfItem, shelfTitle: String? = null) {
        if (item.videoId != null) song = SongMenuRequest(item.toSong(shelfTitle)) else browse = item.toBrowseMenu()
    }
}

/** music.youtube.com's own links, which open in YouTube Music wherever they are sent. */
internal fun songLink(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

internal fun browseLink(browseId: String) = when {
    browseId.startsWith("VL") -> "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"
    else -> "https://music.youtube.com/browse/$browseId"
}

/** The system share sheet, over whatever is on top. */
internal fun shareLink(text: String) {
    val controller = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    top?.presentViewController(controller, animated = true, completion = null)
}

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val Danger = Color(0xFFFF6B6B)

/** Every menu, as the app draws them. Placed once at the root, above the pages and the player. */
@Composable
internal fun IosMenuHost(navigator: IosNavigator, onLeavePlayer: () -> Unit) {
    IosMenus.song?.let { request ->
        SongMenuSheet(request, navigator, onLeavePlayer, onDismiss = { IosMenus.song = null })
    }
    IosMenus.browse?.let { request ->
        BrowseMenuSheet(request, navigator, onLeavePlayer, onDismiss = { IosMenus.browse = null })
    }
    IosMenus.addToPlaylist?.let { songs ->
        AddToPlaylistSheet(songs, onDismiss = { IosMenus.addToPlaylist = null })
    }
    IosMenus.newPlaylist?.let { songs ->
        NewPlaylistSheet(songs, onDismiss = { IosMenus.newPlaylist = null })
    }
}

/**
 * A bottom sheet in the artwork's colours, as the phone's SongActionsSheet is painted: it is
 * opened from that artwork, usually with it still on screen behind the scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TintedSheet(imageUrl: String?, onDismiss: () -> Unit, content: @Composable ColumnScope.(MenuColors) -> Unit) {
    val palette = rememberArtworkPalette(imageUrl)
    val colors = MenuColors(palette.background, palette.accent, palette.onBackground, palette.onBackgroundVariant, palette.divider)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
        shape = SheetShape,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.text.copy(alpha = 0.35f)))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            content(colors)
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal data class MenuColors(
    val background: Color,
    val accent: Color,
    val text: Color,
    val secondary: Color,
    val divider: Color,
)

@Composable
private fun SheetHeader(title: String, subtitle: String, imageUrl: String?, colors: MenuColors, round: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(if (round) RoundedCornerShape(26.dp) else RoundedCornerShape(8.dp))
                .background(Color(0xFF2C2C2E)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = colors.secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    colors: MenuColors,
    tint: Color? = null,
    value: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && !loading, onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = colors.secondary, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = (tint ?: colors.text).copy(alpha = alpha), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(18.dp))
        Text(label, color = (if (tint == Danger) Danger else colors.text).copy(alpha = alpha), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = colors.accent.copy(alpha = alpha), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The phone's track menu: the account's rows first (rating, playlists, library), then the
 * queue, then where the track belongs, then Share. The account's rows are left off signed out,
 * but for the playlists, which signed out are the ones kept on this iPhone.
 */
@Composable
private fun SongMenuSheet(request: SongMenuRequest, navigator: IosNavigator, onLeavePlayer: () -> Unit, onDismiss: () -> Unit) {
    val signedIn by IosAccount.signedIn.collectAsState()
    val likes by LikeState.overrides.collectAsState()
    var menu by remember(request.song.videoId) { mutableStateOf<SongMenu?>(null) }
    var menuLoaded by remember(request.song.videoId) { mutableStateOf(false) }
    var links by remember(request.song.videoId) { mutableStateOf<Song?>(null) }
    // A file on this iPhone has no album or artist page to look up.
    var linksLoaded by remember(request.song.videoId) {
        mutableStateOf(request.song.albumId != null && request.song.artistId != null || IosLocalMusic.isLocal(request.song))
    }
    val scope = rememberCoroutineScope()
    val song = request.song
    // A file on this iPhone: what YouTube Music holds about it — likes, library, radio, links — isn't there.
    val onDevice = IosLocalMusic.isLocal(song)

    // Whether it is liked or in the library is YouTube's to say; the row that opened this rarely knew.
    LaunchedEffect(song.videoId, signedIn) {
        if (!signedIn || onDevice) return@LaunchedEffect
        menu = YtMusicRepository.songMenu(song.videoId).getOrNull()
        menuLoaded = true
        menu?.likeStatus?.let { stated -> if (likes[song.videoId] == null) LikeState.set(song.videoId, stated) }
    }
    LaunchedEffect(song.videoId) {
        if (linksLoaded) return@LaunchedEffect
        links = YtMusicRepository.trackLinks(song.videoId).getOrNull()
        linksLoaded = true
    }
    val albumId = song.albumId ?: links?.albumId
    val artistId = song.artistId ?: links?.artistId
    val likeStatus = likes[song.videoId] ?: menu?.likeStatus ?: LikeStatus.INDIFFERENT

    fun leave(action: () -> Unit) {
        onDismiss()
        action()
    }

    val localPlaylistId = request.fromPlaylistId?.takeIf(IosLocalPlaylists::isLocal)

    TintedSheet(song.thumbnailUrl, onDismiss) { colors ->
        SheetHeader(song.title, song.artist, song.thumbnailUrl, colors)
        if (signedIn && !onDevice) {
            val liked = likeStatus == LikeStatus.LIKE
            val disliked = likeStatus == LikeStatus.DISLIKE
            MenuRow(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Remove from Liked" else "Like",
                colors,
                tint = if (liked) colors.accent else null,
            ) { IosAccount.toggleLike(song, likeStatus) }
            MenuRow(
                if (disliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                if (disliked) "Undo dislike" else "Dislike",
                colors,
                tint = if (disliked) colors.accent else null,
            ) { IosAccount.toggleDislike(song, likeStatus) }
        }
        MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", colors) {
            leave { IosMenus.addToPlaylist = listOf(song) }
        }
        if (localPlaylistId != null) {
            MenuRow(Icons.Rounded.PlaylistRemove, "Remove from playlist", colors) {
                leave {
                    IosLocalPlaylists.remove(localPlaylistId, song.videoId)
                    IosPlayerHost.showMessage("Removed from playlist")
                    request.onRemoved?.invoke()
                }
            }
        }
        if (signedIn && !onDevice) {
            val playlistId = request.fromPlaylistId?.takeUnless(IosLocalPlaylists::isLocal)
            val setVideoId = song.setVideoId
            if (playlistId != null && setVideoId != null) {
                MenuRow(Icons.Rounded.PlaylistRemove, "Remove from playlist", colors) {
                    leave {
                        scope.launch {
                            YtMusicRepository.removeFromPlaylist(playlistId, listOf(setVideoId to song.videoId))
                                .onSuccess {
                                    IosPlayerHost.showMessage("Removed from playlist")
                                    request.onRemoved?.invoke()
                                    IosAccount.playlistChanged()
                                }
                                .onFailure { IosPlayerHost.showMessage("Could not remove it from the playlist") }
                        }
                    }
                }
            }
            val current = menu
            val token = if (current?.inLibrary == true) current.removeFromLibraryToken else current?.addToLibraryToken
            if (!menuLoaded || token != null) {
                MenuRow(
                    if (current?.inLibrary == true) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
                    if (current?.inLibrary == true) "Remove from library" else "Save to library",
                    colors,
                    loading = !menuLoaded,
                ) {
                    val inLibrary = current?.inLibrary == true
                    token ?: return@MenuRow
                    leave {
                        scope.launch {
                            YtMusicRepository.setLibraryStatus(token)
                                .onSuccess { IosPlayerHost.showMessage(if (inLibrary) "Removed from library" else "Saved to library") }
                                .onFailure { IosPlayerHost.showMessage("Could not update your library") }
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 0.5.dp, color = colors.divider)
        if (!onDevice) MenuRow(Icons.Rounded.Radio, "Start radio", colors) { leave { IosPlayer.playRadio(song) } }
        MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", colors) { leave { IosPlayer.playNext(listOf(song)) } }
        MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", colors) { leave { IosPlayer.addToQueue(listOf(song)) } }
        val downloadedIds by IosDownloads.ids.collectAsState()
        val downloadProgress by IosDownloads.progress.collectAsState()
        if (!onDevice) when (val state = downloadProgress[song.videoId]) {
            null -> if (song.videoId in downloadedIds) {
                MenuRow(Icons.Rounded.DownloadDone, "Remove download", colors) {
                    onDismiss()
                    IosDownloads.remove(listOf(song.videoId))
                    IosPlayerHost.showMessage("Download removed")
                }
            } else {
                MenuRow(Icons.Rounded.Download, "Download", colors) {
                    onDismiss()
                    IosDownloads.download(listOf(song))
                }
            }
            is DownloadProgress.Failed -> MenuRow(Icons.Rounded.Download, "Download again", colors) {
                onDismiss()
                IosDownloads.cancel(song.videoId)
                IosDownloads.download(listOf(song))
            }
            else -> MenuRow(
                Icons.Rounded.Close,
                if (state is DownloadProgress.Running) "Cancel download (${(state.fraction * 100).toInt()}%)" else "Cancel download",
                colors,
            ) {
                onDismiss()
                IosDownloads.cancel(song.videoId)
            }
        }
        if (albumId != null || !linksLoaded) {
            MenuRow(Icons.Rounded.Album, "Go to album", colors, loading = albumId == null) {
                albumId ?: return@MenuRow
                leave {
                    onLeavePlayer()
                    navigator.open(IosPage.Collection(albumId))
                }
            }
        }
        if (artistId != null || !linksLoaded) {
            MenuRow(Icons.Rounded.Person, "Go to artist", colors, loading = artistId == null) {
                artistId ?: return@MenuRow
                leave {
                    onLeavePlayer()
                    navigator.open(IosPage.Artist(artistId, song.artists.firstOrNull()?.name ?: song.artist))
                }
            }
        }
        if (request.fromPlayer && !IosLyrics.lyrics.value.isNullOrEmpty()) {
            MenuRow(Icons.Rounded.Timer, "Adjust lyrics timing", colors) {
                onDismiss()
                IosMenus.lyricsOffset = true
            }
        }
        if (!onDevice) MenuRow(Icons.Rounded.Share, "Share", colors) { leave { shareLink(songLink(song.videoId)) } }
    }
}

/**
 * The phone's BrowseActionsSheet: play or queue a whole release from its card, open it, keep it
 * in the library, share it; rename and delete for the account's own playlists, delete asked twice.
 * A playlist kept on this iPhone is the phone's to rename and delete, and has no link to share.
 */
@Composable
private fun BrowseMenuSheet(request: BrowseMenuRequest, navigator: IosNavigator, onLeavePlayer: () -> Unit, onDismiss: () -> Unit) {
    val signedIn by IosAccount.signedIn.collectAsState()
    val scope = rememberCoroutineScope()
    val type = browseTypeOf(request.browseId)
    val isArtist = type == BrowseType.ARTIST
    // Liked Music and the other auto playlists can be played but not saved, renamed or deleted.
    val isAutoPlaylist = request.browseId in AUTO_PLAYLISTS
    val isLocal = IosLocalPlaylists.isLocal(request.browseId)
    var owned by remember(request.browseId) { mutableStateOf<Boolean?>(null) }
    var library by remember(request.browseId) { mutableStateOf<com.music.bitchord.data.model.LibraryState?>(null) }
    var libraryLoaded by remember(request.browseId) { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var loadingSongs by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request.browseId, signedIn) {
        if (!signedIn || isArtist || isAutoPlaylist || isLocal) {
            libraryLoaded = true
            return@LaunchedEffect
        }
        if (type == BrowseType.PLAYLIST) owned = YtMusicRepository.playlistOwned(request.browseId).getOrNull()
        library = YtMusicRepository.releaseLibraryState(request.browseId).getOrNull()
        libraryLoaded = true
    }

    /** The release's tracks, fetched for a queue action from a card that holds none. */
    fun withSongs(label: String, action: (List<Song>) -> Unit) {
        loadingSongs = label
        scope.launch {
            val found = if (isLocal) {
                IosLocalPlaylists.get(request.browseId)?.songs
            } else {
                YtMusicRepository.browseSongs(request.browseId).getOrNull()?.songs
            }
            val songs = found.orEmpty()
                .map { it.copy(playbackSource = request.title, playbackSourceType = com.music.bitchord.data.model.PlaybackSourceType.BROWSE, playbackSourceId = request.browseId) }
            loadingSongs = null
            onDismiss()
            if (songs.isEmpty()) IosPlayerHost.showMessage("Nothing to play here") else action(songs)
        }
    }

    TintedSheet(request.thumbnailUrl, onDismiss) { colors ->
        SheetHeader(request.title, request.subtitle, request.thumbnailUrl, colors, round = isArtist)
        val playlistId = request.browseId.removePrefix("VL")
        if (renaming) {
            RenameForm(request.title, colors, onCancel = { renaming = false }) { title ->
                onDismiss()
                if (isLocal) {
                    IosLocalPlaylists.rename(request.browseId, title)
                    IosPlayerHost.showMessage("Renamed")
                    return@RenameForm
                }
                scope.launch {
                    YtMusicRepository.renamePlaylist(playlistId, title)
                        .onSuccess {
                            IosPlayerHost.showMessage("Renamed")
                            IosAccount.playlistRenamed(playlistId, title)
                        }
                        .onFailure { IosPlayerHost.showMessage("Could not rename the playlist") }
                }
            }
            return@TintedSheet
        }
        if (!isArtist) {
            if (!request.fromPage) {
                MenuRow(Icons.Rounded.PlayArrow, "Play", colors, loading = loadingSongs == "play") {
                    withSongs("play") { IosPlayer.playQueue(it, 0) }
                }
                MenuRow(Icons.Rounded.Shuffle, "Shuffle", colors, loading = loadingSongs == "shuffle") {
                    withSongs("shuffle") { IosPlayer.playShuffled(it) }
                }
            }
            MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", colors, loading = loadingSongs == "next") {
                withSongs("next") { IosPlayer.playNext(it) }
            }
            MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", colors, loading = loadingSongs == "queue") {
                withSongs("queue") { IosPlayer.addToQueue(it) }
            }
            val savedReleases by IosDownloads.releases.collectAsState()
            if (savedReleases.any { it.browseId == request.browseId }) {
                MenuRow(Icons.Rounded.DownloadDone, "Remove download", colors) {
                    onDismiss()
                    IosDownloads.removeRelease(request.browseId)
                    IosPlayerHost.showMessage("Download removed")
                }
            } else {
                MenuRow(Icons.Rounded.Download, "Download", colors, loading = loadingSongs == "download") {
                    withSongs("download") { songs ->
                        IosDownloads.downloadRelease(
                            DownloadedRelease(request.browseId, request.title, request.subtitle, request.thumbnailUrl, emptyList()),
                            songs,
                        )
                    }
                }
            }
        }
        if (!request.fromPage) {
            MenuRow(
                Icons.Rounded.ChevronRight,
                when (type) {
                    BrowseType.ALBUM -> "Go to album"
                    BrowseType.ARTIST -> "Go to artist"
                    BrowseType.PLAYLIST -> "Go to playlist"
                    else -> if (isLocal) "Go to playlist" else "Open"
                },
                colors,
            ) {
                onDismiss()
                onLeavePlayer()
                navigator.openBrowse(
                    com.music.bitchord.data.model.BrowseItem(request.browseId, request.title, request.subtitle, request.thumbnailUrl, type),
                )
            }
        }
        // An owned playlist is in the library by being the account's; only someone else's is saved.
        if (signedIn && !isArtist && !isAutoPlaylist && !isLocal && owned != true) {
            val state = library
            if (!libraryLoaded || state != null) {
                MenuRow(
                    if (state?.saved == true) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
                    if (state?.saved == true) "Remove from library" else "Save to library",
                    colors,
                    loading = !libraryLoaded,
                ) {
                    state ?: return@MenuRow
                    onDismiss()
                    scope.launch {
                        YtMusicRepository.setSaved(state.playlistId, !state.saved)
                            .onSuccess {
                                IosPlayerHost.showMessage(if (state.saved) "Removed from library" else "Saved to library")
                                IosAccount.loadLibrary(quietly = true)
                            }
                            .onFailure { IosPlayerHost.showMessage("Could not update your library") }
                    }
                }
            }
        }
        if (!isLocal) {
            MenuRow(Icons.Rounded.Share, "Share", colors) {
                onDismiss()
                shareLink(browseLink(request.browseId))
            }
        }
        if (owned == true || isLocal) {
            MenuRow(Icons.Rounded.Edit, "Rename", colors) { renaming = true }
            if (confirmingDelete) {
                MenuRow(Icons.Rounded.DeleteForever, "Delete “${request.title}” for good", colors, tint = Danger) {
                    onDismiss()
                    if (isLocal) {
                        // Its download, if it was saved whole, would leave a card that opens nothing.
                        IosLocalPlaylists.delete(request.browseId)
                        IosDownloads.removeRelease(request.browseId)
                        IosPlayerHost.showMessage("Playlist deleted")
                        if (request.fromPage) navigator.back()
                        return@MenuRow
                    }
                    scope.launch {
                        YtMusicRepository.deletePlaylist(playlistId)
                            .onSuccess {
                                IosPlayerHost.showMessage("Playlist deleted")
                                IosAccount.playlistDeleted(playlistId)
                                if (request.fromPage) navigator.back()
                            }
                            .onFailure { IosPlayerHost.showMessage("Could not delete the playlist") }
                    }
                }
            } else {
                MenuRow(Icons.Rounded.Delete, "Delete playlist", colors) { confirmingDelete = true }
            }
        }
    }
}

/**
 * The account's playlists to add to, newest-made first as YouTube lists them, under New playlist;
 * then those kept on this iPhone, under a heading of their own. Signed out, only the latter.
 */
@Composable
private fun AddToPlaylistSheet(songs: List<Song>, onDismiss: () -> Unit) {
    // Files on this iPhone go only in playlists kept on it: YouTube Music can't hold them.
    val account by IosAccount.signedIn.collectAsState()
    val signedIn = account && songs.none { IosLocalMusic.isLocal(it) }
    val localPlaylists by IosLocalPlaylists.playlists.collectAsState()
    var playlists by remember { mutableStateOf<List<UserPlaylist>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        YtMusicRepository.userPlaylists()
            .onSuccess { playlists = IosAccount.withPlaylistEdits(it) }
            .onFailure { failed = true }
    }
    TintedSheet(songs.firstOrNull()?.thumbnailUrl, onDismiss) { colors ->
        Text(
            "Add to playlist",
            style = MaterialTheme.typography.titleLarge,
            color = colors.text,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
        MenuRow(Icons.Rounded.Add, "New playlist", colors) {
            onDismiss()
            IosMenus.newPlaylist = songs
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
        val list = playlists
        when {
            !signedIn -> if (localPlaylists.isEmpty()) {
                Text("No playlists yet", color = colors.secondary, modifier = Modifier.padding(22.dp))
            }
            failed -> Text("Could not load your playlists", color = colors.secondary, modifier = Modifier.padding(22.dp))
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = colors.secondary, strokeWidth = 2.dp)
            }
            list.isEmpty() -> Text("No playlists yet", color = colors.secondary, modifier = Modifier.padding(22.dp))
            else -> list.forEach { playlist ->
                PlaylistChoice(playlist.title, playlist.subtitle, playlist.thumbnailUrl, colors) {
                    onDismiss()
                    scope.launch {
                        YtMusicRepository.addToPlaylist(playlist.playlistId, songs.map { it.videoId })
                            .onSuccess {
                                IosPlayerHost.showMessage("Added to ${playlist.title}")
                                IosAccount.playlistChanged()
                            }
                            .onFailure { IosPlayerHost.showMessage("Could not add to ${playlist.title}") }
                    }
                }
            }
        }
        if (localPlaylists.isNotEmpty()) {
            if (signedIn) {
                Text(
                    "On this device",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.secondary,
                    modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            localPlaylists.forEach { playlist ->
                PlaylistChoice(playlist.title, playlist.subtitle, playlist.thumbnailUrl, colors) {
                    onDismiss()
                    val added = IosLocalPlaylists.add(playlist.id, songs)
                    IosPlayerHost.showMessage(if (added == 0) "Already in ${playlist.title}" else "Added to ${playlist.title}")
                }
            }
        }
    }
}

/** A playlist row in Add to playlist: its cover, name and count. */
@Composable
private fun PlaylistChoice(title: String, subtitle: String, thumbnailUrl: String?, colors: MenuColors, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2C2C2E)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = colors.secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Names a new playlist and picks who can see it; the tracks it was made for go in it. Signed out
 * it is kept on this iPhone, which has no one to show it to.
 */
@Composable
private fun NewPlaylistSheet(songs: List<Song>, onDismiss: () -> Unit) {
    val account by IosAccount.signedIn.collectAsState()
    val signedIn = account && songs.none { IosLocalMusic.isLocal(it) }
    var title by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TintedSheet(songs.firstOrNull()?.thumbnailUrl, onDismiss) { colors ->
        Text("New playlist", style = MaterialTheme.typography.titleLarge, color = colors.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
        NameField(title, colors, onChange = { title = it })
        if (signedIn) {
            Text("Visibility", style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 4.dp))
            PlaylistPrivacy.entries.forEach { option ->
                MenuRow(
                    Icons.Rounded.Check,
                    option.label,
                    colors,
                    tint = if (option == privacy) colors.accent else Color.Transparent,
                ) { privacy = option }
            }
        } else {
            Text(
                if (account) {
                    "Kept on this device: songs on this iPhone can’t go in a YouTube Music playlist."
                } else {
                    "Kept on this device. Sign in to make playlists on YouTube Music."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondary,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        MenuRow(Icons.Rounded.Add, if (saving) "Creating…" else "Create", colors, enabled = title.isNotBlank() && !saving, tint = colors.accent) {
            if (!signedIn) {
                val created = IosLocalPlaylists.create(title, songs)
                IosPlayerHost.showMessage(if (songs.isEmpty()) "Playlist created on this device" else "Added to ${created.title}")
                onDismiss()
                return@MenuRow
            }
            saving = true
            scope.launch {
                YtMusicRepository.createPlaylist(title.trim(), privacy, songs.map { it.videoId })
                    .onSuccess { playlistId ->
                        IosPlayerHost.showMessage(if (songs.isEmpty()) "Playlist created" else "Added to ${title.trim()}")
                        IosAccount.playlistCreated(playlistId, title.trim(), songs)
                        onDismiss()
                    }
                    .onFailure {
                        saving = false
                        IosPlayerHost.showMessage("Could not create the playlist")
                    }
            }
        }
    }
}

@Composable
private fun RenameForm(current: String, colors: MenuColors, onCancel: () -> Unit, onRename: (String) -> Unit) {
    var title by remember { mutableStateOf(current) }
    Text("Rename playlist", style = MaterialTheme.typography.titleLarge, color = colors.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
    NameField(title, colors, onChange = { title = it })
    Spacer(Modifier.height(8.dp))
    MenuRow(Icons.Rounded.Check, "Save", colors, enabled = title.isNotBlank() && title.trim() != current, tint = colors.accent) { onRename(title.trim()) }
    MenuRow(Icons.Rounded.ChevronRight, "Cancel", colors, onClick = onCancel)
}

@Composable
private fun NameField(value: String, colors: MenuColors, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Playlist name", color = colors.secondary) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.divider,
            cursorColor = colors.accent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
    )
}

/** YouTube's own playlists: Liked Music, Episodes for later, and the like. */
private val AUTO_PLAYLISTS = setOf("VLLM", "LM", "VLSE", "SE", "VLRDPN", "FEmusic_liked_videos")
