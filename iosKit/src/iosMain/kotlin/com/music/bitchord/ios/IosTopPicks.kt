package com.music.bitchord.ios

import com.music.bitchord.data.AppleArtistPhotos
import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults

/**
 * Home's "Top Picks for You" row, the phone's v1.9 lead shelf: built by the shared [TopPicks]
 * from the account's history and its top artists' pages, kept for the hour it was built in so a
 * relaunch shows it at once, and rebuilt when the hour or the account changes. Main thread.
 */
internal object IosTopPicks {
    private const val TAG = "IosTopPicks"
    private const val KEY = "bitchord.top_picks"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val defaults = NSUserDefaults.standardUserDefaults

    private val _picks = MutableStateFlow<List<TopPick>?>(null)
    /** Null until built; empty when there was nothing to pick from. */
    val picks: StateFlow<List<TopPick>?> = _picks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var job: Job? = null
    private var builtFor: Pair<String?, String>? = null

    private fun listener(): String? = IosAccount.account.value?.name

    /** `2026-10-31T19`: the slot a build belongs to. */
    private fun slot(): String {
        val calendar = NSCalendar.currentCalendar
        val now = NSDate()
        fun part(unit: ULong) = calendar.component(unit, fromDate = now).toInt()
        val pad = { n: Int -> n.toString().padStart(2, '0') }
        return "${part(NSCalendarUnitYear)}-${pad(part(NSCalendarUnitMonth))}-${pad(part(NSCalendarUnitDay))}T${pad(part(NSCalendarUnitHour))}"
    }

    private fun hour(): Int = NSCalendar.currentCalendar.component(NSCalendarUnitHour, fromDate = NSDate()).toInt()

    /** Home's feed landed: show what was kept, and build afresh if it is from another hour or account. */
    fun refresh(homeShelves: List<HomeShelf>) {
        val listener = listener()
        val slot = slot()
        if (builtFor == listener to slot && _picks.value != null) return
        val stored = defaults.stringForKey(KEY)?.let(TopPicks::decode)
        if (stored != null && stored.listener == listener) {
            scope.launch { AppleArtistPhotos.seed(stored.photos) }
            if (_picks.value == null) _picks.value = stored.picks
            if (stored.day == slot) {
                builtFor = listener to slot
                return
            }
        } else if (builtFor?.first != listener) {
            _picks.value = null
        }
        job?.cancel()
        _loading.value = _picks.value == null
        job = scope.launch {
            val built = runCatching { TopPicks.build(homeShelves, TopPicks.LocalTaste(), hour()) }
                .onFailure { TrackLog.w(TAG, "Top Picks failed: ${it.message}") }
                .getOrNull()
            _loading.value = false
            if (built == null) {
                if (_picks.value == null) _picks.value = emptyList()
                return@launch
            }
            builtFor = listener to slot
            _picks.value = built
            defaults.setObject(TopPicks.encode(TopPicks.Stored(listener, slot, built, AppleArtistPhotos.snapshot())), KEY)
        }
    }

    /** A card tapped: a release opens; a station's queue is fetched and played, named [label]. */
    fun open(pick: TopPick, label: String, navigator: IosNavigator) {
        val browseId = pick.browseId
        if (pick.kind == TopPickKind.LATEST_RELEASE && browseId != null) {
            navigator.open(
                IosPage.Collection(
                    browseId,
                    BrowseItem(
                        browseId = browseId,
                        title = pick.title,
                        subtitle = pick.artistName.orEmpty(),
                        thumbnailUrl = pick.images.firstOrNull(),
                        type = BrowseType.ALBUM,
                    ),
                ),
            )
            return
        }
        scope.launch {
            val songs = TopPicks.queue(pick).getOrNull().orEmpty()
            if (songs.isEmpty()) {
                IosPlayerHost.showMessage(tr("Couldn't load the tracks"))
                return@launch
            }
            val sourceId = pick.watch?.playlistId ?: pick.mood?.name
            IosPlayer.playQueue(
                songs.map {
                    it.copy(
                        radioName = label,
                        playbackSource = label,
                        playbackSourceType = PlaybackSourceType.HOME,
                        playbackSourceId = sourceId,
                    )
                },
                0,
            )
        }
    }

    /** A Find Your Mood tile: that mood's station, steered by the scene words the mood card used. */
    fun playMood(mood: TopPickMood, title: String, navigator: IosNavigator) {
        val hints = _picks.value?.firstOrNull { it.kind == TopPickKind.MOOD }?.hints
        open(if (hints != null) TopPicks.moodStation(mood, hints) else TopPicks.moodStation(mood), title, navigator)
    }
}
