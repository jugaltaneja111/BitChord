package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImportTrack
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.wallClockMs
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSData
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataRecord
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * Spotify on the iOS app, as the phone has it: sign in on Spotify's own page and keep the `sp_dc`
 * cookie; mint the web player's bearer by loading open.spotify.com with it in a hidden web view
 * and catching the token the page fetches for itself (the phone's SpotifyToken explains why
 * signing the request ourselves gets a token nothing honours); then the shared [SpotifyLibrary]
 * lists the library, and [SpotifyImporter] matches tracks to YouTube Music.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosSpotify {
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    private const val HANDLER = "bitchordSpotifyToken"
    private const val HARVEST_TIMEOUT_MS = 20_000L
    private const val DEFAULT_TOKEN_LIFETIME_MS = 3_600_000L
    /** The phone's canvas client sent this one; kept so the requests look the same. */
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http by lazy { appHttpClient {} }
    private val harvestLock = Mutex()

    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    private var clientId: String? = null
    private var clientToken: String? = null
    private var clientTokenExpiresAt = 0L
    private var session: Pair<String, String>? = null

    val connected: Boolean get() = IosSettings.spotifySpdc.flow.value.isNotBlank()

    /** Hands [SpotifyLibrary] its tokens. Called once, at launch. */
    fun install() {
        // The library asks from a background thread; the web view that mints the token, and this
        // object's state, live on the main one.
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            withContext(Dispatchers.Main) { bearer()?.let { SpotifyLibrary.Tokens(it, clientToken()) } }
        }
    }

    fun connect(cookie: String) {
        invalidate()
        IosSettings.spotifySpdc.set(cookie)
    }

    /** Forgets the cookie and signs the in-app browser out of Spotify too, so "Sign in" asks again. */
    fun disconnect() {
        invalidate()
        IosSettings.spotifySpdc.set("")
        val store = WKWebsiteDataStore.defaultDataStore()
        val types = WKWebsiteDataStore.allWebsiteDataTypes()
        store.fetchDataRecordsOfTypes(types) { records ->
            val spotify = records.orEmpty().filterIsInstance<WKWebsiteDataRecord>().filter {
                it.displayName == "spotify.com" || it.displayName.endsWith(".spotify.com") || it.displayName == "scdn.co"
            }
            if (spotify.isNotEmpty()) store.removeDataOfTypes(types, spotify) {}
        }
    }

    private fun invalidate() {
        accessToken = null
        accessTokenExpiresAt = 0L
        clientId = null
        clientToken = null
        clientTokenExpiresAt = 0L
    }

    /** The web player's bearer, minted when there is none or it is about to lapse. */
    private suspend fun bearer(): String? {
        val cookie = IosSettings.spotifySpdc.flow.value
        if (cookie.isBlank()) return null
        accessToken?.let { if (wallClockMs() < accessTokenExpiresAt - 30_000) return it }
        return harvestLock.withLock {
            accessToken?.let { if (wallClockMs() < accessTokenExpiresAt - 30_000) return@withLock it }
            val harvested = harvest(cookie) ?: run {
                TrackLog.w("Spotify", "token harvest failed or timed out")
                return@withLock null
            }
            accessToken = harvested.token
            accessTokenExpiresAt = harvested.expiresAt
            harvested.clientId?.let { clientId = it }
            harvested.token
        }
    }

    private class Harvested(val token: String, val expiresAt: Long, val clientId: String?)

    private class TokenHandler(val result: CompletableDeferred<Harvested?>) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            val payload = didReceiveScriptMessage.body as? String ?: return
            if (result.isCompleted) return
            runCatching {
                val root = json.parseToJsonElement(payload).jsonObject
                val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
                val anonymous = root["isAnonymous"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                // The page mints an anonymous token before the cookie takes; that one reads nothing.
                if (token.isNullOrBlank() || anonymous) return
                val expiresAt = root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?.takeIf { it > wallClockMs() } ?: (wallClockMs() + DEFAULT_TOKEN_LIFETIME_MS)
                result.complete(Harvested(token, expiresAt, root["clientId"]?.jsonPrimitive?.contentOrNull))
            }
        }
    }

    /**
     * Loads the web player in an off-screen view with the cookie set, in a store of its own so
     * no token cached from last time stops the page asking for a fresh one.
     */
    private suspend fun harvest(cookie: String): Harvested? {
        val result = CompletableDeferred<Harvested?>()
        val handler = TokenHandler(result)
        val store = WKWebsiteDataStore.nonPersistentDataStore()
        val cookieSet = CompletableDeferred<Unit>()
        val spDc = NSHTTPCookie.cookieWithProperties(
            mapOf<Any?, Any?>(
                NSHTTPCookieName to "sp_dc",
                NSHTTPCookieValue to cookie,
                NSHTTPCookieDomain to ".spotify.com",
                NSHTTPCookiePath to "/",
                NSHTTPCookieSecure to "TRUE",
            ),
        )
        if (spDc == null) return null
        store.httpCookieStore.setCookie(spDc) { cookieSet.complete(Unit) }
        cookieSet.await()

        val controller = WKUserContentController()
        controller.addScriptMessageHandler(handler, HANDLER)
        controller.addUserScript(
            WKUserScript(source = HOOK_SCRIPT, injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, forMainFrameOnly = false),
        )
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = store
            userContentController = controller
        }
        val view = WKWebView(frame = CGRectMake(0.0, 0.0, 390.0, 844.0), configuration = configuration)
        view.customUserAgent = USER_AGENT
        // In the window, invisibly, so the page runs as a page in view does rather than throttled.
        view.alpha = 0.0
        view.userInteractionEnabled = false
        UIApplication.sharedApplication.keyWindow?.addSubview(view)
        view.loadRequest(NSURLRequest(uRL = NSURL(string = "https://open.spotify.com/")))
        return try {
            withTimeoutOrNull(HARVEST_TIMEOUT_MS) { result.await() }
        } finally {
            view.stopLoading()
            view.removeFromSuperview()
            controller.removeScriptMessageHandlerForName(HANDLER)
        }
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__bitchordTokenHook) return;
          window.__bitchordTokenHook = true;
          var report = function (body) {
            try { window.webkit.messageHandlers.$HANDLER.postMessage(String(body)); } catch (e) {}
          };
          var isToken = function (u) {
            try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; }
          };
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function (input, init) {
              var url = (input && input.url) ? input.url : input;
              var result = origFetch.apply(this, arguments);
              if (isToken(url)) {
                try {
                  result.then(function (res) {
                    res.clone().text().then(report).catch(function () {});
                  }).catch(function () {});
                } catch (e) {}
              }
              return result;
            };
          }
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__bitchordUrl = url;
            return origOpen.apply(this, arguments);
          };
          var origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try {
              xhr.addEventListener('load', function () {
                if (isToken(xhr.__bitchordUrl)) {
                  try { report(xhr.responseText); } catch (e) {}
                }
              });
            } catch (e) {}
            return origSend.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /**
     * The second header Spotify's endpoints now want beside the bearer: without it they answer
     * with a 429 that reads like rate limiting. Best-effort; needs the client id the token came with.
     */
    private suspend fun clientToken(): String? {
        clientToken?.let { if (wallClockMs() < clientTokenExpiresAt - 30_000) return it }
        val id = clientId ?: return null
        val (clientVersion, deviceId) = session() ?: return null
        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", clientVersion)
                put("client_id", id)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "Apple")
                    put("device_model", "iPhone")
                    put("os", "ios")
                    put("os_version", platform.UIKit.UIDevice.currentDevice.systemVersion)
                    put("device_id", deviceId)
                    put("device_type", "smartphone")
                }
            }
        }
        val response = runCatching {
            http.post("https://clienttoken.spotify.com/v1/clienttoken") {
                header("Accept", "application/json")
                header("User-Agent", USER_AGENT)
                // Bare application/json: clienttoken refuses one with a charset.
                contentType(ContentType.Application.Json.withoutParameters())
                setBody(payload.toString())
            }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return null
        if (root["response_type"]?.jsonPrimitive?.contentOrNull != "RESPONSE_GRANTED_TOKEN_RESPONSE") return null
        val granted = root["granted_token"]?.jsonObject ?: return null
        val token = granted["token"]?.jsonPrimitive?.contentOrNull ?: return null
        val ttl = granted["expires_after_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        clientToken = token
        clientTokenExpiresAt = wallClockMs() + ttl * 1000
        return token
    }

    /** The web player's client version and a device id, off the plain open.spotify.com page. */
    private suspend fun session(): Pair<String, String>? {
        session?.let { return it }
        val response = runCatching { http.get("https://open.spotify.com") { header("User-Agent", USER_AGENT) } }.getOrNull() ?: return null
        val html = response.bodyAsText()
        val deviceId = response.headers.getAll("Set-Cookie").orEmpty().firstNotNullOfOrNull { cookie ->
            Regex("""sp_t=([^;]+)""").find(cookie)?.groupValues?.get(1)
        } ?: NSUUID().UUIDString
        val configB64 = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""").find(html)?.groupValues?.get(1)
            ?: return null
        val config = NSData.create(base64EncodedString = configB64, options = 0u)
            ?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() } ?: return null
        val clientVersion = runCatching {
            json.parseToJsonElement(config).jsonObject["clientVersion"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return null
        return (clientVersion to deviceId).also { session = it }
    }

    /**
     * Makes [songs] a YouTube Music playlist called [title]: the first fifty in the create call,
     * the rest added fifty at a time, as the phone does.
     */
    suspend fun saveAsPlaylist(title: String, privacy: PlaylistPrivacy, songs: List<Song>): Result<String> = runCatching {
        val ids = songs.map { it.videoId }
        val playlistId = YtMusicRepository.createPlaylist(title, privacy, ids.take(50)).getOrThrow()
        ids.drop(50).chunked(50).forEach { YtMusicRepository.addToPlaylist(playlistId, it) }
        IosAccount.playlistCreated(playlistId, title, songs)
        playlistId
    }
}

// ---- Sign-in ---------------------------------------------------------------

private class SpotifyLoginDelegate(val onCookie: (String) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    private var sent = false

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (sent || webView.URL?.absoluteString?.startsWith("https://open.spotify.com") != true) return
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { all ->
            val token = all.orEmpty().filterIsInstance<NSHTTPCookie>()
                .firstOrNull { it.name == "sp_dc" && it.domain.endsWith("spotify.com") }
                ?.value?.takeIf { it.isNotBlank() }
                ?: return@getAllCookies
            if (sent) return@getAllCookies
            sent = true
            onCookie(token)
        }
    }
}

/** Spotify's own sign-in page; it closes itself once the page lands on the web player. */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosSpotifyLoginScreen(onClose: () -> Unit) {
    val delegate = remember {
        SpotifyLoginDelegate { cookie ->
            IosSpotify.connect(cookie)
            IosPlayerHost.showMessage("Spotify connected")
            onClose()
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
            Text("Sign in to Spotify", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        UIKitView(
            factory = {
                val configuration = WKWebViewConfiguration().apply {
                    websiteDataStore = WKWebsiteDataStore.defaultDataStore()
                }
                WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                    customUserAgent = IosLogin.SAFARI_USER_AGENT
                    navigationDelegate = delegate
                    loadRequest(NSURLRequest(uRL = NSURL(string = IosSpotify.LOGIN_URL)))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---- The library -----------------------------------------------------------

/** The phone's Spotify page: sign in, or the library's playlists with Liked Songs first. */
@Composable
internal fun SpotifyPage(navigator: IosNavigator, contentPadding: PaddingValues, topPadding: Dp) {
    val cookie by IosSettings.spotifySpdc.flow.collectAsState()
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cookie) {
        if (cookie.isBlank()) {
            playlists = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { SpotifyLibrary.playlists() }
            .onSuccess { playlists = it }
            .onFailure { error = it.message ?: "Could not load your Spotify library" }
        loading = false
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Text(
                "Spotify",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (cookie.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "Sign in to load your playlists. Playback uses YouTube Music.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { IosOverlays.spotifyLogin = true }) { Text("Sign in to Spotify") }
                }
            }
        } else {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            error?.let { message ->
                item {
                    Text(message, color = Color(0xFFFF6B6B), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.open(IosPage.SpotifyPlaylist(playlist.id, playlist.name, playlist.imageUrl)) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AsyncImage(
                        model = playlist.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2E)),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        playlist.owner?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One Spotify playlist, as the phone opens it: every track listed at once from Spotify, each
 * replaced by its YouTube Music match as the search answers, so the page plays as it fills.
 */
@Composable
internal fun SpotifyPlaylistPage(page: IosPage.SpotifyPlaylist, contentPadding: PaddingValues, topInset: Dp) {
    var cover by remember(page.id) { mutableStateOf(page.imageUrl) }
    var rows by remember(page.id) { mutableStateOf<List<Song>?>(null) }
    var error by remember(page.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(page.id) {
        launch { runCatching { SpotifyLibrary.cover(page.id) }.getOrNull()?.let { cover = it } }
        val tracks = runCatching { SpotifyLibrary.tracks(page.id) { soFar -> rows = soFar.map { it.pending() } } }
            .getOrElse {
                error = it.message ?: "Could not load this playlist"
                return@LaunchedEffect
            }
        if (tracks.isEmpty()) {
            error = "This playlist has no tracks"
            return@LaunchedEffect
        }
        rows = tracks.map { it.pending() }
        val gate = Semaphore(MATCH_PARALLELISM)
        tracks.forEachIndexed { index, track ->
            launch {
                gate.withPermit {
                    val match = runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
                    val found = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                        ?: track.pending().copy(videoId = MISSING_PREFIX + track.id)
                    rows = rows?.toMutableList()?.also { if (index < it.size) it[index] = found }
                }
            }
        }
    }
    val all = rows.orEmpty()
    val playable = all.filterNot { it.videoId.startsWith(PENDING_PREFIX) || it.videoId.startsWith(MISSING_PREFIX) }
        .map { it.copy(playbackSource = page.title, playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = "spotify:playlist:${page.id}") }
    val current by IosPlayer.song.collectAsState()
    val isPlaying by IosPlayer.isPlaying.collectAsState()
    val signedIn by IosAccount.signedIn.collectAsState()

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            CollectionHeader(
                title = page.title,
                subtitle = "Spotify",
                thumbnailUrl = cover,
                typeLabel = "Playlist",
                count = all.size.takeIf { rows != null },
                topInset = topInset,
                onPlay = { IosPlayer.playQueue(playable, 0) },
                onShuffle = { IosPlayer.playShuffled(playable) },
                onMore = {
                    if (!signedIn) {
                        IosOverlays.login = true
                    } else if (!saving && playable.isNotEmpty()) {
                        saving = true
                        scope.launch {
                            IosSpotify.saveAsPlaylist(page.title, PlaylistPrivacy.PRIVATE, playable)
                                .onSuccess { IosPlayerHost.showMessage("Saved “${page.title}” to your library") }
                                .onFailure { IosPlayerHost.showMessage("Could not save the playlist") }
                            saving = false
                        }
                    }
                },
            )
        }
        error?.let { message ->
            item(key = "error") { MessageState(message) }
        }
        if (rows == null && error == null) songListSkeleton()
        itemsIndexed(all, key = { index, song -> "${song.videoId}:$index" }) { _, song ->
            val waiting = song.videoId.startsWith(PENDING_PREFIX)
            val missing = song.videoId.startsWith(MISSING_PREFIX)
            val isCurrent = current?.videoId == song.videoId
            SongRow(
                    song = if (missing) song.copy(artist = "Not on YouTube Music · ${song.artist}") else song,
                    onClick = {
                        if (!waiting && !missing) {
                            IosPlayer.playQueue(playable, playable.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0))
                        }
                    },
                    onLongPress = { if (!waiting && !missing) IosMenus.song = SongMenuRequest(song = song) },
                    onSwipeToQueue = { if (!waiting && !missing) IosPlayer.swipe(song) },
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                )
        }
    }
}

private fun SpotifyTrack.pending() = Song(
    videoId = PENDING_PREFIX + id,
    title = title,
    artist = artist,
    thumbnailUrl = imageUrl,
    durationText = durationMs.takeIf { it > 0 }?.let { ms -> "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}" },
    albumName = album,
)

private const val PENDING_PREFIX = "spotify:pending:"
private const val MISSING_PREFIX = "spotify:missing:"
private const val MATCH_PARALLELISM = 4

// ---- Importing a link ------------------------------------------------------

/** The phone's import dialog: a public playlist's link, matched and saved as a YouTube Music playlist. */
@Composable
internal fun SpotifyImportDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<SpotifyImportTrack>?>(null) }

    fun start() {
        val id = SpotifyImporter.extractPlaylistId(link)
        if (id == null) {
            status = "That isn’t a Spotify playlist link"
            failed = true
            return
        }
        working = true
        failed = false
        status = "Reading the playlist…"
        scope.launch {
            try {
                val (name, tracks) = SpotifyImporter.fetchPlaylistTracks(id)
                val (songs, missed) = SpotifyImporter.resolveToSongs(tracks) { done, total ->
                    status = "Matching songs… $done of $total"
                }
                if (songs.isEmpty()) error("None of its songs were found on YouTube Music")
                status = "Saving…"
                IosSpotify.saveAsPlaylist(name, PlaylistPrivacy.PRIVATE, songs).getOrThrow()
                IosPlayerHost.showMessage("Imported “$name”")
                if (missed.isEmpty()) onDismiss() else unmatched = missed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Import failed: ${e.message.orEmpty()}"
                failed = true
            }
            working = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Import from Spotify") },
        text = {
            Column {
                val missed = unmatched
                if (missed != null) {
                    Text("${missed.size} songs weren’t found on YouTube Music:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        missed.forEach { track ->
                            Text(
                                if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text("Paste a link to a public Spotify playlist. Its songs are found on YouTube Music and saved as a playlist in your library.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = link, onValueChange = { link = it }, singleLine = true, enabled = !working, label = { Text("Playlist link") })
                    status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (failed) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (unmatched != null) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(enabled = !working && link.isNotBlank(), onClick = ::start) { Text(if (working) "Importing…" else "Import") }
            }
        },
        dismissButton = {
            if (unmatched == null) TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
