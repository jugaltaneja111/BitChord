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
import kotlinx.coroutines.launch

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

    private val _explore = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<MoodGenreSection>>> = _explore.asStateFlow()

    private var generation = 0
    private var continuation: String? = null
    private val seenTitles = HashSet<String>()

    init {
        loadHome()
        loadExplore()
    }

    fun loadHome() {
        val current = ++generation
        _home.value = UiState.Loading
        continuation = null
        seenTitles.clear()
        _homeLoadingMore.value = false
        scope.launch {
            YtMusicRepository.home()
                .onSuccess { feed ->
                    if (current != generation) return@onSuccess
                    continuation = feed.continuation
                    publish(feed.shelves)
                    if (_home.value is UiState.Loading) _home.value = UiState.Error("Home is empty right now")
                }
                .onFailure {
                    if (current == generation && _home.value !is UiState.Success) {
                        _home.value = UiState.Error(it.message ?: "Could not load Home")
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
                .onSuccess { _explore.value = UiState.Success(it) }
                .onFailure { _explore.value = UiState.Error(it.message ?: "Could not load Explore") }
        }
    }

    private fun publish(shelves: List<HomeShelf>) {
        val existing = (_home.value as? UiState.Success)?.data.orEmpty()
        val added = shelves.withoutRepeatsOf(existing)
            .filter { it.items.isNotEmpty() && seenTitles.add(it.title.lowercase()) }
        if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
    }
}
