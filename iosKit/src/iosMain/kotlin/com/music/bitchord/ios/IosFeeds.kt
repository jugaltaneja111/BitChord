package com.music.bitchord.ios

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.withoutRepeatsOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Home and Explore, loaded as the desktop loads them: the core feed and the supplementary browse
 * feeds asked for at once and laid down as each lands, the core feed's next page as the list nears
 * its end. Signed out, so there is no Recently Played. Main thread only.
 */
internal class IosFeeds {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    private val _homeLoadingMore = MutableStateFlow(false)
    val homeLoadingMore: StateFlow<Boolean> = _homeLoadingMore.asStateFlow()

    private val _recentsLoading = MutableStateFlow(false)
    /** The account's own Recently Played, asked for apart from the feed and laid at its head. */
    val recentsLoading: StateFlow<Boolean> = _recentsLoading.asStateFlow()

    private val _explore = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<MoodGenreSection>>> = _explore.asStateFlow()

    private var generation = 0
    private var continuation: String? = null
    private val seenTitles = HashSet<String>()

    init {
        loadHome()
        loadExplore()
        // A sign-in or sign-out makes the feed someone else's.
        scope.launch {
            IosAccount.generation.drop(1).collect { loadHome() }
        }
        // Back online after a failed load: try again without waiting for Retry.
        scope.launch {
            var wasOffline = false
            IosNetwork.metered.collect { metered ->
                if (metered == null) {
                    wasOffline = true
                } else if (wasOffline) {
                    wasOffline = false
                    if (_home.value is UiState.Error) loadHome()
                    if (_explore.value is UiState.Error) loadExplore()
                }
            }
        }
    }

    fun loadHome() {
        val current = ++generation
        val signedIn = IosAccount.signedIn.value
        _home.value = UiState.Loading
        continuation = null
        seenTitles.clear()
        _homeLoadingMore.value = false
        _recentsLoading.value = signedIn
        if (signedIn) {
            scope.launch {
                YtMusicRepository.homeRecentlyPlayed()
                    .onSuccess { shelf ->
                        if (current != generation) return@onSuccess
                        shelf?.let { publish(listOf(it), prepend = true) }
                    }
                if (current == generation) _recentsLoading.value = false
            }
        }
        scope.launch {
            YtMusicRepository.home()
                .onSuccess { feed ->
                    if (current != generation) return@onSuccess
                    continuation = feed.continuation
                    publish(feed.shelves)
                    if (_home.value is UiState.Loading) _home.value = UiState.Error(tr("Home is empty right now"))
                }
                .onFailure {
                    if (current == generation && _home.value !is UiState.Success) {
                        _home.value = UiState.Error(loadError(it, tr("Could not load Home")))
                    }
                }
        }
        YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.forEach { browseId ->
            scope.launch {
                YtMusicRepository.homeSupplement(browseId).onSuccess { shelves ->
                    if (current == generation) publish(shelves)
                }
            }
        }
    }

    fun loadMoreHome() {
        val token = continuation ?: return
        if (_homeLoadingMore.value || _home.value !is UiState.Success) return
        val current = generation
        _homeLoadingMore.value = true
        scope.launch {
            YtMusicRepository.moreHome(token)
                .onSuccess { feed ->
                    if (current != generation) return@onSuccess
                    val before = seenTitles.size
                    publish(feed.shelves)
                    // A page with nothing new is the feed looping back on itself, not running dry.
                    continuation = feed.continuation.takeIf { seenTitles.size > before }
                }
                .onFailure { continuation = null }
            _homeLoadingMore.value = false
        }
    }

    fun loadExplore() {
        _explore.value = UiState.Loading
        scope.launch {
            YtMusicRepository.moodAndGenres()
                .onSuccess {
                    _explore.value = UiState.Success(it)
                    loadCategoryArtwork(it)
                }
                .onFailure { _explore.value = UiState.Error(loadError(it, tr("Could not load Explore"))) }
        }
    }

    /**
     * Category buttons carry no artwork of their own. As the phone does, a few at a time are
     * resolved from the shelves they open, so the grid gains real art without holding up the
     * list or crowding the browse client.
     */
    private fun loadCategoryArtwork(sections: List<MoodGenreSection>) {
        scope.launch {
            val limiter = Semaphore(4)
            coroutineScope {
                sections.flatMap(MoodGenreSection::items).forEach { item ->
                    launch {
                        val artwork = limiter.withPermit {
                            YtMusicRepository.moodGenreArtwork(item.browseId, item.params).getOrNull()
                        } ?: return@launch
                        val current = (_explore.value as? UiState.Success)?.data ?: return@launch
                        _explore.value = UiState.Success(
                            current.map { section ->
                                section.copy(
                                    items = section.items.map {
                                        if (it.browseId == item.browseId && it.params == item.params) {
                                            it.copy(thumbnailUrl = artwork)
                                        } else {
                                            it
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    private fun publish(shelves: List<HomeShelf>, prepend: Boolean = false) {
        val existing = (_home.value as? UiState.Success)?.data.orEmpty()
        if (prepend) {
            val replacing = shelves.map { it.title.lowercase() }.toSet()
            seenTitles.addAll(replacing)
            _home.value = UiState.Success(shelves + existing.filterNot { it.title.lowercase() in replacing })
            return
        }
        val added = shelves.withoutRepeatsOf(existing)
            .filter { it.items.isNotEmpty() && seenTitles.add(it.title.lowercase()) }
        if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
    }
}
