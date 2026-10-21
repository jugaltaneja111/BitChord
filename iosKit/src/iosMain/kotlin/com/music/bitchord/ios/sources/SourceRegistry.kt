package com.music.bitchord.ios.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.ios.sources.addon.AddonClient
import com.music.bitchord.ios.sources.addon.AddonException
import com.music.bitchord.ios.sources.addon.DetectedFormat
import com.music.bitchord.ios.sources.addon.SourceFormats
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl
import kotlin.concurrent.Volatile
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import platform.Foundation.NSThread
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

/**
 * One configured source: which protocol, which server.
 *
 * Stored in the app's defaults under `bitchord.sources.configs`. The phone keeps
 * these in encrypted prefs because [baseUrl] can carry an addon's private token
 * in its path; on iOS the app's container is already encrypted at rest with the
 * device, which is the protection the phone's encrypted prefs were reaching for.
 */
@Serializable
internal data class SourceConfig(
    val id: String = NSUUID().UUIDString(),
    val kind: SourceKind,
    /** What the user called it. Blank falls back to the server's host, or the kind's own label. */
    val label: String = "",
    val baseUrl: String = "",
    /** JioSaavn is opt-in because catalogue matches can select the wrong recording. */
    val enabled: Boolean = kind != SourceKind.JIOSAAVN,
    /**
     * The addon's `allowDownloads`, as its manifest last said. Stored rather
     * than asked each time so the answer is there without a request — see
     * [checkValidLossless] for why that matters.
     */
    val allowDownloads: Boolean = true,
    /**
     * The addon's `checkValidLossless`, as its manifest last said. Has to be
     * stored: the promise is that a gated addon is not contacted at all while
     * no lossless output is connected, and asking it for its manifest would
     * already break that. Refreshed whenever the manifest is next read.
     */
    val checkValidLossless: Boolean = false,
) {
    /** Whether this source is shut out right now because it needs an output that isn't connected. */
    val awaitsLosslessOutput: Boolean
        get() = checkValidLossless && !LosslessOutput.capable

    /** The two manifest switches, without the parts of the config the user set. */
    fun withPolicyOf(other: SourceConfig) =
        copy(allowDownloads = other.allowDownloads, checkValidLossless = other.checkValidLossless)

    /** What the Sources page and the player show. Never blank. */
    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf { it.isNotBlank() }
                ?.let { parseUrl(it)?.host?.ifBlank { null } }
                ?: kind.label
        }

    /** Whether this has enough filled in to be worth contacting at all. */
    val isComplete: Boolean
        get() = !kind.needsServer || baseUrl.isNotBlank()
}

/**
 * The user's sources, always tried in the fixed order [SourceKind] declares:
 * their own addons first, then JioSaavn, then YouTube Music.
 *
 * [SourceKind.YOUTUBE] is seeded on first run and cannot be deleted or
 * disabled — it needs no configuration, so a "remove" would delete something
 * the user could not then re-create by typing anything in. Addons are entirely
 * optional: with none configured, YouTube is the only active source on a fresh
 * install. JioSaavn is present but off until the user accepts its
 * catalogue-matching risk.
 *
 * ### Threading
 *
 * Every edit is made on the main thread, which is where the Sources page makes
 * them; [updatePolicy], the one write that arrives from a source's own network
 * call, hops there first. Readers on any thread see [configs] (a state flow)
 * and [instances] (an immutable map swapped whole), so nothing needs a lock —
 * the phone's equivalent leans on the same shape.
 */
internal object SourceRegistry {

    private const val TAG = "BitChord"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val defaults = NSUserDefaults.standardUserDefaults

    private val main = MainScope()

    private val _configs = MutableStateFlow<List<SourceConfig>>(emptyList())

    /** Every configured source, enabled or not. */
    val configs: StateFlow<List<SourceConfig>> = _configs.asStateFlow()

    /**
     * Built instances, keyed by config id, rebuilt whenever [configs] changes.
     *
     * Held rather than constructed per call so that a source with any warmed
     * state — an addon whose manifest and recent searches are already in hand —
     * keeps it across tracks instead of re-probing on every resolve.
     */
    @Volatile
    private var instances: Map<String, MusicSource> = emptyMap()

    private var initialised = false

    fun init() {
        if (initialised) return
        initialised = true
        val stored = defaults.stringForKey(KEY_SOURCES)?.let(::decodeStored) ?: emptyList()
        val after = sourcesForInit(stored)
        publish(after, persist = after != stored)
    }

    /**
     * Built-in seeding, kept pure.
     *
     * Seeded rather than persisted-on-first-write, so a build adding a new
     * built-in kind picks it up for existing installs too. [SourceConfig]'s
     * default is the policy: JioSaavn off, YouTube on. YouTube is forced back on
     * if anything stored says otherwise, for the reason on [setEnabled].
     *
     * The phone also runs a one-shot migration here forcing JioSaavn off for
     * installs that predate its opt-in. Nothing on iOS predates it.
     */
    internal fun sourcesForInit(stored: List<SourceConfig>): List<SourceConfig> {
        val seeded = stored + BUILT_IN_KINDS
            .filter { kind -> stored.none { it.kind == kind } }
            .map { SourceConfig(kind = it) }
        return seeded.map { config ->
            if (config.kind == SourceKind.YOUTUBE && !config.enabled) config.copy(enabled = true) else config
        }
    }

    /**
     * Decodes a stored source list one entry at a time rather than as a single
     * list, so one entry naming a kind this build doesn't have — the phone's
     * `MODULE`/`CUSTOM_MODULE`, say, should a list ever be carried across —
     * doesn't take every other entry down with it. A strict
     * `List<SourceConfig>` decode fails whole: one bad enum value and the
     * user's real, working addon is silently gone along with it.
     */
    internal fun decodeStored(raw: String): List<SourceConfig> {
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { return emptyList() }
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "dropping unreadable stored source: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * The enabled sources, addons first and YouTube last, however they're
     * stored. The user's standing choice and nothing else; a stream is budgeted
     * further by [activeForPlayback].
     */
    fun active(): List<MusicSource> {
        val built = instances
        return enabledConfigs(configs.value)
            // Not in the walk at all while it would be refused anyway: an addon
            // that asked for a lossless output costs nothing — not even a timed
            // out search — while there isn't one. See [SourceConfig.checkValidLossless].
            .filterNot { it.awaitsLosslessOutput }
            .sortedBy { it.kind.rank }
            .mapNotNull { built[it.id] }
    }

    /**
     * [active], minus the addons whose manifest said `allowDownloads: 0`. For a
     * download walk, should one ever come here, so the next source in line
     * serves it.
     */
    fun activeForDownload(): List<MusicSource> =
        active().filter { config(it.configId)?.allowDownloads != false }

    /**
     * [active], minus the sources [request] does not pay for.
     *
     * The phone asks its connection ceiling (`AudioQuality.permits`); iOS is
     * handed only the [StreamRequest] the ceiling became, which carries the
     * same answer — see [StreamRequest] for the mapping. YouTube is permitted
     * on every rung: it is what a cap caps, and it is the only source that can
     * answer at all when the ones above it are skipped.
     */
    fun activeForPlayback(request: StreamRequest): List<MusicSource> =
        active().filter { permits(request, it.kind) }

    fun permits(request: StreamRequest, kind: SourceKind): Boolean = when (request) {
        is StreamRequest.Lossless -> true
        // No lossless answer is wanted here, and a source that can serve one is
        // the slow half of the list: an addon fronting several catalogues walks
        // all of them before it answers, which is seconds spent to land on a
        // transcode JioSaavn already has at 320.
        is StreamRequest.Best -> !kind.canServeLossless
        is StreamRequest.Capped -> kind == SourceKind.YOUTUBE
    }

    /**
     * Records what an addon's manifest said about downloads and lossless
     * output, if it changed.
     *
     * Does not rebuild the source the way an edit does — see [configuredBy] —
     * because nothing about where it points has changed, and this is called
     * from inside that source's own requests, off the main thread; the write
     * is carried over to it.
     */
    fun updatePolicy(configId: String, allowDownloads: Boolean, checkValidLossless: Boolean) {
        val current = config(configId) ?: return
        if (current.allowDownloads == allowDownloads && current.checkValidLossless == checkValidLossless) return
        onMain {
            TrackLog.d(
                TAG,
                "${current.displayName}: manifest now says allowDownloads=$allowDownloads " +
                    "checkValidLossless=$checkValidLossless",
            )
            publish(
                configs.value.map {
                    if (it.id == configId) {
                        it.copy(allowDownloads = allowDownloads, checkValidLossless = checkValidLossless)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** The common eligibility gate used by every source walk. */
    internal fun enabledConfigs(configs: List<SourceConfig>): List<SourceConfig> =
        configs.filter { it.enabled && it.isComplete }

    fun instance(configId: String): MusicSource? = instances[configId]

    fun config(configId: String): SourceConfig? = configs.value.firstOrNull { it.id == configId }

    /**
     * Drops completed addon track answers for an explicit retry.
     *
     * Empty responses are normally legitimate and cached, but an addon whose
     * upstream was temporarily down can express that outage as HTTP 200 with
     * no rows. A listener asking again is an explicit request to ask again
     * now, not to repeat that cached answer.
     */
    fun clearCompletedAddonTrackCalls() {
        instances.values
            .filterIsInstance<AddonSource>()
            .forEach(AddonSource::clearCompletedTrackCalls)
    }

    // ── Editing ─────────────────────────────────────────────────────────

    fun add(config: SourceConfig) = publish(configs.value + config.tidied())

    fun update(config: SourceConfig) =
        config.tidied().let { tidied ->
            publish(configs.value.map { if (it.id == tidied.id) tidied else it })
        }

    /**
     * The config as it should be stored, rather than as it was typed.
     *
     * Only addons have anything to tidy, and only their URL: the two forms
     * people paste — the addon's root and its `manifest.json` — address the
     * same server, and storing them as typed makes two configs that behave
     * identically look different on screen and compare unequal in
     * [configuredBy], which would rebuild a warm source over a cosmetic edit.
     */
    private fun SourceConfig.tidied(): SourceConfig =
        if (kind == SourceKind.ADDON) copy(baseUrl = AddonClient.normalizeBase(baseUrl)) else this

    fun remove(configId: String) {
        val target = config(configId) ?: return
        if (target.kind in BUILT_IN_KINDS) return
        publish(configs.value.filterNot { it.id == configId })
    }

    /**
     * Turns one source on or off.
     *
     * YouTube is not switchable and silently ignores a request to disable it.
     * It is the only source that can supply a home feed, radio or related
     * tracks, and nothing else holds the full catalogue — switching it off
     * wouldn't even stop it being played, because a YouTube-queued track whose
     * substitutes all miss still falls back to it. A switch that cannot honour
     * its own off position is worse than no switch, so the page offers none.
     */
    fun setEnabled(configId: String, enabled: Boolean) {
        if (!enabled && config(configId)?.kind == SourceKind.YOUTUBE) return
        publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
    }

    /**
     * Puts the addons in [orderedIds], which is the order they will be asked in.
     *
     * The stored list *is* the priority order and needs no rank field to carry
     * it: [active] sorts by [SourceKind.rank] and Kotlin's sort is stable, so
     * two sources of the same kind keep the order they are held in here.
     * Writing a rank alongside would be a second source of truth for a fact the
     * list already states.
     *
     * Ids that name nothing are dropped and addons the caller forgot to mention
     * are appended, so a list that has moved on since the page last drew
     * reorders what it can rather than deleting the rest. Everything that is
     * not an addon keeps its place, since its rank is decided by its kind and
     * is not the user's to set.
     */
    fun reorderAddons(orderedIds: List<String>) {
        val addons = configs.value.filter { it.kind.isUserAdded }
        if (addons.size < 2) return
        val byId = addons.associateBy { it.id }
        val moved = orderedIds.mapNotNull(byId::get)
        val missed = addons.filterNot { config -> moved.any { it.id == config.id } }
        val reordered = moved + missed
        if (reordered.map { it.id } == addons.map { it.id }) return
        publish(reordered + configs.value.filterNot { it.kind.isUserAdded })
    }

    private fun publish(next: List<SourceConfig>, persist: Boolean = true) {
        if (!NSThread.isMainThread) {
            onMain { publish(next, persist) }
            return
        }
        _configs.value = next
        // Rebuilt against the previous map so that an untouched source keeps
        // the instance it already had, rather than being replaced by an
        // identical-but-cold one every time an unrelated row is toggled.
        val previous = instances
        val rebuilt = next.associate { config ->
            val existing = previous[config.id]?.takeIf { it.configuredBy(config) }
            config.id to (existing ?: build(config))
        }
        instances = rebuilt
        // Whatever the rebuild above left behind, told so. An addon that was
        // edited or removed is holding a manifest, a set of search answers and
        // a handful of stream URLs that all describe the server it used to
        // point at; serving one of those afterwards would be answering a
        // question about the old address with the new one selected.
        previous.values.filterNot { it in rebuilt.values }
            .filterIsInstance<AddonSource>()
            .forEach { it.release() }
        if (persist) {
            defaults.setObject(json.encodeToString(ListSerializer(SourceConfig.serializer()), next), KEY_SOURCES)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else main.launch { block() }
    }

    /**
     * Works out what is at [url] and returns a config that speaks to it, or a
     * refusal saying what was found instead.
     *
     * This is what makes the editor accept "any JSON" rather than one shape:
     * pasting an addon's root or its `manifest.json` both end here, and
     * anything recognised but unplayable — a module index, an extension
     * registry, a manifest that cannot search — comes back as a failure
     * carrying a line written for the person reading it.
     *
     * [existing] is carried through so editing a saved source keeps its id and
     * its on/off state; a new source gets a fresh config.
     */
    suspend fun identify(url: String, existing: SourceConfig? = null): Result<SourceConfig> {
        val detected = SourceFormats.identify(url).getOrElse { return Result.failure(it) }
        return when (detected) {
            is DetectedFormat.Addon -> Result.success(
                (existing ?: SourceConfig(kind = SourceKind.ADDON)).copy(
                    kind = SourceKind.ADDON,
                    baseUrl = detected.baseUrl,
                    label = detected.manifest.displayName,
                    allowDownloads = detected.manifest.downloadsAllowed,
                    checkValidLossless = detected.manifest.requiresLosslessOutput,
                ),
            )
            is DetectedFormat.Unsupported -> Result.failure(AddonException(detected.reason))
        }
    }

    /**
     * The already-configured source pointing at [url], if there is one.
     *
     * Compared after [identify] has run rather than on the raw text, which is
     * what makes this catch the cases worth catching: an addon's root and its
     * `manifest.json` are the same server typed two ways, and both normalise to
     * one base before they reach here. A trailing slash and a host in a
     * different case are the same source too.
     *
     * [exceptId] is the source being edited, which is not its own duplicate —
     * without it, saving an existing addon without touching its URL would
     * refuse itself.
     */
    fun duplicateOf(url: String, exceptId: String? = null): SourceConfig? {
        val wanted = canonicalUrl(url)
        if (wanted.isEmpty()) return null
        return configs.value.firstOrNull { it.id != exceptId && canonicalUrl(it.baseUrl) == wanted }
    }

    /**
     * A URL reduced to the form two spellings of the same address share.
     *
     * Scheme and host are lowercased because they are case-insensitive; the
     * path deliberately is not, because on this protocol the path can carry a
     * user's token and two tokens differing only in case are two different
     * credentials. Falls back to the trimmed text when the URL will not parse,
     * so a malformed entry still compares equal to itself.
     */
    private fun canonicalUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val parsed = parseUrl(trimmed)?.takeIf { it.host.isNotBlank() && trimmed.contains("://") } ?: return trimmed
        val scheme = parsed.protocol.name.lowercase()
        val path = parsed.encodedPath.trimEnd('/')
        val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val port = if (parsed.port != parsed.protocol.defaultPort) ":${parsed.port}" else ""
        return "$scheme://${parsed.host.lowercase()}$port$path$query"
    }

    /**
     * Health-checks a config that hasn't been saved — what the editor's Test
     * button asks.
     *
     * Built fresh and thrown away rather than routed through [instances],
     * which hold the *stored* config: testing one of those would report on the
     * old address, which is precisely the state the user is in the middle of
     * correcting.
     */
    suspend fun probeCandidate(config: SourceConfig): SourceHealth {
        val probe = build(config)
        return try {
            probe.health()
        } finally {
            (probe as? AddonSource)?.release()
        }
    }

    private fun build(config: SourceConfig): MusicSource = when (config.kind) {
        SourceKind.ADDON -> AddonSource(config)
        SourceKind.JIOSAAVN -> JioSaavnSource(config)
        SourceKind.YOUTUBE -> YouTubeSource(config)
    }

    /**
     * Whether an already-built instance still matches its stored config —
     * false after an edit that changes where it points, which is exactly when
     * the warm instance must be thrown away. The manifest switches are left
     * out: they change no address, and a source reads them live from here.
     */
    private fun MusicSource.configuredBy(config: SourceConfig): Boolean =
        this is ConfigBacked && this.config.withPolicyOf(config) == config

    /** Implemented by sources that carry their [SourceConfig], so [publish] can tell a real edit from a no-op. */
    internal interface ConfigBacked {
        val config: SourceConfig
    }

    // ── Track identity ──────────────────────────────────────────────────

    /**
     * A source-backed track's id, as it travels through the queue:
     * `src:<configId>::<trackId>`.
     *
     * Packed into the existing [Song.videoId][com.music.bitchord.data.model.Song.videoId]
     * rather than added beside it: that field is the app's media id everywhere —
     * the queue, the history, the like state — and a second identity field would
     * have to be threaded through every one of them, with each place that forgot
     * silently falling back to treating the track as YouTube's. Parsed by the
     * shared [SourceTrackKeys], which the stream stats read too.
     */
    fun trackKey(configId: String, trackId: String) =
        "${SourceTrackKeys.PREFIX}$configId${SourceTrackKeys.SEPARATOR}$trackId"

    /** The `(configId, trackId)` inside a [trackKey], or null if this is an ordinary YouTube id. */
    fun parseTrackKey(key: String): Pair<String, String>? = SourceTrackKeys.parse(key)

    /**
     * The phone's playback URI for a source-backed track —
     * `bitchord://source?s=<configId>&t=<trackId>` — which its ExoPlayer
     * resolves at open time. iOS does not route through a URI (the player calls
     * [IosSources.resolve] with the [Song] in hand), so this is here only for
     * parity, for a caller that needs to hand the phone's shape to something.
     */
    fun trackUri(configId: String, trackId: String): String =
        URLBuilder(protocol = URLProtocol("bitchord", 0), host = "source").apply {
            parameters.append("s", configId)
            parameters.append("t", trackId)
        }.buildString()

    private val BUILT_IN_KINDS = listOf(SourceKind.JIOSAAVN, SourceKind.YOUTUBE)

    private const val KEY_SOURCES = "bitchord.sources.configs"
}
