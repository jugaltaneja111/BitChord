package com.music.bitchord.ios

import com.music.bitchord.data.settings.AudioQuality
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.music.bitchord.data.lyrics.TRANSLATION_LANGUAGES
import com.music.bitchord.data.lyrics.translationLanguageName
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.lyrics.LyricsSource
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

/**
 * Every setting a backup carries, by its name without the app's prefix — the names the phone
 * uses where the two share a setting, so a backup from either restores what both have. Each
 * reads and writes its own type: a value is stored as text with its type beside it, and one
 * that doesn't parse as that type is skipped rather than guessed at. Credentials ([SecretPref])
 * are never listed.
 */
internal object IosPrefs {
    class Entry(val type: String, val read: () -> String?, val write: (String) -> Unit)

    val all = LinkedHashMap<String, Entry>()

    fun register(key: String, type: String, read: () -> String?, write: (String) -> Unit) {
        all[key.removePrefix(PREFIX)] = Entry(type, read, write)
    }

    const val PREFIX = "bitchord.settings."
}

/** One on/off setting, kept in the app's defaults under [key]. */
internal class BoolPref(private val key: String, default: Boolean) {
    private val defaults = NSUserDefaults.standardUserDefaults

    /** Read by whatever the setting changes. Written only through [set], which keeps it. */
    val flow = MutableStateFlow(
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else default,
    )

    init {
        IosPrefs.register(key, "bool", { flow.value.toString() }) { text -> text.toBooleanStrictOrNull()?.let(::set) }
    }

    fun set(value: Boolean) {
        defaults.setBool(value, key)
        flow.value = value
    }
}

internal class IntPref(private val key: String, default: Int) {
    private val defaults = NSUserDefaults.standardUserDefaults

    val flow = MutableStateFlow(
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else default,
    )

    init {
        IosPrefs.register(key, "int", { flow.value.toString() }) { text -> text.toIntOrNull()?.let(::set) }
    }

    fun set(value: Int) {
        defaults.setInteger(value.toLong(), key)
        flow.value = value
    }
}

internal class StringPref(private val key: String, default: String) : StringPrefLike {
    private val defaults = NSUserDefaults.standardUserDefaults

    override val flow = MutableStateFlow(defaults.stringForKey(key) ?: default)

    init {
        IosPrefs.register(key, "string", { flow.value }) { set(it) }
    }

    override fun set(value: String) {
        defaults.setObject(value, key)
        flow.value = value
    }
}

/** A credential: kept in the Keychain (see [IosKeychain]), read and written like any setting. */
internal class SecretPref(private val key: String) : StringPrefLike {
    override val flow = MutableStateFlow(IosKeychain.get(key).orEmpty())

    override fun set(value: String) {
        if (value.isEmpty()) IosKeychain.remove(key) else IosKeychain.set(key, value)
        flow.value = value
    }
}

internal interface StringPrefLike {
    val flow: MutableStateFlow<String>
    fun set(value: String)
}

internal class FloatPref(private val key: String, default: Float) {
    private val defaults = NSUserDefaults.standardUserDefaults

    val flow = MutableStateFlow(
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else default,
    )

    init {
        IosPrefs.register(key, "float", { flow.value.toString() }) { text -> text.toFloatOrNull()?.let(::set) }
    }

    fun set(value: Float) {
        defaults.setFloat(value, key)
        flow.value = value
    }
}

/** A list of numbers, comma-separated as the phone keeps its equaliser bands. */
internal class FloatListPref(private val key: String, private val default: List<Float>) {
    private val defaults = NSUserDefaults.standardUserDefaults

    val flow = MutableStateFlow(
        defaults.stringForKey(key)?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == default.size }
            ?: default,
    )

    init {
        IosPrefs.register(key, "string", { flow.value.joinToString(",") }) { text ->
            text.split(",").mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == default.size }?.let(::set)
        }
    }

    fun set(value: List<Float>) {
        defaults.setObject(value.joinToString(","), key)
        flow.value = value
    }
}

/**
 * Lyrics sources kept by name, as the phone keeps them: a name a later build drops falls out, and
 * nothing saved means the default.
 */
internal class LyricsSourcesPref(private val key: String, private val default: () -> List<LyricsSource>) {
    private val defaults = NSUserDefaults.standardUserDefaults

    val flow = MutableStateFlow(
        defaults.stringForKey(key)?.split(",")?.mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }
            ?: default(),
    )

    init {
        IosPrefs.register(key, "string", { flow.value.joinToString(",") { it.name } }) { text ->
            set(text.split(",").mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } })
        }
    }

    fun set(value: List<LyricsSource>) {
        defaults.setObject(value.joinToString(",") { it.name }, key)
        flow.value = value
    }
}

/**
 * The listener's settings, as the phone's AppSettings names them — only those the iOS app has
 * something to apply to. The rest of the phone's page (downloads, equalizer, crossfade, local
 * files, lyrics providers…) comes with the features they set.
 */
internal object IosSettings {
    private const val PREFIX = "bitchord.settings."

    val autoplay = BoolPref("${PREFIX}autoplay", true)
    val fullBleedArtwork = BoolPref("${PREFIX}full_bleed_artwork", true)
    val legacyMeshGradient = BoolPref("${PREFIX}legacy_mesh_gradient", false)
    val reduceAnimation = BoolPref("${PREFIX}reduce_animation", false)
    val reduceDynamicBlur = BoolPref("${PREFIX}reduce_dynamic_blur", false)
    val hideSongStatus = BoolPref("${PREFIX}hide_song_status", false)
    val showQueueHistory = BoolPref("${PREFIX}show_queue_history", false)
    val showNerdStats = BoolPref("${PREFIX}show_nerd_stats", false)
    /** A swiped row plays next instead of joining the end of the queue. */
    val swipePlayNext = BoolPref("${PREFIX}swipe_play_next", false)
    /** AutoPlay suggests nothing already played this session. */
    val dontRepeatSongs = BoolPref("${PREFIX}dont_repeat_songs", false)

    /** The ceiling a download is resolved under: 320 takes the best AAC there is. */
    val downloadKbps = IntPref("${PREFIX}download_kbps", 320)
    /**
     * YouTube's Opus (about 160 kbps, through the FFmpeg player) over its 128 kbps AAC. Off until
     * the FFmpeg path has been heard on a phone; AAC is what every YouTube track played before it.
     */
    val youtubeOpus = BoolPref("${PREFIX}youtube_opus", false)
    val skipSilence = BoolPref("${PREFIX}skip_silence", false)
    /** SYSTEM, LIGHT or DARK, as the phone stores it. */
    val themeMode = StringPref("${PREFIX}theme_mode", "DARK")
    /** A BCP 47 tag, or empty for the phone's own language. */
    val appLanguage = StringPref("${PREFIX}app_language", "")
    /** The phone's per-connection ceilings, by name; Lossless by default, as there. */
    val audioQualityWifi = StringPref("${PREFIX}audio_quality_wifi", AudioQuality.LOSSLESS.name)
    val audioQualityCellular = StringPref("${PREFIX}audio_quality_cellular", AudioQuality.LOSSLESS.name)

    /** The ceiling for the connection in hand: mobile data's when metered, else Wi-Fi's. */
    val effectiveAudioQuality: AudioQuality
        get() = (if (IosNetwork.metered.value == true) audioQualityCellular else audioQualityWifi).flow.value
            .let { name -> AudioQuality.entries.firstOrNull { it.name == name } ?: AudioQuality.LOSSLESS }
    val downloadWifiOnly = BoolPref("${PREFIX}download_wifi_only", false)
    /** For a music video, play the catalogue's audio release in its place. */
    val preferMusicOnly = BoolPref("${PREFIX}prefer_music_only", false)

    /** Spotify's `sp_dc` session cookie, as the phone keeps it; blank when not connected. */
    val spotifySpdc = SecretPref("spotify_sp_dc")

    // Scrobbling, the phone's defaults: everything off until signed in.
    val lastfmEnabled = BoolPref("${PREFIX}lastfm_enabled", false)
    val lastfmUsername = StringPref("${PREFIX}lastfm_username", "")
    val lastfmSessionKey = SecretPref("lastfm_session_key")
    /** The listener's own key and secret, asked for only when the build carries none. */
    val lastfmApiKey = StringPref("${PREFIX}lastfm_api_key", "")
    val lastfmSecret = SecretPref("lastfm_secret")
    val lastfmScrobbleEnabled = BoolPref("${PREFIX}lastfm_scrobble_enabled", false)
    val lastfmNowPlaying = BoolPref("${PREFIX}lastfm_now_playing", false)
    val lastfmPrimaryArtistOnly = BoolPref("${PREFIX}lastfm_primary_artist_only", false)
    val scrobbleMinDuration = IntPref("${PREFIX}scrobble_min_duration", 30)
    val scrobbleDelayPercent = FloatPref("${PREFIX}scrobble_delay_percent", 0.5f)
    val scrobbleDelaySeconds = IntPref("${PREFIX}scrobble_delay_seconds", 180)
    val listenBrainzEnabled = BoolPref("${PREFIX}listenbrainz_enabled", false)
    val listenBrainzToken = SecretPref("listenbrainz_token")

    // Discord rich presence, the phone's defaults.
    val discordToken = SecretPref("discord_token")
    val discordUsername = StringPref("${PREFIX}discord_username", "")
    val discordName = StringPref("${PREFIX}discord_name", "")
    val discordAvatar = StringPref("${PREFIX}discord_avatar", "")
    val discordRpcEnabled = BoolPref("${PREFIX}discord_rpc_enabled", true)
    val discordUseDetails = BoolPref("${PREFIX}discord_use_details", false)
    val discordStatus = StringPref("${PREFIX}discord_status", "online")
    val discordActivityType = StringPref("${PREFIX}discord_activity_type", "listening")
    val discordActivityName = StringPref("${PREFIX}discord_activity_name", "")
    val discordButton1Text = StringPref("${PREFIX}discord_button_1_text", "")
    val discordButton1Visible = BoolPref("${PREFIX}discord_button_1_visible", true)
    val discordButton2Text = StringPref("${PREFIX}discord_button_2_text", "")
    val discordButton2Visible = BoolPref("${PREFIX}discord_button_2_visible", true)
    val listenBrainzPrimaryArtistOnly = BoolPref("${PREFIX}listenbrainz_primary_artist_only", false)

    // Animated cover art, the phone's defaults.
    val animatedCanvas = BoolPref("${PREFIX}animated_canvas", true)
    val canvasOverCellular = BoolPref("${PREFIX}canvas_over_cellular", false)
    val prioritizeSpotifyCanvas = BoolPref("${PREFIX}prioritize_spotify_canvas", false)
    val spotifyCanvasAutoHide = BoolPref("${PREFIX}spotify_canvas_auto_hide", true)

    // Playback sound, the phone's defaults.
    val loudnessNormalization = BoolPref("${PREFIX}loudness_normalization", true)
    val loudnessOffOnSpeaker = BoolPref("${PREFIX}loudness_off_on_speaker", true)
    val crossfadeSeconds = IntPref("${PREFIX}crossfade_seconds", 0)
    val equalizerEnabled = BoolPref("${PREFIX}equalizer_enabled", false)
    /** Which tab drives the sound: 0 the tone pad (Dynamic, the phone's default), 1 the bands. */
    val equalizerMode = IntPref("${PREFIX}equalizer_mode", 0)
    val equalizerToneX = IntPref("${PREFIX}equalizer_tone_x", 0)
    val equalizerToneY = IntPref("${PREFIX}equalizer_tone_y", 0)
    val equalizerFocused = BoolPref("${PREFIX}equalizer_focused", false)
    val equalizerBalance = FloatPref("${PREFIX}equalizer_balance", 0f)
    val equalizerBands = FloatListPref("${PREFIX}equalizer_bands", com.music.bitchord.playback.EqualizerPreset.FLAT.bands)

    val syncedLyrics = BoolPref("${PREFIX}synced_lyrics", true)
    /** Blurs the lyric lines that aren't being sung. */
    val lyricsBlur = BoolPref("${PREFIX}lyrics_blur", true)
    /** A line-timed answer only stands in while the rest of the order is searched for word timing. */
    val prioritizeSyllableSync = BoolPref("${PREFIX}prioritize_syllable_sync", false)
    /** Megabytes of played songs kept on the phone; 0 is off, [IosSongCache.UNLIMITED_MB] no limit. */
    val songCacheMb = IntPref("${PREFIX}song_cache_mb", 512)
    /** The language the translate button turns lyrics into; blank follows the app's language. */
    val translationLanguage = StringPref("${PREFIX}translation_language", "")
    /** Unlocks PaxSenix's Spotify and Musixmatch routes. A credential, so in the Keychain. */
    val paxSenixApiKey = SecretPref("paxsenix_api_key")
        .also { com.music.bitchord.data.lyrics.PaxSenix.setApiKey(it.flow.value) }

    fun setPaxSenixApiKey(value: String) {
        val normalized = com.music.bitchord.data.lyrics.normalizePaxSenixApiKey(value)
        paxSenixApiKey.set(normalized)
        com.music.bitchord.data.lyrics.PaxSenix.setApiKey(normalized)
    }
    val lyricsOffsetMs = IntPref("${PREFIX}lyrics_offset_ms", 0)
    /** The sources asked; every offered one unless the listener turned some off. */
    val lyricsSources = LyricsSourcesPref("${PREFIX}lyrics_sources") { LyricsSource.offered }
    /** The order they are asked in, brought up to date with this build's sources. */
    val lyricsSourceOrder = LyricsSourcesPref("${PREFIX}lyrics_source_order") { LyricsSource.offered }
        .also { it.flow.value = LyricsSource.ordered(it.flow.value) }
}

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GroupColor = Color(0xFF1C1C1E)

@Composable
internal fun SettingsPage(
    contentPadding: PaddingValues,
    topPadding: androidx.compose.ui.unit.Dp,
    onClearSearchHistory: () -> Unit,
    onOpenLyricsSources: () -> Unit,
    onOpenTranslationLanguage: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenReplay: () -> Unit,
    onOpenListenTogether: () -> Unit,
) {
    val translationLanguage by IosSettings.translationLanguage.flow.collectAsState()
    val enabledLyricsSources by IosSettings.lyricsSources.flow.collectAsState()
    val lyricsSourceCount = "${enabledLyricsSources.count { !it.hidden }} of ${LyricsSource.offered.size}"
    val signedIn by IosAccount.signedIn.collectAsState()
    val account by IosAccount.account.collectAsState()
    val accounts by IosAccount.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    // The phone's page, group for group. A row whose feature the iOS app does not have yet is
    // listed, greyed and marked, rather than left out or offered as a switch that does nothing.
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item {
            Group("Account & integrations") {
                if (signedIn) {
                    Row(
                        Modifier.fillMaxWidth().clickable { IosOverlays.switcher = true }.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = account?.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2C2C2E)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account?.name.orEmpty().ifBlank { "YouTube Music" }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                account?.email.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("Switch", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Divider()
                    ActionRow("Add account") { IosOverlays.login = true }
                    Divider()
                    ActionRow(if (accounts.size > 1) "Sign out of all accounts" else "Sign out", destructive = true) {
                        IosAccount.signOut()
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { IosOverlays.login = true }.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Sign in to YouTube Music", color = Color.White)
                            Text(
                                "Your library, likes and personal Home",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Divider()
                ActionRow("Spotify, Last.fm, ListenBrainz, Discord", onClick = onOpenIntegrations)
                Divider()
                ActionRow("Listen together", onClick = onOpenListenTogether)
            }
        }
        item {
            Group("Audio quality") {
                ActionRow("Sources", value = "Where audio comes from", onClick = onOpenSources)
                Divider()
                QualityRow("On Wi-Fi", IosSettings.audioQualityWifi)
                Divider()
                QualityRow("On mobile data", IosSettings.audioQualityCellular)
                Divider()
                Toggle(
                    "YouTube in Opus",
                    "About 160 kbps instead of 128 kbps AAC, decoded with FFmpeg; a track that won’t play falls back to AAC",
                    IosSettings.youtubeOpus,
                )
            }
        }
        item {
            val downloadKbps by IosSettings.downloadKbps.flow.collectAsState()
            val downloaded by IosDownloads.tracks.collectAsState()
            var confirmDelete by remember { mutableStateOf(false) }
            Group("Downloads") {
                ActionRow("Download quality", value = if (downloadKbps >= 256) "High" else "Normal") {
                    IosSettings.downloadKbps.set(if (downloadKbps >= 256) 128 else 320)
                }
                Divider()
                ActionRow(
                    "Downloaded songs",
                    value = "${downloaded.size} · ${megabytes(downloaded.sumOf { it.bytes })}",
                    onClick = onOpenDownloads,
                )
                Divider()
                val wifiOnly by IosSettings.downloadWifiOnly.flow.collectAsState()
                val metered by IosNetwork.metered.collectAsState()
                Toggle(
                    "Download over Wi-Fi only",
                    if (wifiOnly && metered == true) "Blocking: downloads wait for Wi-Fi" else "Downloads wait for Wi-Fi instead of using mobile data",
                    IosSettings.downloadWifiOnly,
                )
                if (downloaded.isNotEmpty()) {
                    Divider()
                    ActionRow(if (confirmDelete) "Tap again to delete every download" else "Delete all downloads", destructive = true) {
                        if (confirmDelete) {
                            IosDownloads.removeAll()
                            confirmDelete = false
                            IosPlayerHost.showMessage("Downloads deleted")
                        } else {
                            confirmDelete = true
                        }
                    }
                }
            }
        }
        item {
            Group("Playback") {
                Toggle("Autoplay", "Keep playing similar songs when the queue ends", IosSettings.autoplay)
                Divider()
                Toggle("Prefer music-only version", "For music videos, show the video details while finding and loading the catalogue audio version", IosSettings.preferMusicOnly)
                Divider()
                Toggle("Loudness normalization", "Levels every track to the same loudness", IosSettings.loudnessNormalization)
                Divider()
                Toggle("Disable on Phone Speaker", "The phone’s own speaker plays every track as it was mastered", IosSettings.loudnessOffOnSpeaker)
                Divider()
                CrossfadeRow()
                Divider()
                Soon("Automix", "Times and blends transitions automatically, without a slider")
                Divider()
                Toggle("Skip silence", "Trim gaps longer than a second", IosSettings.skipSilence)
                Divider()
                val equalizerOn by IosSettings.equalizerEnabled.flow.collectAsState()
                ActionRow("Equalizer", value = if (equalizerOn) "On" else "Off", onClick = onOpenEqualizer)
            }
        }
        item {
            Group("Appearance") {
                Soon("Theme", "The iOS app is dark for now")
                Divider()
                Toggle("Reduce animation", "Freezes the main player's gradient instead of drifting", IosSettings.reduceAnimation)
                Divider()
                Toggle("Reduce dynamic blur", "Swaps frosted glass for solid fills across the app", IosSettings.reduceDynamicBlur)
                Divider()
                Toggle("Full-screen cover art", "Runs the cover to the edges of the player instead of a square sleeve", IosSettings.fullBleedArtwork)
                Divider()
                Toggle("Legacy mesh gradient", "Brings back the drifting colour blobs behind the player from v1.5", IosSettings.legacyMeshGradient)
                Divider()
                Toggle("Animated cover art", "Plays the looping video some releases ship instead of a still sleeve", IosSettings.animatedCanvas)
                val animated by IosSettings.animatedCanvas.flow.collectAsState()
                if (animated) {
                    Divider()
                    Toggle("Play animated cover over cellular", "Clips use data; off, they play on Wi-Fi only", IosSettings.canvasOverCellular)
                    Divider()
                    Toggle("Prioritize Spotify Canvas", "Try Spotify before Apple Music and other animated cover providers (needs Spotify connected)", IosSettings.prioritizeSpotifyCanvas)
                    Divider()
                    Toggle("Auto-hide player controls", "Collapse the half-player after 5 seconds while Spotify Canvas is playing", IosSettings.spotifyCanvasAutoHide)
                }
                Divider()
                Toggle("Synced lyrics", "Lights up the words on the player as they’re sung", IosSettings.syncedLyrics)
                Divider()
                Toggle("Blur other lines", "Softens the lyric lines that aren’t being sung", IosSettings.lyricsBlur)
                Divider()
                Toggle("Prefer word-by-word timing", "Keeps looking past a line-timed answer for one timed by the word", IosSettings.prioritizeSyllableSync)
                Divider()
                ActionRow("Lyrics sources", value = "${lyricsSourceCount}", onClick = onOpenLyricsSources)
                Divider()
                ActionRow(
                    "Translation language",
                    value = translationLanguage.ifBlank { null }?.let { translationLanguageName(it, deviceLanguageTag()) }
                        ?: "App language",
                    onClick = onOpenTranslationLanguage,
                )
            }
        }
        item {
            Group("App icon") { AppIconPicker() }
        }
        item {
            Group("Local music") {
                Soon("Music from the Files app")
            }
        }
        item {
            var imageCache by remember { mutableStateOf(imageCacheSize()) }
            Group("Storage") {
                ActionRow("Clear image cache", value = imageCache.takeIf { it > 0 }?.let(::megabytes)) {
                    scope.launch {
                        clearImageCache()
                        imageCache = imageCacheSize()
                        IosPlayerHost.showMessage("Image cache cleared")
                    }
                }
                Divider()
                SongCacheRow()
                Divider()
                val songCache by IosSongCache.bytes.collectAsState()
                ActionRow("Clear song cache", value = songCache.takeIf { it > 0 }?.let(::megabytes)) {
                    IosSongCache.clear()
                    IosPlayerHost.showMessage("Song cache cleared")
                }
            }
        }
        item {
            Group("Your data") {
                ActionRow("Replay", value = "Top songs, artists, albums", onClick = onOpenReplay)
                Divider()
                ActionRow("Clear search history") {
                    onClearSearchHistory()
                    IosPlayerHost.showMessage("Search history cleared")
                }
                Divider()
                ActionRow("Export data", value = "Settings and listening", onClick = IosBackup::export)
                Divider()
                ActionRow("Import data", onClick = IosBackup::import)
            }
        }
        item {
            Group("Miscellaneous") {
                Toggle("Play next on swipe", "A swiped song plays next instead of joining the end of the queue", IosSettings.swipePlayNext)
                Divider()
                Toggle("Don’t repeat songs in current session", "AutoPlay won’t suggest a song already played this session", IosSettings.dontRepeatSongs)
                Divider()
                Toggle("Show queue history", "Show up to 25 previously played songs above Now playing in the queue", IosSettings.showQueueHistory)
                Divider()
                Toggle("Hide song status", "Hides the “Playing from” or “Played by” text in the main player", IosSettings.hideSongStatus)
            }
        }
        item {
            Group("Language") {
                Soon("App language")
            }
        }
        item {
            Group("Advanced & experimental") {
                Toggle("Show stats for nerds", "Codec, bitrate and sample rate on the player", IosSettings.showNerdStats)
                Divider()
                // The phone's "Copy log": what the player did, to paste into a bug report.
                ActionRow("Copy playback log") {
                    platform.UIKit.UIPasteboard.generalPasteboard.string = com.music.bitchord.data.TrackLog.all()
                    IosPlayerHost.showMessage("Playback log copied")
                }
                Divider()
                Soon("Smart audio alignment")
            }
        }
        item {
            val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
            Group("About") {
                Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Version", color = Color.White)
                    Text(version ?: "–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Which lyrics services are asked, and in what order: the phone's Lyrics sources dialog as a page.
 * Every enabled source is asked at once, and the answers are taken top to bottom.
 */
@Composable
internal fun LyricsSourcesPage(contentPadding: PaddingValues, topPadding: androidx.compose.ui.unit.Dp) {
    val paxSenixKey by IosSettings.paxSenixApiKey.flow.collectAsState()
    var editingKey by remember { mutableStateOf(false) }
    val order by IosSettings.lyricsSourceOrder.flow.collectAsState()
    val enabled by IosSettings.lyricsSources.flow.collectAsState()
    LazyColumn(
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Lyrics sources",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                "Every source that’s on is asked at the same time; the first one in this list to answer wins. Word-timed sources light up each word as it’s sung.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).clip(GroupShape).background(GroupColor)) {
                order.forEachIndexed { index, source ->
                    if (index > 0) Divider()
                    val on = source in enabled
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(source.label, color = if (on) Color.White else Color.White.copy(alpha = 0.45f))
                            Text(
                                (if (source.wordSynced) "Word by word · " else "Line by line · ") + source.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { IosSettings.lyricsSourceOrder.set(order.moved(index, index - 1)) }, enabled = index > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up", tint = if (index > 0) Color.White else Color.White.copy(alpha = 0.2f))
                        }
                        IconButton(onClick = { IosSettings.lyricsSourceOrder.set(order.moved(index, index + 1)) }, enabled = index < order.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down", tint = if (index < order.lastIndex) Color.White else Color.White.copy(alpha = 0.2f))
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { checked ->
                                IosSettings.lyricsSources.set(if (checked) enabled + source else enabled - source)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF34C759),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFF39393D),
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp).clip(GroupShape).background(GroupColor)) {
                ActionRow(
                    "PaxSenix API key",
                    value = if (paxSenixKey.isBlank()) "Optional" else "Configured",
                    onClick = { editingKey = true },
                )
                Divider()
                ActionRow("Reset to default") {
                    IosSettings.lyricsSourceOrder.set(LyricsSource.offered)
                    IosSettings.lyricsSources.set(LyricsSource.offered)
                }
            }
            Text(
                "The key unlocks PaxSenix’s Spotify and Musixmatch sources. It stays on this iPhone, in the Keychain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
    }
    if (editingKey) {
        var input by remember(paxSenixKey) { mutableStateOf(paxSenixKey) }
        AlertDialog(
            onDismissRequest = { editingKey = false },
            title = { Text("PaxSenix API key") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    IosSettings.setPaxSenixApiKey(input)
                    editingKey = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingKey = false }) { Text("Cancel") } },
        )
    }
}

/** The phone's Translation language picker as a page: the app's language first, then every one Translate offers. */
@Composable
internal fun TranslationLanguagePage(contentPadding: PaddingValues, topPadding: androidx.compose.ui.unit.Dp) {
    val selected by IosSettings.translationLanguage.flow.collectAsState()
    val locale = remember { deviceLanguageTag() }
    var query by remember { mutableStateOf("") }
    val named = remember(locale) { TRANSLATION_LANGUAGES.map { it.code to translationLanguageName(it.code, locale) } }
    val shown = remember(named, query) {
        val needle = query.trim()
        if (needle.isEmpty()) named
        else named.filter { (code, name) -> name.contains(needle, ignoreCase = true) || code.equals(needle, ignoreCase = true) }
    }
    LazyColumn(
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Translation language",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                "The language the translate button turns lyrics into.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search languages") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).clip(GroupShape).background(GroupColor)) {
                if (query.isBlank()) {
                    LanguageRow("App language", selected.isBlank()) { IosSettings.translationLanguage.set("") }
                }
                shown.forEach { (code, name) ->
                    Divider()
                    LanguageRow(name, selected.equals(code, ignoreCase = true)) { IosSettings.translationLanguage.set(code) }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(name: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color(0xFF34C759))
    }
}

/** The iPhone's first preferred language as a BCP 47 tag ("en-IN"), for naming languages in it. */
internal fun deviceLanguageTag(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.ifBlank { null } ?: "en"

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * One connection's quality ceiling, chosen from the phone's four. On the iPhone YouTube serves AAC
 * at about 128 kbps whatever is chosen; the higher rungs are what JioSaavn and add-ons are asked for.
 */
@Composable
private fun QualityRow(title: String, pref: StringPref) {
    val chosen by pref.flow.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val quality = AudioQuality.entries.firstOrNull { it.name == chosen } ?: AudioQuality.LOSSLESS
    ActionRow(title, value = quality.label) { picking = true }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(title) },
            text = {
                Column {
                    AudioQuality.entries.reversed().forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pref.set(option.name)
                                picking = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.label, color = Color.White)
                                Text(
                                    if (option == AudioQuality.MEDIUM) "Best available · ~128 kbps AAC" else option.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (option == quality) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color(0xFF34C759))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Done") } },
        )
    }
}

/** The phone's Song cache limit slider, with Off at the left. */
@Composable
private fun SongCacheRow() {
    val mb by IosSettings.songCacheMb.flow.collectAsState()
    val label = when (mb) {
        0 -> "Off"
        IosSongCache.UNLIMITED_MB -> "Unlimited"
        else -> if (mb >= 1024) "${mb / 1024}.${(mb % 1024) * 10 / 1024} GB" else "$mb MB"
    }
    Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Song cache", color = Color.White)
                Text(
                    "Keeps songs you’ve played on this iPhone for instant replays, saved over Wi-Fi only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = mb.toFloat(),
            onValueChange = { IosSettings.songCacheMb.set((it / IosSongCache.STEP_MB).roundToInt() * IosSongCache.STEP_MB) },
            onValueChangeFinished = { IosSongCache.trim() },
            valueRange = 0f..IosSongCache.UNLIMITED_MB.toFloat(),
            steps = IosSongCache.UNLIMITED_MB / IosSongCache.STEP_MB - 1,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** The phone's crossfade slider: off, or one to twelve seconds of blend. */
@Composable
private fun CrossfadeRow() {
    val seconds by IosSettings.crossfadeSeconds.flow.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Crossfade", color = Color.White)
                Text("Blends one track into the next", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (seconds == 0) "Off" else "${seconds}s", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = seconds.toFloat(),
            onValueChange = { IosSettings.crossfadeSeconds.set(it.roundToInt()) },
            valueRange = 0f..12f,
            steps = 11,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** A setting the phone has and this app does not yet: shown, so the page reads the same. */
@Composable
internal fun Soon(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White.copy(alpha = 0.45f))
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("Coming soon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun megabytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val tenths = (bytes * 10 / (1024 * 1024)).coerceAtLeast(1)
    return "${tenths / 10}.${tenths % 10} MB"
}

@Composable
internal fun Group(header: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        if (header.isNotEmpty()) {
            Text(
                header.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(GroupShape).background(GroupColor)) { content() }
    }
}

@Composable
internal fun Divider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.10f))
}

@Composable
internal fun Toggle(title: String, subtitle: String, pref: BoolPref) {
    val checked by pref.flow.collectAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { pref.set(!checked) }.padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked, pref::set)
    }
}

/** The iOS green switch every settings row uses. */
@Composable
internal fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF34C759),
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF39393D),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun ActionRow(title: String, destructive: Boolean = false, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 15.dp)) {
        Text(title, color = if (destructive) Color(0xFFFF6B6B) else Color.White, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
