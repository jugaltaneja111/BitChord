package com.music.bitchord.ios

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A session lifted out of the sign-in page: the cookie, and who the page said it was. The same
 * fields the phone captures (app/…/auth/WebSession.kt's CapturedSession).
 */
@Serializable
internal data class IosSession(
    val cookie: String,
    /** `DELEGATED_SESSION_ID`: set only while a brand channel is selected. */
    val pageId: String? = null,
    val dataSyncId: String? = null,
    /** `SESSION_INDEX`: which Google account in the cookie jar. */
    val authUser: String? = null,
    val visitorData: String? = null,
    val clientVersion: String? = null,
    val name: String = "",
    val email: String = "",
    val avatar: String? = null,
)

/** One Personal or Brand channel a Google login can act as (app/…/auth/AccountSessions.kt). */
@Serializable
internal data class IosProfile(
    val id: String,
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val brand: Boolean = false,
)

/** A Google login, kept apart from every other one: its own cookie, its own channels. */
@Serializable
internal data class IosGoogleAccount(
    val id: String,
    val cookie: String,
    val name: String = "",
    val email: String = "",
    val authUser: String? = null,
    val visitorData: String? = null,
    val clientVersion: String? = null,
    val profiles: List<IosProfile> = emptyList(),
    val activeProfileId: String? = null,
) {
    val activeProfile: IosProfile? get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}

@Serializable
private data class IosAccountStore(
    val accounts: List<IosGoogleAccount> = emptyList(),
    val activeAccountId: String? = null,
)

/**
 * The YouTube Music accounts, as the phone's MainViewModel keeps them: any number of Google
 * logins, each with its personal and brand channels, one of them acting at a time. A login is
 * checked against the API before it is kept. Main thread only.
 */
internal object IosAccount {
    private const val TAG = "IosAccount"
    private const val KEY_ACCOUNTS = "youtube_music_accounts_v2"
    private const val KEY_SESSION_V1 = "youtube_music_session_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    private val _accounts = MutableStateFlow<List<IosGoogleAccount>>(emptyList())
    val accounts: StateFlow<List<IosGoogleAccount>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    /** Raised on every sign-in, sign-out and switch, so feeds built for the last listener reload. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private var likedSync: Job? = null
    private var channelsJob: Job? = null

    val activeAccount: IosGoogleAccount?
        get() = _accounts.value.firstOrNull { it.id == _activeAccountId.value }

    /** The saved logins, the active one applied before the first request is made. Called once, at launch. */
    fun restore() {
        val store = IosKeychain.get(KEY_ACCOUNTS)
            ?.let { runCatching { json.decodeFromString(IosAccountStore.serializer(), it) }.getOrNull() }
            ?: migrateV1()
            ?: return
        val active = store.accounts.firstOrNull { it.id == store.activeAccountId } ?: store.accounts.firstOrNull() ?: return
        _accounts.value = store.accounts
        _activeAccountId.value = active.id
        apply(active, active.activeProfile)
        _signedIn.value = true
        _account.value = active.activeProfile?.let { Account(it.name, it.handle.ifBlank { active.email }, it.avatar) }
        loadAccountData()
        loadChannels()
    }

    /** The single session the first sign-in build kept, as the first account of the list. */
    private fun migrateV1(): IosAccountStore? {
        val saved = IosKeychain.get(KEY_SESSION_V1)
            ?.let { runCatching { json.decodeFromString(IosSession.serializer(), it) }.getOrNull() }
            ?: return null
        val account = accountFrom(saved, Account(saved.name, saved.email, saved.avatar), existing = null)
        val store = IosAccountStore(listOf(account), account.id)
        IosKeychain.set(KEY_ACCOUNTS, json.encodeToString(IosAccountStore.serializer(), store))
        IosKeychain.remove(KEY_SESSION_V1)
        return store
    }

    /**
     * A fresh sign-in, or another Google login added beside the ones kept. Applied, then
     * checked: the account must answer as signed in before anything is saved, so a
     * half-finished login never becomes a broken saved account.
     */
    fun signIn(captured: IosSession, onComplete: (Boolean) -> Unit) {
        val previous = activeAccount
        scope.launch {
            Innertube.cookie = captured.cookie
            Innertube.adoptSessionScope(
                pageId = captured.pageId,
                dataSyncId = captured.dataSyncId,
                authUser = captured.authUser,
                visitorData = captured.visitorData,
                clientVersion = captured.clientVersion,
                loggedIn = true,
            )
            Innertube.selectChannel(captured.pageId, captured.dataSyncId, captured.authUser)
            InnerTubeXResolver.onSessionChanged()
            val account = withTimeoutOrNull(20_000L) {
                var result = YtMusicRepository.account()
                if (result.isFailure) {
                    delay(750L)
                    result = YtMusicRepository.account()
                }
                result.getOrNull()
            }
            if (account == null) {
                TrackLog.w(TAG, "the captured session did not answer as an account; not saving it")
                if (previous != null) apply(previous, previous.activeProfile) else clearRequests()
                onComplete(false)
                return@launch
            }
            // The same Google login signed in again (its cookies rotate) replaces its old entry
            // rather than listing itself twice.
            val existing = _accounts.value.firstOrNull {
                account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)
            }
            val stored = accountFrom(captured, account, existing)
            _accounts.value = if (existing != null) {
                _accounts.value.map { if (it.id == existing.id) stored else it }
            } else {
                _accounts.value + stored
            }
            _activeAccountId.value = stored.id
            save()
            _account.value = account
            _signedIn.value = true
            listenerChanged()
            loadChannels()
            onComplete(true)
        }
    }

    /** Acts as [profileId] of [accountId] from here on, and refetches everything that is theirs. */
    fun selectProfile(accountId: String, profileId: String) {
        val target = _accounts.value.firstOrNull { it.id == accountId } ?: return
        val profile = target.profiles.firstOrNull { it.id == profileId } ?: return
        if (accountId == _activeAccountId.value && profileId == target.activeProfile?.id) return
        val updated = target.copy(activeProfileId = profileId)
        _accounts.value = _accounts.value.map { if (it.id == accountId) updated else it }
        val accountChanged = accountId != _activeAccountId.value
        _activeAccountId.value = accountId
        save()
        apply(updated, profile)
        _account.value = Account(profile.name, profile.handle.ifBlank { updated.email }, profile.avatar)
        listenerChanged()
        if (accountChanged) loadChannels()
    }

    /** Forgets one Google login; the next one kept takes over, or nobody is signed in. */
    fun removeAccount(accountId: String) {
        val remaining = _accounts.value.filterNot { it.id == accountId }
        if (remaining.isEmpty()) {
            signOut()
            return
        }
        _accounts.value = remaining
        if (accountId == _activeAccountId.value) {
            val next = remaining.first()
            _activeAccountId.value = null
            selectProfile(next.id, next.activeProfile?.id ?: return)
        } else {
            save()
        }
    }

    /** Every login gone. */
    fun signOut() {
        IosKeychain.remove(KEY_ACCOUNTS)
        IosKeychain.remove(KEY_SESSION_V1)
        IosLogin.clearGoogleCookies()
        clearRequests()
        likedSync?.cancel()
        channelsJob?.cancel()
        LikeState.clear()
        _accounts.value = emptyList()
        _activeAccountId.value = null
        _account.value = null
        _signedIn.value = false
        _library.value = UiState.Loading
        _generation.value++
    }

    fun loadLibrary() {
        if (!_signedIn.value) return
        _library.value = UiState.Loading
        val asked = _generation.value
        scope.launch {
            val result = YtMusicRepository.library()
            // A switch while this was in flight: the answer is the last listener's.
            if (asked != _generation.value) return@launch
            result
                .onSuccess { page ->
                    _library.value = UiState.Success(page)
                    // Liked tracks past the first page still read as liked.
                    likedSync?.cancel()
                    likedSync = scope.launch { YtMusicRepository.syncLikedMusic(page.likedContinuation) }
                }
                .onFailure { _library.value = UiState.Error(it.message ?: "Could not load your library") }
        }
    }

    /** Like, or take a like back, painted at once and put back if YouTube Music refuses it. */
    fun toggleLike(song: Song, current: LikeStatus) {
        if (!_signedIn.value) return
        val next = if (current == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE
        LikeState.set(song.videoId, next)
        scope.launch {
            YtMusicRepository.rate(song.videoId, next).onFailure {
                TrackLog.w(TAG, "${song.videoId}: rating refused: ${it.message}")
                LikeState.set(song.videoId, current)
                IosPlayerHost.showMessage("Could not update your likes")
            }
        }
    }

    /**
     * Asks YouTube which channels the active login can act as — its own and any brand ones —
     * and keeps them beside the one it signed in as. An empty or partial answer never drops
     * the channel in use.
     */
    fun loadChannels() {
        val accountId = _activeAccountId.value ?: return
        channelsJob?.cancel()
        channelsJob = scope.launch {
            val channels = YtMusicRepository.accountChannels()
                .onFailure { TrackLog.w(TAG, "channels unavailable: ${it.message}") }
                .getOrNull()
                .orEmpty()
            if (channels.isEmpty()) return@launch
            val current = _accounts.value.firstOrNull { it.id == accountId } ?: return@launch
            val merged = current.copy(profiles = mergeProfiles(current.profiles, channels.map(::profileOf)))
            val selected = merged.profiles.firstOrNull { it.id == current.activeProfileId }?.id
                ?: merged.profiles.firstOrNull()?.id
            _accounts.value = _accounts.value.map { if (it.id == accountId) merged.copy(activeProfileId = selected) else it }
            save()
        }
    }

    private fun profileOf(channel: AccountChannel) = IosProfile(
        id = profileId(channel.pageId, channel.dataSyncId, channel.name),
        name = channel.name,
        handle = channel.subtitle,
        avatar = channel.thumbnailUrl,
        pageId = channel.pageId,
        dataSyncId = channel.dataSyncId,
        brand = channel.pageId != null,
    )

    /**
     * Known channels plus what YouTube reports, in YouTube's order. A channel kept under a
     * provisional name id (captured before its real ids were known) is dropped once real ones
     * arrive, as the phone's `persistDetectedProfiles` does.
     */
    private fun mergeProfiles(known: List<IosProfile>, detected: List<IosProfile>): List<IosProfile> {
        val kept = known.filter { old ->
            !old.id.startsWith("profile:") && detected.none { it.id == old.id }
        }
        return detected.map { fresh ->
            // A detected entry has no handle as rich as the one the account menu gave.
            val old = known.firstOrNull { it.id == fresh.id }
            if (old != null && fresh.handle.isBlank()) fresh.copy(handle = old.handle) else fresh
        } + kept
    }

    private fun accountFrom(captured: IosSession, account: Account, existing: IosGoogleAccount?): IosGoogleAccount {
        val profile = IosProfile(
            id = profileId(captured.pageId, captured.dataSyncId, account.name),
            name = account.name,
            handle = account.email,
            avatar = account.thumbnailUrl,
            pageId = captured.pageId,
            dataSyncId = captured.dataSyncId,
            brand = captured.pageId != null,
        )
        val others = existing?.profiles.orEmpty().filterNot { it.id == profile.id || it.id.startsWith("profile:") }
        return IosGoogleAccount(
            id = existing?.id ?: "google:${account.email.ifBlank { captured.cookie }.hashCode().toUInt().toString(16)}",
            cookie = captured.cookie,
            name = account.name,
            email = account.email,
            authUser = captured.authUser,
            visitorData = captured.visitorData,
            clientVersion = captured.clientVersion,
            profiles = listOf(profile) + others,
            activeProfileId = profile.id,
        )
    }

    private fun profileId(pageId: String?, dataSyncId: String?, name: String): String =
        pageId ?: dataSyncId ?: "profile:${name.hashCode().toUInt().toString(16)}"

    /** Everything on screen belonged to whoever was acting before. */
    private fun listenerChanged() {
        likedSync?.cancel()
        LikeState.clear()
        _generation.value++
        loadAccountData()
    }

    private fun save() {
        val store = IosAccountStore(_accounts.value, _activeAccountId.value)
        IosKeychain.set(KEY_ACCOUNTS, json.encodeToString(IosAccountStore.serializer(), store))
    }

    private fun loadAccountData() {
        val asked = _generation.value
        scope.launch {
            Innertube.ensureSessionScope()
            YtMusicRepository.account().onSuccess { account ->
                if (asked != _generation.value) return@onSuccess
                _account.value = account
                // The account menu speaks for the channel acting, with its real handle and photo.
                val active = activeAccount ?: return@onSuccess
                val profileId = active.activeProfile?.id ?: return@onSuccess
                val updated = active.copy(
                    profiles = active.profiles.map {
                        if (it.id == profileId) it.copy(name = account.name, handle = account.email, avatar = account.thumbnailUrl) else it
                    },
                )
                if (updated != active) {
                    _accounts.value = _accounts.value.map { if (it.id == active.id) updated else it }
                    save()
                }
            }
        }
        loadLibrary()
    }

    private fun apply(account: IosGoogleAccount, profile: IosProfile?) {
        val cookieChanged = Innertube.cookie != account.cookie
        Innertube.cookie = account.cookie
        if (cookieChanged) {
            // The page scope this login was captured with, so the first request is already scoped.
            Innertube.adoptSessionScope(
                pageId = profile?.pageId,
                dataSyncId = profile?.dataSyncId,
                authUser = account.authUser,
                visitorData = account.visitorData,
                clientVersion = account.clientVersion,
                loggedIn = true,
            )
        }
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, account.authUser)
        // Stream verdicts reached for the last identity say nothing about this one.
        InnerTubeXResolver.onSessionChanged()
    }

    private fun clearRequests() {
        Innertube.cookie = null
        Innertube.selectChannel(null, null)
        InnerTubeXResolver.onSessionChanged()
    }
}
