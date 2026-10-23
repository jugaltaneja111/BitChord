package com.music.bitchord.ios.sources

import com.music.bitchord.ios.tr

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose. **[rank] is the order sources are tried** — see
 * [SourceRegistry.active], which sorts on it — so the sources a user added come
 * first, then JioSaavn, then YouTube Music. Adding a source means adding a
 * [MusicSource] implementation and an entry here, which is the point — every
 * protocol the app speaks is one someone can read in this repo, and a source
 * can't teach the app a new way to behave after it ships.
 *
 * The phone's list less its two legacy module kinds (`MODULE`,
 * `CUSTOM_MODULE`): those download a JavaScript plugin per catalogue and run it
 * in QuickJS, which this app does not carry, and the phone itself no longer lets
 * anyone add one. A stored config naming either is dropped on decode — see
 * [SourceRegistry.decodeStored]. The ranks keep the phone's numbers so the two
 * read the same.
 *
 * What varies per *instance* — which addon, whose token — is [SourceConfig].
 */
internal enum class SourceKind(
    val label: String,
    val detail: String,
    /** The chips under the name on the Sources page. */
    val labels: List<String>,
    /** Whether an instance needs a URL before it can do anything — and so needs an editor. */
    val needsServer: Boolean,
    /** Whether this kind can serve bit-exact audio when asked. */
    val canServeLossless: Boolean,
    /**
     * Whether this kind answers quickly enough to be worth asking *before* a
     * track is played, so its copy can be pinned and cached ahead of time.
     *
     * Measured on the phone, JioSaavn answers a search and hands back a stream
     * URL in about 0.4s; a wasted JioSaavn resolve costs one HTTP round trip,
     * which is worth spending speculatively. False for [YOUTUBE] as well,
     * though it *is* warmed ahead of time — through the player's own
     * read-ahead, which speaks video ids directly and needs no cross-source
     * match to find the track.
     */
    val worthPrefetching: Boolean = false,
    /**
     * Where this kind sits in the walk, low first.
     *
     * Separate from [ordinal] because kinds can share a position: every source
     * the user added sits at [USER_ADDED], and which of *those* is asked first
     * is theirs to set — see [SourceRegistry.reorderAddons]. A stable sort on
     * this keeps them in the stored list's order.
     */
    val rank: Int,
) {
    /**
     * An addon server the user pointed at themselves.
     *
     * The one kind that can be added on the Sources page. An addon is a plain
     * HTTP server — `/manifest.json`, `/search`, `/stream`, JSON both ways — so
     * there is no code to download and nothing to sandbox: the app asks
     * questions and reads answers.
     *
     * Any number may be configured, tried in the order the user sets. Declared
     * first so a source the user chose on purpose is asked before anything the
     * app fell back to.
     */
    ADDON(
        label = tr("Addon"),
        detail = tr("An addon server you host or were given a link to. Searched and streamed over plain HTTP — no code is downloaded or run."),
        labels = listOf("FLAC", tr("Lossless"), "Hi-Res"),
        needsServer = true,
        canServeLossless = true,
        // Literal, not [USER_ADDED]: an enum entry is constructed before
        // its own companion exists.
        rank = 0,
    ),

    JIOSAAVN(
        label = "JioSaavn",
        detail = tr("Optional JioSaavn streams up to 320kbps AAC/MP4. Catalogue matching can select the wrong song."),
        labels = listOf(tr("High Quality"), "320kbps"),
        needsServer = false,
        canServeLossless = false,
        worthPrefetching = true,
        rank = 2,
    ),

    /**
     * The source the app was built on, listed here so it always has a fixed
     * place: last. It cannot be removed or switched off — see
     * [SourceRegistry.setEnabled]. Nothing else in the app can supply a home
     * feed, a radio station or a related-tracks queue.
     *
     * On iOS its stream is AAC in MP4 — AVPlayer has no Opus or WebM decoder —
     * so the line says so rather than repeating the phone's Opus figure.
     */
    YOUTUBE(
        label = "YouTube Music",
        detail = tr("The full catalogue, as AAC up to about 256 kbps. Lossy — there is no lossless rendition to ask for."),
        labels = listOf(tr("Lossy"), tr("Full catalogue"), tr("Radio")),
        needsServer = false,
        canServeLossless = false,
        rank = 3,
    ),
    ;

    /** Whether this is a source the user configured, rather than one that ships. */
    val isUserAdded: Boolean get() = rank == USER_ADDED

    companion object {
        /**
         * The shared rank of every source the user added. Their order among
         * themselves is the stored list order, which reordering rewrites.
         */
        const val USER_ADDED = 0
    }
}
