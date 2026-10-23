package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import coil3.compose.AsyncImage
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.discord.DiscordApplication
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.wallClockMs
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.dataUsingEncoding
import platform.Foundation.localeIdentifier
import platform.UIKit.UIDevice
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

/**
 * Discord Rich Presence, as the phone publishes it: the account's own token (there is no OAuth
 * scope for setting a user's presence), the gateway socket from the shared Kizzy code, and the
 * card the phone's DiscordRPC draws — song, artist, cover, a countdown from start and end
 * instants, and two buttons. Up while something plays; taken down, socket kept, on pause.
 */
internal object IosDiscord {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rpc: KizzyRPC? = null
    private var updateJob: Job? = null
    private var presenceUp = false
    private var started = false

    const val PROJECT_URL = "https://github.com/kushagrasinghx/BitChord"
    const val DEFAULT_BUTTON_1 = "Listen on YouTube Music"
    const val DEFAULT_BUTTON_2 = "Visit BitChord"
    private const val ART_PX = 480
    private const val TEARDOWN_TIMEOUT_MS = 3_000L

    /** Discord Android's client fingerprint, as the phone's SuperProperties sends it. */
    private const val CLIENT_BUILD_NUMBER = 314013
    private const val USER_AGENT = "Discord-Android/$CLIENT_BUILD_NUMBER;RNA"

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    private val superPropertiesBase64: String by lazy {
        val properties = buildJsonObject {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", "iPhone")
            put("system_locale", NSLocale.currentLocale.localeIdentifier)
            put("client_version", "314.13 - Stable")
            put("release_channel", "googleRelease")
            put("device_vendor_id", NSUUID().UUIDString.lowercase())
            put("client_uuid", NSUUID().UUIDString.lowercase())
            put("client_launch_id", NSUUID().UUIDString.lowercase())
            put("os_version", UIDevice.currentDevice.systemVersion)
            put("os_sdk_version", "34")
            put("client_build_number", CLIENT_BUILD_NUMBER)
            put("client_event_source", null as String?)
            put("design_id", 0)
        }.toString()
        NSString.create(string = properties).dataUsingEncoding(NSUTF8StringEncoding)?.base64EncodedStringWithOptions(0u).orEmpty()
    }

    /** Opens or closes the connection as the token and the switch change. Called once, at launch. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(IosSettings.discordToken.flow, IosSettings.discordRpcEnabled.flow) { token, enabled ->
                token.takeIf { enabled && it.isNotBlank() }
            }.distinctUntilChanged().collect { token ->
                updateJob?.cancel()
                rpc?.let { old ->
                    val wasUp = presenceUp
                    presenceUp = false
                    withContext(Dispatchers.Default + NonCancellable) {
                        withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { if (wasUp) runCatching { old.close() } }
                        runCatching { old.closeRPC() }
                    }
                }
                rpc = null
                if (token == null) return@collect
                rpc = KizzyRPC(
                    token = token,
                    os = "Android",
                    browser = "Discord Android",
                    device = "iPhone",
                    userAgent = USER_AGENT,
                    superPropertiesBase64 = superPropertiesBase64,
                )
                // Switched on mid-song: up now, not at the next track.
                refresh()
            }
        }
        scope.launch {
            combine(
                listOf(
                    IosSettings.discordUseDetails.flow,
                    IosSettings.discordStatus.flow,
                    IosSettings.discordActivityType.flow,
                    IosSettings.discordActivityName.flow,
                    IosSettings.discordButton1Text.flow,
                    IosSettings.discordButton1Visible.flow,
                    IosSettings.discordButton2Text.flow,
                    IosSettings.discordButton2Visible.flow,
                ),
            ) { it.toList() }.distinctUntilChanged().drop(1).collect { refresh() }
        }
    }

    /** The player's state changed — a track, a pause, a seek — so the card follows. */
    fun refresh() {
        val song = IosPlayer.song.value
        if (song != null && IosPlayer.isPlaying.value) push(song) else clear()
    }

    private fun push(song: Song) {
        val rpc = rpc ?: return
        val positionMs = IosPlayer.position.positionMs.coerceAtLeast(0L)
        val durationMs = IosPlayer.durationMs.value.coerceAtLeast(0L)
        updateJob?.cancel()
        presenceUp = true
        updateJob = scope.launch {
            runCatching {
                val now = wallClockMs()
                val buttons = buildList {
                    // A file on this iPhone has no page to link to.
                    if (IosSettings.discordButton1Visible.flow.value && !IosLocalMusic.isLocal(song)) {
                        add(resolve(IosSettings.discordButton1Text.flow.value.ifEmpty { DEFAULT_BUTTON_1 }, song) to watchUrl(song))
                    }
                    if (IosSettings.discordButton2Visible.flow.value) {
                        add(resolve(IosSettings.discordButton2Text.flow.value.ifEmpty { DEFAULT_BUTTON_2 }, song) to PROJECT_URL)
                    }
                }
                rpc.setActivity(
                    name = IosSettings.discordActivityName.flow.value.ifEmpty { "BitChord" },
                    details = song.title,
                    state = song.artist,
                    detailsUrl = watchUrl(song).takeUnless { IosLocalMusic.isLocal(song) },
                    largeImage = song.artworkAt(ART_PX)?.takeIf { it.startsWith("http") }?.let(RpcImage::ExternalImage),
                    smallImage = null,
                    largeText = null,
                    buttons = buttons.ifEmpty { null },
                    type = when (IosSettings.discordActivityType.flow.value) {
                        "playing" -> KizzyRPC.Type.PLAYING
                        "watching" -> KizzyRPC.Type.WATCHING
                        "competing" -> KizzyRPC.Type.COMPETING
                        else -> KizzyRPC.Type.LISTENING
                    },
                    statusDisplayType = if (IosSettings.discordUseDetails.flow.value) {
                        KizzyRPC.StatusDisplayType.DETAILS
                    } else {
                        KizzyRPC.StatusDisplayType.STATE
                    },
                    since = now,
                    startTime = now - positionMs,
                    endTime = if (durationMs > 0) now + (durationMs - positionMs).coerceAtLeast(0L) else null,
                    applicationId = DiscordApplication.ID,
                    status = IosSettings.discordStatus.flow.value,
                )
            }.onFailure { TrackLog.d("Discord", "presence failed: ${it.message}") }
        }
    }

    /** Takes the card down and keeps the socket, so resuming doesn't pay for a reconnect. */
    private fun clear() {
        val rpc = rpc ?: return
        if (!presenceUp) return
        presenceUp = false
        updateJob?.cancel()
        updateJob = scope.launch { runCatching { rpc.close() } }
    }

    /** Signed in: keep the token and who it belongs to. */
    suspend fun connect(token: String): Boolean {
        val user = KizzyRPC.getUserInfo(token, USER_AGENT, superPropertiesBase64).getOrElse {
            TrackLog.w("Discord", "token refused: ${it.message}")
            return false
        }
        IosSettings.discordUsername.set(user.username)
        IosSettings.discordName.set(user.name)
        IosSettings.discordAvatar.set(user.avatar.orEmpty())
        IosSettings.discordToken.set(token)
        IosSettings.discordRpcEnabled.set(true)
        return true
    }

    fun disconnect() {
        IosSettings.discordToken.set("")
        IosSettings.discordUsername.set("")
        IosSettings.discordName.set("")
        IosSettings.discordAvatar.set("")
        val store = WKWebsiteDataStore.defaultDataStore()
        val types = WKWebsiteDataStore.allWebsiteDataTypes()
        store.fetchDataRecordsOfTypes(types) { records ->
            val discord = records.orEmpty().filterIsInstance<platform.WebKit.WKWebsiteDataRecord>()
                .filter { it.displayName == "discord.com" || it.displayName.endsWith(".discord.com") }
            if (discord.isNotEmpty()) store.removeDataOfTypes(types, discord) {}
        }
    }

    fun watchUrl(song: Song) = "https://music.youtube.com/watch?v=${song.videoId}"

    /** {song_name}, {artist_name} and {album_name} in a button's text. */
    fun resolve(text: String, song: Song) = text
        .replace("{song_name}", song.title)
        .replace("{artist_name}", song.artist)
        .replace("{album_name}", song.albumName.orEmpty())

    /**
     * Reads the token out of a signed-in page through a fresh same-origin iframe: Discord takes
     * localStorage away from the top window once the client boots, so a script like this can't.
     */
    const val TOKEN_SNIPPET = """
        (function () {
          try {
            var frame = document.createElement('iframe');
            document.body.appendChild(frame);
            var token = frame.contentWindow.localStorage.token;
            document.body.removeChild(frame);
            return token ? token.slice(1, -1) : '';
          } catch (e) {
            return '';
          }
        })()
    """
}

/** Off the sign-in flow: a finished login lands on /app or /channels/@me, depending. */
private fun isSignedIn(url: String?): Boolean =
    url != null && url.startsWith("https://discord.com/") && !url.contains("/login") && !url.contains("/register")

/**
 * discord.com's own login (2FA, passkeys and all), then the token lifted from the page once it
 * leaves the sign-in flow. The client routes itself there without a real navigation, so the page
 * is watched on a timer rather than through navigation callbacks.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosDiscordLoginScreen(onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WKWebView?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        var attempts = 0
        while (true) {
            delay(500)
            if (!isSignedIn(view.URL?.absoluteString)) continue
            val token = kotlinx.coroutines.suspendCancellableCoroutine<String?> { continuation ->
                view.evaluateJavaScript(IosDiscord.TOKEN_SNIPPET) { result, _ ->
                    continuation.resumeWith(Result.success((result as? String)?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }))
                }
            }
            if (token == null) {
                // The client is still booting and hasn't written the key yet.
                if (++attempts >= 24) status = tr("Signed in, but no token was found. Try entering one instead.")
                continue
            }
            status = tr("Connecting…")
            if (IosDiscord.connect(token)) {
                IosPlayerHost.showMessage(tr("Discord connected"))
                onClose()
            } else {
                status = tr("Discord didn’t accept that sign-in")
            }
            break
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = tr("Close"), tint = MaterialTheme.colorScheme.onSurface) }
            Column(Modifier.weight(1f)) {
                Text(tr("Sign in to Discord"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        UIKitView(
            factory = {
                val configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.defaultDataStore() }
                WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                    customUserAgent = IosLogin.SAFARI_USER_AGENT
                    loadRequest(NSURLRequest(uRL = NSURL(string = "https://discord.com/login")))
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The phone's Discord page: who is connected, and what the card shows. */
@Composable
internal fun DiscordPage(contentPadding: PaddingValues, topPadding: Dp) {
    val token by IosSettings.discordToken.flow.collectAsState()
    val name by IosSettings.discordName.flow.collectAsState()
    val username by IosSettings.discordUsername.flow.collectAsState()
    val avatar by IosSettings.discordAvatar.flow.collectAsState()
    val status by IosSettings.discordStatus.flow.collectAsState()
    val activityType by IosSettings.discordActivityType.flow.collectAsState()
    val activityName by IosSettings.discordActivityName.flow.collectAsState()
    val button1 by IosSettings.discordButton1Text.flow.collectAsState()
    val button2 by IosSettings.discordButton2Text.flow.collectAsState()
    var pastingToken by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, StringPrefLike>?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Text("Discord", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (token.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        tr("Show what you are playing on your Discord profile. Discord has no way for apps to do this, so BitChord signs in as you, like Discord’s own app; your token stays on this phone and goes only to Discord."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { IosOverlays.discordLogin = true }) { Text(tr("Sign in to Discord")) }
                    TextButton(onClick = { pastingToken = true }) { Text(tr("Enter a token instead")) }
                }
            }
        } else {
            item {
                Group("") {
                    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = avatar.ifBlank { null },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF5865F2)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name.ifBlank { username }, color = MaterialTheme.colorScheme.onSurface)
                            Text("@$username", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Divider()
                    Toggle(tr("Rich presence"), tr("Show the song you’re playing on your profile"), IosSettings.discordRpcEnabled)
                    Divider()
                    ActionRow(tr("Disconnect"), destructive = true, onClick = IosDiscord::disconnect)
                }
            }
            item {
                Group(tr("Status")) {
                    listOf("online" to tr("Online"), "idle" to tr("Idle"), "dnd" to tr("Do not disturb"), "invisible" to tr("Invisible")).forEachIndexed { i, (value, label) ->
                        if (i > 0) Divider()
                        Choice(label, status == value) { IosSettings.discordStatus.set(value) }
                    }
                }
            }
            item {
                Group(tr("Activity")) {
                    listOf("listening" to tr("Listening to"), "playing" to tr("Playing"), "watching" to tr("Watching"), "competing" to tr("Competing in")).forEachIndexed { i, (value, label) ->
                        if (i > 0) Divider()
                        Choice(label, activityType == value) { IosSettings.discordActivityType.set(value) }
                    }
                    Divider()
                    ActionRow(tr("Activity name"), value = activityName.ifBlank { "BitChord" }) { editing = tr("Activity name") to IosSettings.discordActivityName }
                    Divider()
                    Toggle(tr("Show the song as the status"), tr("Your status line reads the song instead of the activity name"), IosSettings.discordUseDetails)
                }
            }
            item {
                Group(tr("Buttons")) {
                    Toggle(tr("First button"), button1.ifBlank { IosDiscord.DEFAULT_BUTTON_1 }, IosSettings.discordButton1Visible)
                    Divider()
                    ActionRow(tr("First button’s text"), value = button1.ifBlank { tr("Default") }) { editing = tr("First button’s text") to IosSettings.discordButton1Text }
                    Divider()
                    Toggle(tr("Second button"), button2.ifBlank { IosDiscord.DEFAULT_BUTTON_2 }, IosSettings.discordButton2Visible)
                    Divider()
                    ActionRow(tr("Second button’s text"), value = button2.ifBlank { tr("Default") }) { editing = tr("Second button’s text") to IosSettings.discordButton2Text }
                }
                Footer(tr("Button text can use {song_name}, {artist_name} and {album_name}."))
            }
        }
    }

    if (pastingToken) {
        val scope = rememberCoroutineScope()
        var input by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!checking) pastingToken = false },
            title = { Text(tr("Discord token")) },
            text = {
                Column {
                    Text(tr("Use this if the login page does not load."), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, label = { Text(tr("Token")) })
                    error?.let { Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = input.isNotBlank() && !checking, onClick = {
                    checking = true
                    scope.launch {
                        if (IosDiscord.connect(input.trim())) pastingToken = false else error = tr("Discord didn’t accept that token")
                        checking = false
                    }
                }) { Text(if (checking) tr("Checking…") else tr("Save")) }
            },
            dismissButton = { TextButton(enabled = !checking, onClick = { pastingToken = false }) { Text(tr("Cancel")) } },
        )
    }

    editing?.let { (title, pref) ->
        var input by remember(title) { mutableStateOf(pref.flow.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(title) },
            text = { OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, placeholder = { Text(tr("Leave empty for the default")) }) },
            confirmButton = { TextButton(onClick = { pref.set(input.trim()); editing = null }) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = tr("Selected"), tint = Color(0xFF0A84FF), modifier = Modifier.size(22.dp))
    }
}
