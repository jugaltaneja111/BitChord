package com.music.bitchord.ios

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Account
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

/**
 * The YouTube Music account, as the phone's MainViewModel keeps it: one Google login for now,
 * applied to every request, checked against the API before it is kept, and gone on sign-out.
 * Main thread only.
 */
internal object IosAccount {
    private const val TAG = "IosAccount"
    private const val KEY_SESSION = "youtube_music_session_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    private val _session = MutableStateFlow<IosSession?>(null)

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    /** Raised on every sign-in and sign-out, so feeds built for the last listener reload. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private var likedSync: Job? = null

    /** The saved session, applied before the first request is made. Called once, at launch. */
    fun restore() {
        val saved = IosKeychain.get(KEY_SESSION)
            ?.let { runCatching { json.decodeFromString(IosSession.serializer(), it) }.getOrNull() }
            ?: return
        apply(saved)
        _session.value = saved
        _signedIn.value = true
        _account.value = Account(saved.name, saved.email, saved.avatar)
        loadAccountData()
    }

    /**
     * A fresh sign-in. Applied, then checked: the account must answer as signed in before
     * anything is saved, so a half-finished login never becomes a broken saved account.
     */
    fun signIn(captured: IosSession, onComplete: (Boolean) -> Unit) {
        val previous = _session.value
        scope.launch {
            apply(captured)
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
                if (previous != null) apply(previous) else clearRequests()
                onComplete(false)
                return@launch
            }
            val stored = captured.copy(name = account.name, email = account.email, avatar = account.thumbnailUrl)
            IosKeychain.set(KEY_SESSION, json.encodeToString(IosSession.serializer(), stored))
            _session.value = stored
            _account.value = account
            _signedIn.value = true
            LikeState.clear()
            _generation.value++
            loadAccountData()
            onComplete(true)
        }
    }

    fun signOut() {
        IosKeychain.remove(KEY_SESSION)
        IosLogin.clearGoogleCookies()
        clearRequests()
        likedSync?.cancel()
        LikeState.clear()
        _session.value = null
        _account.value = null
        _signedIn.value = false
        _library.value = UiState.Loading
        _generation.value++
    }

    fun loadLibrary() {
        if (!_signedIn.value) return
        _library.value = UiState.Loading
        scope.launch {
            YtMusicRepository.library()
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

    private fun loadAccountData() {
        scope.launch {
            Innertube.ensureSessionScope()
            YtMusicRepository.account().onSuccess { account ->
                _account.value = account
                _session.value?.let { saved ->
                    val updated = saved.copy(name = account.name, email = account.email, avatar = account.thumbnailUrl)
                    if (updated != saved) {
                        IosKeychain.set(KEY_SESSION, json.encodeToString(IosSession.serializer(), updated))
                        _session.value = updated
                    }
                }
            }
        }
        loadLibrary()
    }

    private fun apply(session: IosSession) {
        Innertube.cookie = session.cookie
        Innertube.adoptSessionScope(
            pageId = session.pageId,
            dataSyncId = session.dataSyncId,
            authUser = session.authUser,
            visitorData = session.visitorData,
            clientVersion = session.clientVersion,
            loggedIn = true,
        )
        Innertube.selectChannel(session.pageId, session.dataSyncId, session.authUser)
        // Stream verdicts reached for the last identity say nothing about this one.
        InnerTubeXResolver.onSessionChanged()
    }

    private fun clearRequests() {
        Innertube.cookie = null
        Innertube.selectChannel(null, null)
        InnerTubeXResolver.onSessionChanged()
    }
}
