package com.music.bitchord.ios.party

import com.music.bitchord.ios.tr
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.listentogether.ApiError
import com.music.bitchord.data.listentogether.JamInvite
import com.music.bitchord.data.listentogether.JoinRequest
import com.music.bitchord.data.listentogether.PartyActivity
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyMembership
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyPreview
import com.music.bitchord.data.listentogether.PartyQueue
import com.music.bitchord.data.listentogether.PartySnapshot
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.ServerClock
import com.music.bitchord.data.wallClockMs
import com.music.bitchord.ios.IosAccount
import com.music.bitchord.ios.IosKeychain
import com.music.bitchord.ios.IosNetwork
import com.music.bitchord.ios.IosSettings
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSBundle
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

/**
 * Listening together: this device's half of a party, ported from the desktop's
 * `DesktopListenTogether` (itself a port of Android's `ListenTogether`).
 *
 * The protocol is the server's, documented in `backend/README.md`, and the wire types are the same
 * ones the other two apps use — all three compile `:shared`'s `PartyModels`. What differs here is
 * only the plumbing: the Darwin engine rather than CIO or OkHttp, `NSUserDefaults` and the Keychain
 * rather than `SharedPreferences`, and [IosAccount] as the account a party is joined as.
 *
 * A party lasts as long as the app is running. Backgrounding, a tunnel or a handover do not end
 * it — the reconnect loop below exists for exactly those — but a process that is killed cannot
 * resume its old socket, so the slot it held is handed back on the next launch (see `init`).
 *
 * Threading: [state] is written on the main thread, so the UI and [IosPartySync] read one
 * consistent sequence of it. Requests and the socket run off it — Darwin answers on its own queue
 * anyway — and hop back to publish what they learned.
 */
internal object IosListenTogether {

    enum class Connection { OFFLINE, CONNECTING, LIVE }
    enum class Health { CHECKING, ONLINE, OFFLINE }

    data class ServerStatus(
        val health: Health = Health.CHECKING,
        val latencyMs: Long = 0,
        /** A custom server is configured but did not answer, so the built-in one is in use. */
        val isFallback: Boolean = false,
    )

    /** What one `/healthz` answered, and how long it took. */
    data class ProbeResult(val isOnline: Boolean, val latencyMs: Long = 0L)

    /** A party invite, from either of the shapes a link can arrive in. */
    data class ParsedInvite(val code: String, val serverUrl: String? = null)

    data class State(
        val code: String? = null,
        val you: PartyMember? = null,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        /** @see controlsLocked */
        val hostOnlyControl: Boolean = false,
        val playback: PartyPlayback = PartyPlayback(),
        /** Held apart from [playback]: the state frame carries only a sequence number for it. */
        val queue: PartyQueue = PartyQueue(),
        val connection: Connection = Connection.OFFLINE,
        /** False until the first round trip; the playhead is a guess until then. */
        val clockSynced: Boolean = false,
        val roundTripMs: Long = 0,
        /** The last 100 actions, newest first, for this membership only. */
        val activity: List<PartyActivity> = emptyList(),
        /**
         * A resume this device has accepted but is not allowed to perform yet — see
         * [IosPartySync.shouldDeferPlay]. Written only by the sync, which owns the wait; held here
         * so a play button can draw the wait instead of snapping from play to pause on the echo.
         */
        val awaitingStart: Boolean = false,
        /** The last thing that went wrong, for the page to show. */
        val error: String? = null,
    ) {
        val inParty: Boolean get() = code != null
        val connected: Boolean get() = connection == Connection.LIVE
        /** In a party whose socket is down, which the loop is already working on. */
        val reconnecting: Boolean get() = inParty && connection != Connection.LIVE
        val isFull: Boolean get() = members.size >= maxMembers

        /**
         * Whether this device may not drive the music.
         *
         * True only for a listener in a party whose host has taken control of it — the host is
         * never locked out of their own party. The server enforces the same rule, so this is what
         * the app shows rather than what makes it true. Host is reassigned when a host leaves, so
         * this can go false under a listener mid-party; everything reading it has to follow.
         */
        val controlsLocked: Boolean get() = inParty && hostOnlyControl && you?.isHost != true
    }

    /** A refusal from the server, carrying the machine-readable half. */
    class PartyException(
        val code: String,
        message: String,
        val statusCode: Int? = null,
    ) : Exception(message)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Where the party server lives: the build's own, unless the app's Info.plist names another.
     *
     * Never shown and never logged — see [redact]. The only address the app ever displays is one
     * the listener typed themselves.
     */
    private val DEFAULT_SERVER: String by lazy {
        val override = (NSBundle.mainBundle.objectForInfoDictionaryKey(PLIST_SERVER_KEY) as? String)
            ?.trim()
            .orEmpty()
        normalizeServerUrl(override.ifBlank { BUILT_IN_SERVER }).getOrNull()
            ?.ifBlank { null }
            ?: normalizeServerUrl(BUILT_IN_SERVER).getOrNull().orEmpty()
    }

    // Installed without defaults so the socket is never timed out by it: a party can sit paused
    // for ten minutes and the connection is not in trouble. Each plain request sets its own
    // bound. Darwin has no OkHttp-style ping interval either, so liveness is the app-level ping
    // frames below plus the server's own heartbeat.
    private val http = appHttpClient {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val clock = ServerClock()
    private val defaults = NSUserDefaults.standardUserDefaults
    /** Create, join, switch and leave each move the membership; one at a time. */
    private val membershipMutex = Mutex()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _customServer = MutableStateFlow(
        normalizeServerUrl(string(KEY_SERVER)).getOrNull().orEmpty(),
    )

    /** The listener's own server, or blank for the built-in one (which is never published). */
    val customServerUrl: StateFlow<String> = _customServer.asStateFlow()

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    /** Whether there is an address to talk to at all. */
    val hasServer: Boolean get() = DEFAULT_SERVER.isNotBlank() || _customServer.value.isNotBlank()

    @Volatile private var socketJob: Job? = null
    private var healthJob: Job? = null
    private var healthMonitorJob: Job? = null
    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var token: String? = null
    @Volatile private var effectiveIdleServerBase: String = ""
    @Volatile private var activePartyServerBase: String? = null
    private var screenActive = false
    private var lastHealthProbeMs = Long.MIN_VALUE / 2

    init {
        effectiveIdleServerBase = _customServer.value.ifBlank { DEFAULT_SERVER }
        // A process cannot resume the old socket safely, and leaving its token around occupies a
        // party slot until the server's grace period expires. Android hands that stale slot back
        // during init; this does the same before offering a new room.
        val staleCode = string(KEY_CODE)
        val staleToken = IosKeychain.get(KEY_TOKEN).orEmpty()
        val staleServer = string(KEY_ACTIVE_SERVER).ifBlank { DEFAULT_SERVER }
        defaults.removeObjectForKey(KEY_CODE)
        defaults.removeObjectForKey(KEY_ACTIVE_SERVER)
        IosKeychain.remove(KEY_TOKEN)
        if (staleCode.isNotBlank() && staleToken.isNotBlank()) {
            releaseSlot(staleServer, staleCode, staleToken)
        }
        startHealthMonitor()
        // One resolution per process, so create and join target the right server (custom or the
        // fallback) without the page having been opened first.
        refreshServerHealth()
    }

    // ------------------------------------------------------------ servers --

    /** Points this install at another server, or back at the built-in one if blank. */
    suspend fun setCustomServerUrl(value: String): Result<ServerStatus> = runCatching {
        check(!_state.value.inParty) { "Leave the current party before changing servers." }
        val normalized = normalizeServerUrl(value).getOrThrow()
        // The built-in address typed back in is the same as no custom server at all, and storing
        // it would put it on screen.
        val stored = if (normalized == DEFAULT_SERVER) "" else normalized
        defaults.setObject(stored, KEY_SERVER)
        _customServer.value = stored
        healthJob?.cancel()
        _serverStatus.value = ServerStatus(Health.CHECKING)
        withContext(Dispatchers.IO) { resolveIdleServer(forceProbe = true) }
        _serverStatus.value
    }

    /** Canonicalises a user-entered server without ever exposing the build's default address. */
    fun normalizeServerUrl(value: String): Result<String> = runCatching {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return@runCatching ""
        if (trimmed.any(Char::isWhitespace)) throw PartyException("bad_server", tr("Remove spaces from the server address."))
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        val scheme = candidate.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            throw PartyException("bad_server", tr("Use an http or https party server address."))
        }
        if (candidate.contains('?') || candidate.contains('#')) {
            throw PartyException("bad_server", tr("The server address can't include credentials, a query, or a fragment."))
        }
        // Read off the text rather than the parsed URL: Ktor refuses an out-of-range port with a
        // generic parse failure, and the listener deserves to be told which part was wrong.
        val authority = candidate.substringAfter("://").substringBefore('/')
        if ('@' in authority) {
            throw PartyException("bad_server", tr("The server address can't include credentials, a query, or a fragment."))
        }
        val portText = if (authority.startsWith("[")) {
            authority.substringAfter("]", "").removePrefix(":")
        } else if (authority.count { it == ':' } == 1) {
            authority.substringAfter(':')
        } else {
            ""
        }
        if (portText.isNotEmpty() && (portText.any { !it.isDigit() } || portText.toLongOrNull() !in 1L..65535L)) {
            throw PartyException("bad_server", tr("The server address has an invalid port."))
        }
        val url = runCatching { Url(candidate) }.getOrElse {
            throw PartyException("bad_server", tr("Enter a valid party server address."))
        }
        val rawHost = url.host.removePrefix("[").removeSuffix("]")
        if (rawHost.isBlank()) throw PartyException("bad_server", tr("Use an http or https party server address."))
        if (url.user != null) {
            throw PartyException("bad_server", tr("The server address can't include credentials, a query, or a fragment."))
        }
        val path = candidate.substringAfter("://").let { rest ->
            if ('/' in rest) "/" + rest.substringAfter('/') else ""
        }.trimEnd('/')
        if (path.split('/').any { it == "." || it == ".." }) {
            throw PartyException("bad_server", tr("The server address has an invalid path."))
        }
        val host = rawHost.lowercase()
        if (host != "localhost" && ':' !in host && '.' !in host) {
            throw PartyException("bad_server", tr("Enter a complete party server hostname."))
        }
        if (':' !in host && host != "localhost") {
            val validLabel = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
            if (host.split('.').any { it.isEmpty() || it.length > 63 || !validLabel.matches(it) }) {
                throw PartyException("bad_server", tr("Enter a valid party server hostname."))
            }
        }
        val hostPart = if (':' in host) "[$host]" else host
        val portPart = if (portText.isNotEmpty()) ":${portText.toInt()}" else ""
        "$scheme://$hostPart$portPart$path".trimEnd('/')
    }

    /** Re-checks the server on demand, as the page's Test does. */
    fun refreshServerHealth() {
        lastHealthProbeMs = clock.nowMs()
        healthJob?.cancel()
        _serverStatus.value = ServerStatus(Health.CHECKING)
        healthJob = scope.launch { withContext(Dispatchers.IO) { resolveIdleServer(forceProbe = true) } }
    }

    /**
     * Whether the page is on screen, which is the only time an idle health poll is worth making.
     *
     * Every poll is a request to a server shared by every install, so nothing is probed unless
     * somebody is looking at the status row, never while a live socket already proves the server
     * is up, and never off-party on a metered connection.
     */
    fun setScreenActive(active: Boolean) {
        screenActive = active
        if (active && !shouldSkipIdleProbe()) quietRefresh()
    }

    /** Probes [serverUrl] once, for the server editor's Test. Never touches the stored setting. */
    suspend fun probeHealthWithLatency(serverUrl: String, timeoutMs: Long = EDITOR_PROBE_TIMEOUT_MS): ProbeResult {
        val base = normalizeServerUrl(serverUrl).getOrNull().orEmpty().ifBlank { DEFAULT_SERVER }
        if (base.isBlank()) return ProbeResult(false)
        val (online, latency) = withContext(Dispatchers.IO) { probeHealth(base, timeoutMs) }
        return ProbeResult(online, latency)
    }

    private fun shouldSkipIdleProbe(): Boolean {
        if (!screenActive) return true
        val snapshot = _state.value
        if (snapshot.connection == Connection.LIVE) return true
        if (!snapshot.inParty && IosNetwork.metered.value == true) return true
        return false
    }

    /** A background re-check: no "Checking…" flash, and debounced against screen toggles. */
    private fun quietRefresh() {
        val now = clock.nowMs()
        if (now - lastHealthProbeMs < HEALTH_MIN_GAP_MS) return
        lastHealthProbeMs = now
        if (healthJob?.isActive == true) return
        healthJob = scope.launch { withContext(Dispatchers.IO) { resolveIdleServer(forceProbe = true) } }
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                delay(HEALTH_POLL_INTERVAL_MS)
                if (!shouldSkipIdleProbe()) quietRefresh()
            }
        }
    }

    // ----------------------------------------------------------- identity --

    fun nickname(): String = string(KEY_NICKNAME)

    fun setNickname(value: String) = defaults.setObject(value.trim().take(80), KEY_NICKNAME)

    /** The picture the rest of the party will see, as [identity] would send it. */
    fun myAvatarUrl(): String? = identity()?.avatar

    /** Whether this device can join at all — a party is joined as an account, not anonymously. */
    fun canJoin(): Boolean = identity() != null

    /**
     * The public invite for the server that actually owns the current membership.
     *
     * Always on the official origin, so it opens the app wherever it is tapped; a party on a
     * server of the listener's own carries that server along as `?server=`, which is the shape
     * [parseInvite] (and Android's `JamInviteLink`) reads back. The built-in server is never named.
     */
    fun inviteUrl(code: String): String {
        val server = activePartyServerBase
        val base = JamInvite.url(code)
        return if (server.isNullOrBlank() || server == DEFAULT_SERVER) base
        else "$base?server=${server.encodeURLParameter()}"
    }

    /**
     * Parses an incoming invite, in either shape:
     *  1. `bitchord://party/<CODE>?server=<SERVER>` (or `bitchord://party?code=<CODE>`)
     *  2. `https://bitchord.kushagrasingh.in/invite/<CODE>?server=<SERVER>`
     *
     * Anything else is refused outright rather than half-parsed into a join attempt.
     */
    fun parseInvite(value: String?): ParsedInvite? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ("://" !in text || text.any(Char::isWhitespace)) return null
        val scheme = text.substringBefore("://").lowercase()
        // Read off the text: Ktor's parser is built for http(s), and the custom scheme's "host"
        // is really the route name.
        val host = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        if (scheme == "https" && runCatching { Url(text) }.getOrNull()?.host?.lowercase() != host) return null
        val query = text.substringAfter('?', "").substringBefore('#')
        val server = queryParam(query, "server")?.let { normalizeServerUrl(it).getOrNull() }?.ifBlank { null }
        val path = text.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')

        if (scheme == CUSTOM_SCHEME && host == CUSTOM_HOST) {
            val candidate = path.trim('/').takeIf { it.isNotBlank() } ?: queryParam(query, "code") ?: return null
            val code = candidate.filter(Char::isLetterOrDigit).uppercase()
            if (code.length != CODE_LENGTH) return null
            return ParsedInvite(code, server)
        }
        if (scheme == "https" && host == INVITE_HOST) {
            val match = INVITE_PATH.matchEntire("/$path") ?: return null
            return ParsedInvite(match.groupValues[1].uppercase(), server)
        }
        return null
    }

    /**
     * Whether opening an invite means talking to a different server than this device uses now,
     * and if so which: null when no switch is needed, blank for the built-in server (an invite
     * naming none, while a custom one is set), or the custom address the invite named.
     *
     * Asked before anything is contacted, so the page can get consent first — the built-in
     * address itself never leaves this object.
     */
    fun serverSwitchFor(inviteServer: String?): String? {
        val target = inviteServer?.let { normalizeServerUrl(it).getOrNull() }
            ?.ifBlank { null }
            ?.takeIf { it != DEFAULT_SERVER }
        val current = activePartyServerBase ?: _customServer.value.ifBlank { DEFAULT_SERVER }
        return when {
            target != null -> target.takeIf { it != current }
            current != DEFAULT_SERVER -> ""
            else -> null
        }
    }

    // ------------------------------------------------------------ joining --

    suspend fun createParty(
        nickname: String = nickname(),
        maxMembers: Int = 5,
        autoplayEnabled: Boolean = IosSettings.autoplay.flow.value,
    ): Result<String> = enter(nickname) { who, server ->
        post(
            "$server/api/parties",
            JoinRequest(
                who.userId,
                who.deviceId,
                who.name,
                who.avatar,
                maxMembers.coerceIn(2, 10),
                autoplayEnabled,
            ),
        )
    }

    suspend fun joinParty(code: String, nickname: String = nickname()): Result<String> {
        val trimmed = cleanCode(code)
        if (trimmed.length != CODE_LENGTH) {
            return Result.failure(PartyException("bad_code", tr("A party code is six letters or digits.")))
        }
        return runCatching { refuseIfRecentlyKicked(trimmed) }.fold(
            onSuccess = {
                enter(nickname) { who, server ->
                    post(
                        "$server/api/parties/$trimmed/join",
                        JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun enter(
        nickname: String,
        request: suspend (Identity, String) -> PartyMembership,
    ): Result<String> = membershipMutex.withLock {
        val who = identity(nickname)
            ?: return@withLock Result.failure(PartyException("not_signed_in", tr("Sign in to listen together.")))
        if (!hasServer) {
            return@withLock Result.failure(PartyException("no_server", tr("Set the party server address first.")))
        }
        var usedFallback = false
        val primary = withContext(Dispatchers.IO) { resolveIdleServer() }
        val attempt = withContext(Dispatchers.IO) {
            val first = runCatching { request(who, primary) }
            if (
                first.isFailure &&
                primary != DEFAULT_SERVER &&
                DEFAULT_SERVER.isNotBlank() &&
                first.exceptionOrNull()?.let(::isEligibleForFallback) == true
            ) {
                TrackLog.w(TAG, "custom server failed, trying the built-in one: ${redact(first.exceptionOrNull()?.message)}")
                usedFallback = true
                runCatching { request(who, DEFAULT_SERVER) }
            } else {
                first
            }
        }
        attempt.onSuccess { membership ->
            if (usedFallback) {
                effectiveIdleServerBase = DEFAULT_SERVER
                _serverStatus.value = ServerStatus(Health.ONLINE, isFallback = true)
            }
            commit(membership, if (usedFallback) DEFAULT_SERVER else primary)
            connect()
        }.onFailure { failure ->
            TrackLog.w(TAG, "could not enter a party: ${redact(failure.message)}")
            _state.update { it.copy(error = failure.displayMessage()) }
        }.map { it.code }
    }

    /**
     * Moves to an invite's party, which may be on another server, without letting go of the
     * current one until the new one has said yes.
     *
     * The target is joined first, while this device stays in (and keeps playing) its current
     * party; only once that succeeds is the old slot released and the membership switched. A
     * refusal therefore leaves everything exactly as it was. [targetServer] null means the server
     * idle joins already use, blank means the built-in one (see [serverSwitchFor]), anything else
     * is the address an invite named. This strictly contacts the target and never falls back.
     */
    suspend fun switchParty(
        targetServer: String?,
        code: String,
        nickname: String = nickname(),
    ): Result<String> = membershipMutex.withLock {
        runCatching {
            val who = identity(nickname) ?: throw PartyException("not_signed_in", tr("Sign in to listen together."))
            val target = when {
                targetServer == null -> _customServer.value.ifBlank { effectiveIdleServerBase.ifBlank { DEFAULT_SERVER } }
                targetServer.isBlank() -> DEFAULT_SERVER
                else -> normalizeServerUrl(targetServer).getOrNull()?.ifBlank { null }
                    ?: throw PartyException("bad_server", tr("Target server address is invalid."))
            }
            if (target.isBlank()) throw PartyException("no_server", tr("Target server address is invalid or missing."))
            val cleaned = cleanCode(code)
            if (cleaned.length != CODE_LENGTH) throw PartyException("bad_code", tr("A party code is six letters or digits."))
            refuseIfRecentlyKicked(cleaned)

            val membership = withContext(Dispatchers.IO) {
                post("$target/api/parties/$cleaned/join", JoinRequest(who.userId, who.deviceId, who.name, who.avatar))
            }

            withContext(NonCancellable) {
                val oldCode = _state.value.code
                val oldToken = token
                val oldServer = activePartyServerBase
                if (!oldCode.isNullOrBlank() && !oldToken.isNullOrBlank() && !oldServer.isNullOrBlank() &&
                    (oldServer != target || !oldCode.equals(membership.code, ignoreCase = true))
                ) {
                    releaseSlot(oldServer, oldCode, oldToken)
                }
                socketJob?.cancel()
                socketJob = null
                session = null
                // The invite named where the party lives; idle joins follow it from now on, as on
                // the phone. The built-in address is stored as blank so it never reaches the page.
                val stored = if (target == DEFAULT_SERVER) "" else target
                if (stored != _customServer.value) {
                    defaults.setObject(stored, KEY_SERVER)
                    _customServer.value = stored
                }
                effectiveIdleServerBase = target
                commit(membership, target)
            }
            connect()
            membership.code
        }.onFailure { failure ->
            TrackLog.w(TAG, "could not switch parties: ${redact(failure.message)}")
        }.recoverCatching { failure ->
            throw if (failure is PartyException) failure else PartyException("unreachable", UNREACHABLE)
        }
    }

    /** Takes on a membership the server has just granted. Main thread. */
    private fun commit(membership: PartyMembership, server: String) {
        activePartyServerBase = server
        token = membership.token
        defaults.setObject(membership.code, KEY_CODE)
        defaults.setObject(server, KEY_ACTIVE_SERVER)
        IosKeychain.set(KEY_TOKEN, membership.token)
        clock.reset()
        _state.value = State(
            code = membership.code,
            you = membership.you,
            members = membership.party.members,
            maxMembers = membership.party.maxMembers,
            hostOnlyControl = membership.party.hostOnlyControl,
            playback = membership.party.playback,
            queue = membership.party.queue,
            connection = Connection.CONNECTING,
        )
    }

    /** Give up this device's slot. The party carries on without it. */
    suspend fun leaveParty() = membershipMutex.withLock {
        val code = _state.value.code
        val held = token
        val server = activePartyServerBase ?: effectiveIdleServerBase
        socketJob?.cancel()
        socketJob = null
        session = null
        clock.reset()
        token = null
        activePartyServerBase = null
        defaults.removeObjectForKey(KEY_CODE)
        defaults.removeObjectForKey(KEY_ACTIVE_SERVER)
        IosKeychain.remove(KEY_TOKEN)
        _state.value = State()
        if (code != null && held != null && server.isNotBlank()) {
            withContext(Dispatchers.IO) { releaseSlotNow(server, code, held) }
        }
        Unit
    }

    /** Opens the socket for a membership that has none, as after the system suspended the app. */
    fun ensureConnected() {
        if (_state.value.code == null || token == null) return
        if (socketJob?.isActive == true) return
        connect()
    }

    // ------------------------------------------------------------ controls --
    //
    // Any member may send any of these unless the host has taken control of the party — see
    // [State.controlsLocked]. The server enforces that independently, so a control sent anyway
    // comes back refused rather than quietly obeyed.

    fun play(positionMs: Long? = null) = control("play") { positionMs?.let { put("positionMs", it) } }

    fun pause(positionMs: Long? = null) = control("pause") { positionMs?.let { put("positionMs", it) } }

    fun seek(positionMs: Long) = control("seek") { put("positionMs", positionMs) }

    fun next() = control("next") {}

    fun previous() = control("previous") {}

    fun setTrack(track: PartyTrack, positionMs: Long = 0, isPlaying: Boolean = true) =
        control("setTrack") {
            put("track", json.encodeToJsonElement(PartyTrack.serializer(), track))
            put("positionMs", positionMs)
            put("isPlaying", isPlaying)
        }

    fun setQueue(queue: List<PartyTrack>, index: Int) = control("setQueue") {
        put("queue", json.encodeToJsonElement(ListSerializer(PartyTrack.serializer()), queue))
        put("queueIndex", index)
    }

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) = control("queueAdd") {
        put("tracks", json.encodeToJsonElement(ListSerializer(PartyTrack.serializer()), tracks))
        put("playNext", playNext)
    }

    fun queueRemove(videoId: String) = control("queueRemove") { put("videoId", videoId) }

    fun queueClear() = control("queueClear") {}

    fun queueMove(fromIndex: Int, toIndex: Int, videoId: String? = null) = control("queueMove") {
        put("fromIndex", fromIndex)
        put("toIndex", toIndex)
        videoId?.let { put("videoId", it) }
    }

    fun setMaxMembers(value: Int) = control("setMaxMembers") { put("maxMembers", value.coerceIn(2, 10)) }

    fun kick(memberId: String) = control("kick") { put("memberId", memberId) }

    fun setAutoplay(enabled: Boolean) = control("setAutoplay") { put("enabled", enabled) }

    /** Host only, and refused by the server from anybody else. @see State.controlsLocked */
    fun setHostOnlyControl(enabled: Boolean) =
        control("setHostOnlyControl") { put("enabled", enabled) }

    /** @see State.awaitingStart */
    fun setAwaitingStart(value: Boolean) {
        if (_state.value.awaitingStart != value) _state.update { it.copy(awaitingStart = value) }
    }

    private fun control(action: String, body: JsonObjectBuilder.() -> Unit) {
        if (_state.value.controlsLocked) return
        send(buildJsonObject {
            put("type", "control")
            put("action", action)
            body()
        })
    }

    private fun send(frame: JsonObject) {
        val live = session ?: return
        scope.launch(Dispatchers.Default) {
            runCatching { live.send(Frame.Text(frame.toString())) }
                .onFailure { TrackLog.w(TAG, "control not sent: ${redact(it.message)}") }
        }
    }

    // --------------------------------------------------------- the playhead --

    /**
     * Where the party's playhead is right now, on this device's reading of the server's clock.
     *
     * Null when nothing is playing. Before the clock has synced this falls back to the anchor the
     * server stated, which is a guess but a stable one — wait for [State.clockSynced] before
     * seeking to it.
     */
    fun partyPositionMs(): Long? {
        val playback = _state.value.playback
        val track = playback.track ?: return null
        if (!playback.isPlaying) return playback.positionMs
        val serverNow = clock.serverNowMs() ?: return playback.effectivePositionMs
        val elapsed = (serverNow - playback.anchorMs).coerceAtLeast(0)
        val position = playback.positionMs + elapsed
        val duration = track.durationMs
        return if (duration != null) minOf(position, duration) else position
    }

    /** How long until the party's start instant, for a device that arrived early. */
    fun msUntilStart(): Long {
        val playback = _state.value.playback
        if (!playback.isPlaying) return 0
        val serverNow = clock.serverNowMs() ?: return 0
        return (playback.anchorMs - serverNow).coerceAtLeast(0)
    }

    // ------------------------------------------------------------- socket --

    private fun connect() {
        socketJob?.cancel()
        socketJob = scope.launch(Dispatchers.Default) { runSocketLoop() }
    }

    /**
     * Connect, pump frames until the connection goes away for any reason, back off, go round again.
     *
     * The loop is the design, not error handling bolted onto one: a socket held by a backgrounded
     * phone is dropped repeatedly over a listening session, and every drop has to heal without
     * anyone reopening the app. The membership outlives the socket — the server holds the slot
     * through a grace period — so reconnecting is invisible to everyone else.
     */
    private suspend fun runSocketLoop() {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            val code = _state.value.code ?: return
            val held = token ?: return
            val server = activePartyServerBase ?: return
            try {
                onMain { _state.update { it.copy(connection = Connection.CONNECTING) } }
                http.webSocket(
                    urlString = "${wsBase(server)}/ws/parties/$code",
                    request = { header("Authorization", "Bearer $held") },
                ) {
                    session = this
                    backoffMs = 1_000L
                    onMain { _state.update { it.copy(connection = Connection.LIVE, error = null) } }
                    launch { pingLoop() }
                    launch { reportLoop() }
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val received = clock.nowMs()
                            onMain { onFrame(text, received) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TrackLog.w(TAG, "party socket dropped: ${redact(failure.message)}")
            } finally {
                session = null
            }
            if (!currentCoroutineContext().isActive) return
            // The clock is not carried across a gap: whatever the offset was before the socket
            // died, the only honest thing to say afterwards is that it has not been measured since.
            clock.reset()
            onMain { _state.update { it.copy(connection = Connection.CONNECTING, clockSynced = false) } }
            // Jittered, or every client that lost the socket in the same outage retries at the
            // same instants and a recovering server is hit by the whole crowd at once.
            delay(backoffMs / 2 + Random.nextLong(backoffMs + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** A burst on connect converges the offset faster than a long series would. */
    private suspend fun DefaultClientWebSocketSession.pingLoop() {
        repeat(4) {
            ping()
            delay(300)
        }
        while (true) {
            delay(PING_INTERVAL_MS)
            ping()
        }
    }

    private suspend fun DefaultClientWebSocketSession.ping() {
        val sentAt = clock.nowMs()
        val frame = buildJsonObject {
            put("type", "ping")
            put("clientMs", sentAt)
        }
        runCatching { send(Frame.Text(frame.toString())) }
    }

    /**
     * Tells the server where this device is. Nothing is decided by it — the server's state is the
     * truth — but it keeps the membership alive and makes real drift visible in the server log.
     */
    private suspend fun DefaultClientWebSocketSession.reportLoop() {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            val position = partyPositionMs() ?: continue
            val frame = buildJsonObject {
                put("type", "report")
                put("positionMs", position)
                put("isPlaying", _state.value.playback.isPlaying)
            }
            runCatching { send(Frame.Text(frame.toString())) }
        }
    }

    private fun onFrame(text: String, received: Long) {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (frame["type"]?.jsonPrimitive?.content) {
            "welcome" -> {
                val party = frame["party"]?.let {
                    runCatching { json.decodeFromJsonElement(PartySnapshot.serializer(), it) }.getOrNull()
                } ?: return
                val you = frame["you"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyMember.serializer(), it) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        code = party.code,
                        you = you ?: it.you,
                        members = party.members,
                        maxMembers = party.maxMembers,
                        hostOnlyControl = party.hostOnlyControl,
                        playback = party.playback,
                        // The one message that carries the queue unconditionally — a device that
                        // has just arrived has no other way to learn it.
                        queue = party.queue,
                        connection = Connection.LIVE,
                        error = null,
                    )
                }
            }

            "pong" -> {
                val sentAt = frame["clientMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val serverMs = frame["serverMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                clock.record(sentAt, serverMs, received)
                _state.update { it.copy(clockSynced = clock.synced, roundTripMs = clock.roundTripMs) }
            }

            "state" -> {
                val playback = frame["playback"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyPlayback.serializer(), it) }.getOrNull()
                } ?: return
                // An out-of-order frame is older news than what is already held.
                if (playback.seq < _state.value.playback.seq) return
                _state.update { it.copy(playback = playback) }
                // Only the queue's sequence number rides along. Disagreeing with the copy held
                // here means a queue frame was missed, which the heartbeat heals within seconds.
                if (playback.queueSeq != _state.value.queue.seq) {
                    send(buildJsonObject { put("type", "syncQueue") })
                }
            }

            "queue" -> {
                val queue = frame["queue"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyQueue.serializer(), it) }.getOrNull()
                } ?: return
                if (queue.seq < _state.value.queue.seq) return
                _state.update { it.copy(queue = queue) }
            }

            "members" -> {
                val members = frame["members"]?.let {
                    runCatching {
                        json.decodeFromJsonElement(ListSerializer(PartyMember.serializer()), it)
                    }.getOrNull()
                } ?: return
                val maxMembers = frame["maxMembers"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: _state.value.maxMembers
                // Absent on a server that predates the setting, which is not "off": keeping the
                // value held means an upgrade mid-party does not silently unlock the party.
                val hostOnly = frame["hostOnlyControl"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: _state.value.hostOnlyControl
                _state.update { current ->
                    current.copy(
                        members = members,
                        maxMembers = maxMembers,
                        hostOnlyControl = hostOnly,
                        // The only place a promotion is announced: left unread, a listener handed
                        // the host role would stay locked out of a party they now own.
                        you = current.you
                            ?.let { mine -> members.firstOrNull { it.memberId == mine.memberId } }
                            ?: current.you,
                    )
                }
            }

            "activity" -> {
                val action = frame["action"]?.jsonPrimitive?.content ?: return
                val by = frame["by"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return
                val atMs = frame["atMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: wallClockMs()
                val detail = frame["detail"]?.jsonPrimitive?.content.orEmpty()
                _state.update {
                    it.copy(activity = (listOf(PartyActivity(action, by, atMs, detail)) + it.activity).take(100))
                }
            }

            "error" -> {
                val reason = frame["error"]?.jsonPrimitive?.content
                val message = frame["message"]?.jsonPrimitive?.content
                TrackLog.w(TAG, "party server refused a frame: $reason ${redact(message)}")
                _state.update { it.copy(error = message) }
                // Terminal, and the reconnect loop cannot learn that on its own — it would keep
                // dialling a party that no longer knows this device.
                if (reason == "bad_token" || reason == "no_such_party") scope.launch { leaveParty() }
            }

            "bye" -> {
                // A removal is the one kind worth remembering: the code goes on this device's own
                // shut-out list before the state carrying it is torn down.
                if (frame["reason"]?.jsonPrimitive?.content == "kicked") {
                    _state.value.code?.let(::recordKick)
                }
                scope.launch { leaveParty() }
            }
        }
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    // ----------------------------------------------------------- identity --

    private data class Identity(
        val userId: String,
        val deviceId: String,
        val name: String,
        val avatar: String?,
    )

    /**
     * Who this device jams as, or null when it cannot.
     *
     * A party is joined as an account: the id is a hash of the signed-in account and profile so
     * the server never sees either, and a device with no session has nothing to identify itself by.
     */
    private fun identity(nickname: String = nickname()): Identity? {
        if (!IosAccount.signedIn.value) return null
        val accountId = IosAccount.activeAccountId.value ?: return null
        val account = IosAccount.accounts.value.firstOrNull { it.id == accountId } ?: return null
        val profile = account.activeProfile
        val name = nickname.trim().takeIf { it.isNotBlank() }
            ?: profile?.name?.takeIf { it.isNotBlank() }
            ?: account.name.takeIf { it.isNotBlank() }
            ?: account.email.substringBefore('@').takeIf { it.isNotBlank() }
            ?: return null
        return Identity(
            userId = sha256("$accountId:${profile?.id.orEmpty()}").take(32),
            deviceId = deviceId(),
            name = name,
            avatar = profile?.avatar?.takeIf { it.startsWith("http") },
        )
    }

    /**
     * This installation, remembered so a rejoin is recognised as the same device. Survives a
     * sign-out and an account switch, not an uninstall — the server counts devices, not people.
     */
    private fun deviceId(): String {
        string(KEY_DEVICE).takeIf { it.isNotBlank() }?.let { return it }
        return NSUUID().UUIDString.also { defaults.setObject(it, KEY_DEVICE) }
    }

    // --------------------------------------------------------------- HTTP --

    private suspend fun post(url: String, body: JoinRequest): PartyMembership {
        val response = http.post(url) {
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) throw response.toPartyException()
        return response.body()
    }

    /**
     * Who is in a party, before committing a slot to it. [server] is as [switchParty] reads it:
     * null for the idle server, blank for the built-in one, or the address an invite named. A party this device was recently removed from is refused here too,
     * so the refusal arrives while the code is still on screen.
     */
    suspend fun previewParty(code: String, server: String? = null): Result<PartyPreview> {
        val cleaned = cleanCode(code)
        if (cleaned.length != CODE_LENGTH) {
            return Result.failure(PartyException("bad_code", tr("A party code is six letters or digits.")))
        }
        return runCatching {
            refuseIfRecentlyKicked(cleaned)
            withContext(Dispatchers.IO) {
                val base = when {
                    server == null -> resolveIdleServer()
                    server.isBlank() -> DEFAULT_SERVER
                    else -> normalizeServerUrl(server).getOrNull()?.ifBlank { null }
                        ?: throw PartyException("bad_server", tr("Target server address is invalid."))
                }
                if (base.isBlank()) throw PartyException("no_server", tr("Set the party server address first."))
                val response = http.get("$base/api/parties/$cleaned/preview") {
                    timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
                }
                if (!response.status.isSuccess()) {
                    val problem = response.toPartyException()
                    // Older self-hosted servers did not expose previews: their router answers in
                    // plain text, so nothing parses and the code falls back to the status. The
                    // confirmation still works there; it simply cannot show members.
                    if (problem.statusCode == 404 && problem.code == "http_404") {
                        return@withContext PartyPreview(code = cleaned)
                    }
                    throw problem
                }
                response.body<PartyPreview>()
            }
        }.onFailure { failure ->
            TrackLog.w(TAG, "preview failed: ${redact(failure.message)}")
        }.recoverCatching { failure ->
            throw if (failure is PartyException) failure else PartyException("unreachable", UNREACHABLE)
        }
    }

    private fun cleanCode(code: String): String =
        code.filter(Char::isLetterOrDigit).uppercase().take(CODE_LENGTH)

    private suspend fun HttpResponse.toPartyException(): PartyException {
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return PartyException(
            code = parsed?.code.orEmpty().ifBlank { "http_${status.value}" },
            message = parsed?.message?.takeIf { it.isNotBlank() }
                ?: if (status.value == 422) tr("This account can't be used to jam.")
                else tr("The party server said {0}.", status.value),
            statusCode = status.value,
        )
    }

    /** Hands a slot back now, so the others see this device leave rather than wait for the sweep. */
    private fun releaseSlot(server: String, code: String, held: String) {
        scope.launch { withContext(Dispatchers.IO) { releaseSlotNow(server, code, held) } }
    }

    private suspend fun releaseSlotNow(server: String, code: String, held: String) {
        runCatching {
            http.post("$server/api/parties/$code/leave") {
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
                header("Authorization", "Bearer $held")
            }
        }.onFailure { TrackLog.i(TAG, "party slot left to the server's grace: ${redact(it.message)}") }
    }

    private suspend fun resolveIdleServer(forceProbe: Boolean = false): String {
        if (!forceProbe && _serverStatus.value.health == Health.ONLINE) return effectiveIdleServerBase
        val custom = normalizeServerUrl(_customServer.value).getOrNull().orEmpty()
        if (custom.isNotBlank() && custom != DEFAULT_SERVER) {
            val customProbe = probeHealth(custom, CUSTOM_SERVER_TIMEOUT_MS)
            if (customProbe.first) {
                publishResolution(custom, ServerStatus(Health.ONLINE, customProbe.second))
                return custom
            }
            if (DEFAULT_SERVER.isNotBlank()) {
                val fallback = probeHealth(DEFAULT_SERVER, DEFAULT_SERVER_TIMEOUT_MS)
                publishResolution(
                    DEFAULT_SERVER,
                    if (fallback.first) ServerStatus(Health.ONLINE, fallback.second, isFallback = true)
                    else ServerStatus(Health.OFFLINE),
                )
                return DEFAULT_SERVER
            }
            publishResolution(custom, ServerStatus(Health.OFFLINE))
            return custom
        }

        if (DEFAULT_SERVER.isBlank()) {
            publishResolution("", ServerStatus(Health.OFFLINE))
            return ""
        }
        val probe = probeHealth(DEFAULT_SERVER, DEFAULT_SERVER_TIMEOUT_MS)
        publishResolution(
            DEFAULT_SERVER,
            if (probe.first) ServerStatus(Health.ONLINE, probe.second) else ServerStatus(Health.OFFLINE),
        )
        return DEFAULT_SERVER
    }

    private suspend fun publishResolution(server: String, status: ServerStatus) {
        effectiveIdleServerBase = server
        onMain { _serverStatus.value = status }
    }

    private suspend fun probeHealth(server: String, timeoutMs: Long): Pair<Boolean, Long> {
        val started = clock.nowMs()
        val online = runCatching {
            val response = http.get("$server/healthz") {
                timeout { requestTimeoutMillis = timeoutMs }
            }
            response.status.isSuccess() && response.bodyAsText().contains(HEALTH_OK)
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            TrackLog.w(TAG, "health check failed: ${redact(failure.message)}")
            false
        }
        return online to if (online) (clock.nowMs() - started).coerceAtLeast(0L) else 0L
    }

    /**
     * Whether a failure on the custom server means it is unreachable — worth retrying on the
     * built-in one — rather than a refusal it actually gave.
     *
     * Darwin reports transport failures as `DarwinHttpRequestException`, an IO exception wrapping
     * an `NSError`, so the type is checked first and its text second.
     */
    private fun isEligibleForFallback(error: Throwable): Boolean = when (error) {
        is PartyException -> (error.statusCode ?: 0) in 500..599
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        is kotlinx.io.IOException -> true
        else -> error.message?.lowercase()?.let { text -> CONNECTION_FAILURE_HINTS.any { it in text } } == true ||
            error.cause?.takeIf { it !== error }?.let(::isEligibleForFallback) == true
    }

    private fun wsBase(server: String): String = server.replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    // ------------------------------------------------------- recent kicks --

    /**
     * Parties this device was removed from, and when it may ask again: code → the epoch
     * millisecond it lapses, pruned on every read. Held here rather than on the server, which
     * forgets a party minutes after it empties.
     */
    private fun recentKicks(): Map<String, Long> {
        val encoded = string(KEY_KICKED)
        if (encoded.isBlank()) return emptyMap()
        val stored = runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), encoded)
        }.getOrDefault(emptyMap())
        val now = wallClockMs()
        val live = stored.filterValues { it > now }
        if (live.size != stored.size) writeKicks(live)
        return live
    }

    private fun writeKicks(entries: Map<String, Long>) {
        if (entries.isEmpty()) {
            defaults.removeObjectForKey(KEY_KICKED)
            return
        }
        defaults.setObject(
            json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), entries),
            KEY_KICKED,
        )
    }

    private fun recordKick(code: String) {
        val normalized = cleanCode(code)
        if (normalized.isBlank()) return
        writeKicks(recentKicks() + (normalized to wallClockMs() + KICK_BLOCK_MS))
    }

    /** Says it was removed and not for how long: a countdown is an invitation to wait it out. */
    private fun refuseIfRecentlyKicked(code: String) {
        if (cleanCode(code) in recentKicks()) {
            throw PartyException(
                "recently_kicked",
                tr("Couldn't let you in — you've recently been removed from this party."),
            )
        }
    }

    // ------------------------------------------------------------ plumbing --

    /**
     * Keeps the server's address out of the log and off the page.
     *
     * Failures from the HTTP and WebSocket layers name the host they were talking to, and a
     * failing request's URL is not diagnostic enough to be worth printing when it identifies where
     * somebody's party lives.
     */
    private fun redact(text: String?): String {
        var out = text.orEmpty()
        if (out.isEmpty()) return out
        listOfNotNull(DEFAULT_SERVER, _customServer.value, activePartyServerBase)
            .filter { it.isNotBlank() }
            .flatMap { listOf(it, it.substringAfter("://")) }
            .sortedByDescending(String::length)
            .forEach { out = out.replace(it, SERVER_PLACEHOLDER, ignoreCase = true) }
        return out.replace(ABSOLUTE_URL, SERVER_PLACEHOLDER)
    }

    private fun Throwable.displayMessage(): String = when (this) {
        is PartyException -> message.orEmpty().ifBlank { UNREACHABLE }
        else -> UNREACHABLE
    }

    private fun queryParam(query: String, name: String): String? =
        query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0].equals(name, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let { runCatching { it.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(it) }

    private fun string(key: String): String = defaults.stringForKey(key).orEmpty()

    @OptIn(ExperimentalForeignApi::class)
    private fun sha256(value: String): String {
        val bytes = value.encodeToByteArray()
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        bytes.usePinned { input ->
            digest.usePinned { output ->
                CC_SHA256(
                    if (bytes.isEmpty()) null else input.addressOf(0),
                    bytes.size.convert(),
                    output.addressOf(0),
                )
            }
        }
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    private const val TAG = "IosListenTogether"
    // The server the released phone and desktop apps are built against (their LISTEN_TOGETHER_SERVER),
    // not the source's placeholder: a party only reaches the devices that share its server.
    private const val BUILT_IN_SERVER = "https://api.bitchord.kushagrasingh.in"
    private const val PLIST_SERVER_KEY = "BitChordListenTogetherServer"
    private const val PREFIX = "bitchord.party."
    private const val KEY_SERVER = "${PREFIX}server_url"
    private const val KEY_CODE = "${PREFIX}party_code"
    private const val KEY_TOKEN = "${PREFIX}party_token"
    private const val KEY_DEVICE = "${PREFIX}device_id"
    private const val KEY_NICKNAME = "${PREFIX}party_nickname"
    private const val KEY_KICKED = "${PREFIX}party_kicked_until"
    /** Which server the stale slot is on, so the next launch releases it in the right place. */
    private const val KEY_ACTIVE_SERVER = "${PREFIX}active_server"
    private const val SERVER_PLACEHOLDER = "<party server>"
    private const val UNREACHABLE = "Couldn't reach the party server."
    private const val PING_INTERVAL_MS = 15_000L
    private const val REPORT_INTERVAL_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val CUSTOM_SERVER_TIMEOUT_MS = 6_000L
    private const val DEFAULT_SERVER_TIMEOUT_MS = 15_000L
    /** The editor's Test was asked for, so it waits longer than the background check does. */
    const val EDITOR_PROBE_TIMEOUT_MS = 8_000L
    /** Polled only while the page is open and no socket is live, so 10 s costs nothing otherwise. */
    private const val HEALTH_POLL_INTERVAL_MS = 10_000L
    /** Debounces screen toggles; at or below the poll interval, or it silently stretches it. */
    private const val HEALTH_MIN_GAP_MS = 5_000L
    private const val KICK_BLOCK_MS = 24L * 60 * 60 * 1000
    private const val HEALTH_OK = "\"ok\":true"
    private const val CUSTOM_SCHEME = "bitchord"
    private const val CUSTOM_HOST = "party"
    private const val INVITE_HOST = "bitchord.kushagrasingh.in"
    const val CODE_LENGTH = JamInvite.CODE_LENGTH

    private val INVITE_PATH = Regex("""/invite/([A-Za-z0-9]{$CODE_LENGTH})/?""")
    private val ABSOLUTE_URL = Regex("""(?:https?|wss?)://[^\s,;)\]}'"]+""", RegexOption.IGNORE_CASE)

    /** What NSURLErrorDomain failures say when the server simply cannot be reached. */
    private val CONNECTION_FAILURE_HINTS = listOf(
        "could not connect",
        "cannot connect",
        "connection refused",
        "connection was lost",
        "timed out",
        "offline",
        "cannot find host",
        "hostname could not be found",
        "network is unreachable",
        "dns",
    )
}
