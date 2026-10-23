package com.music.bitchord.ios

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ios.sources.IosSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The search page's state, as the phone's MainViewModel keeps it for the same screen: the query,
 * typed suggestions, the submitted results and their next page. History lives for the session.
 */
internal class IosSearch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var typingJob: Job? = null
    private var searchJob: Job? = null
    private var continuation: String? = null

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val results: StateFlow<UiState<List<SearchResult>>?> = _results.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _typeahead = MutableStateFlow<List<SearchResult>>(emptyList())
    val typeahead: StateFlow<List<SearchResult>> = _typeahead.asStateFlow()

    /** Which catalogue the page asks: YouTube Music, the enabled add-ons, or the songs on this iPhone. */
    enum class Source { YOUTUBE, ADDONS, LIBRARY }

    private val _source = MutableStateFlow(Source.YOUTUBE)
    val source: StateFlow<Source> = _source.asStateFlow()

    private val addons: Boolean get() = _source.value == Source.ADDONS
    /** The add-ons and the Library have no suggestions to offer; the field is only words to match. */
    private val ownField: Boolean get() = _source.value != Source.YOUTUBE

    /** The Library source's songs for what is typed, matched as it is typed; null while nothing is. */
    val libraryResults: StateFlow<UiState<List<Song>>?> = combine(_query, IosLocalMusic.songs) { query, songs ->
        if (query.isBlank()) null else UiState.Success(localSongsMatching(songs, query).sortedBy { it.title.lowercase() })
    }.stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private val _addonResults = MutableStateFlow<UiState<List<Song>>?>(null)
    val addonResults: StateFlow<UiState<List<Song>>?> = _addonResults.asStateFlow()

    private val _addonHistory = MutableStateFlow<List<String>>(emptyList())
    /** Words searched in the Addons tab, newest first, for the session. */
    val addonHistory: StateFlow<List<String>> = _addonHistory.asStateFlow()

    /** Flips the page between its sources; whatever is typed is asked again. */
    fun setSource(value: Source) {
        if (_source.value == value) return
        _source.value = value
        _suggestions.value = emptyList()
        _typeahead.value = emptyList()
        val hadResults = _results.value != null || _addonResults.value != null
        _results.value = null
        _addonResults.value = null
        if (hadResults) submit()
    }

    fun removeAddonSearch(term: String) {
        _addonHistory.value = _addonHistory.value - term
    }

    fun clearAddonSearchHistory() {
        _addonHistory.value = emptyList()
    }

    private val _history = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    val history: StateFlow<List<SearchHistoryEntity>> = _history.asStateFlow()

    fun onQueryChange(value: String) {
        _query.value = value
        // Typing again drops the results: the page goes back to suggestions for the new words.
        _results.value = null
        _addonResults.value = null
        searchJob?.cancel()
        typingJob?.cancel()
        // The add-ons have no suggestions to offer; the field is only words to send them.
        if (value.isBlank() || ownField) {
            _suggestions.value = emptyList()
            _typeahead.value = emptyList()
            return
        }
        typingJob = scope.launch {
            delay(TYPING_DEBOUNCE_MS)
            launch { YtMusicRepository.searchSuggestions(value).onSuccess { _suggestions.value = it } }
            launch { YtMusicRepository.searchTypeahead(value).onSuccess { _typeahead.value = it.rows } }
        }
    }

    fun onFilterChange(value: SearchFilter) {
        _filter.value = value
        if (_results.value != null) submit()
    }

    fun submit(text: String = _query.value) {
        if (text.isBlank()) return
        _query.value = text
        typingJob?.cancel()
        searchJob?.cancel()
        continuation = null
        // The Library's results follow the field as it is typed in.
        if (_source.value == Source.LIBRARY) return
        if (addons) {
            _addonHistory.value = (listOf(text) + _addonHistory.value.filter { it != text }).take(MAX_HISTORY)
            if (!IosSources.hasSearchableAddons()) {
                _addonResults.value = UiState.Error(tr("No addons are enabled. Add one in Settings → Sources."))
                return
            }
            _addonResults.value = UiState.Loading
            searchJob = scope.launch { _addonResults.value = UiState.Success(IosSources.search(text)) }
            return
        }
        _results.value = UiState.Loading
        searchJob = scope.launch {
            YtMusicRepository.searchPage(text, _filter.value)
                .onSuccess { page ->
                    continuation = page.continuation
                    _results.value = UiState.Success(page.rows)
                }
                .onFailure { _results.value = UiState.Error(it.message ?: tr("Search failed")) }
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        val current = (_results.value as? UiState.Success)?.data ?: return
        if (_loadingMore.value) return
        _loadingMore.value = true
        scope.launch {
            YtMusicRepository.searchContinuation(token, _filter.value).onSuccess { page ->
                continuation = page.continuation
                _results.value = UiState.Success(current + page.rows)
            }
            _loadingMore.value = false
        }
    }

    fun record(entity: SearchHistoryEntity) {
        _history.value = listOf(entity) + _history.value.filter { it.id != entity.id }.take(MAX_HISTORY - 1)
    }

    fun removeHistory(id: String) {
        _history.value = _history.value.filter { it.id != id }
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    private companion object {
        const val TYPING_DEBOUNCE_MS = 250L
        const val MAX_HISTORY = 30
    }
}
