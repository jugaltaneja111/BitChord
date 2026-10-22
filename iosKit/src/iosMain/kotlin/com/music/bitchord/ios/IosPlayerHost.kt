package com.music.bitchord.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.ios.canvas.IosCanvas
import com.music.bitchord.ios.party.IosListenTogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.ui.player.AudioOutputDevice
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.ui.player.LyricsRomanizationResult
import com.music.bitchord.ui.player.LyricsTranslationResult
import com.music.bitchord.ui.player.OutputFormatUi
import com.music.bitchord.ui.player.PartyUi
import com.music.bitchord.ui.player.PlayerHost
import com.music.bitchord.ui.player.PlayerSettingsSource
import com.music.bitchord.ui.player.SystemVolume
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the shared pages and player take from the app, as iOS has it so far: the phone's default
 * settings, and no output picker. Each piece is filled in as the
 * iOS app gains the feature behind it. Even the search rows read it (for artwork), so it is
 * installed before anything is drawn.
 */
internal object IosPlayerHost : PlayerHost {
    override val settings: PlayerSettingsSource = IosPlayerSettings

    override fun cachedCanvas(song: Song): CanvasArtwork? = IosCanvas.cached(song)

    override suspend fun canvasFor(song: Song): CanvasArtwork? = IosCanvas.canvasFor(song)

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = IosCanvasVideo(spec, modifier)

    // No remote files (WebDAV, SMB) on iOS yet: every cover is the song's own.
    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    // The hardware buttons own the volume on iOS; the player's bar is hidden (see settings).
    override val volume: SystemVolume = object : SystemVolume {
        override val level: StateFlow<Float> = MutableStateFlow(1f)
        override fun set(level: Float) = Unit
        override fun refresh() = Unit
    }

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> = emptyList()

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = onOpen

    override fun selectAudioOutput(id: Int?) = Unit

    override val outputFormat: StateFlow<OutputFormatUi> = MutableStateFlow(OutputFormatUi())

    override val party: StateFlow<PartyUi> = IosListenTogether.state
        .map { PartyUi(inParty = it.inParty, controlsLocked = it.controlsLocked, members = it.members, you = it.you, code = it.code) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Main), SharingStarted.Eagerly, PartyUi())

    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    override fun showMessage(message: String) {
        println("BitChord: $message")
        IosToast.show(message)
    }

    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) = Unit
}

/**
 * The phone's defaults (AppSettings). The ones the Settings page offers are the listener's,
 * kept in the app's defaults (see [IosSettings]); the rest are fixed until iOS has what they set.
 */
internal object IosPlayerSettings : PlayerSettingsSource {
    override val animatedCanvas = IosSettings.animatedCanvas.flow
    override val audioQualityCellular = IosSettings.audioQualityCellular.flow.asQuality()
    override val audioQualityWifi = IosSettings.audioQualityWifi.flow.asQuality()
    override val canvasOverCellular = IosSettings.canvasOverCellular.flow
    override val fullBleedArtwork = IosSettings.fullBleedArtwork.flow
    override val hideSongStatus = IosSettings.hideSongStatus.flow
    override val showQueueHistory = IosSettings.showQueueHistory.flow
    // The bar would drive nothing: an iOS app cannot set the system volume.
    override val hideVolumeBar = MutableStateFlow(true)
    override val hideStatusBar = MutableStateFlow(false)
    override val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)
    override val legacyMeshGradient = IosSettings.legacyMeshGradient.flow
    override val lyricsBlur = IosSettings.lyricsBlur.flow
    override val lyricsOffsetMs = IosSettings.lyricsOffsetMs.flow
    override val lyricsSourceOrder = IosSettings.lyricsSourceOrder.flow
    override val meteredConnection = IosNetwork.metered
    override val preferUsbDac = MutableStateFlow(false)
    override val prioritizeSpotifyCanvas = IosSettings.prioritizeSpotifyCanvas.flow
    override val reduceAnimation = IosSettings.reduceAnimation.flow
    override val reduceDynamicBlur = IosSettings.reduceDynamicBlur.flow
    override val showNerdStats = IosSettings.showNerdStats.flow
    override val smartAnalysis = MutableStateFlow(SmartAnalysis())
    override val smartFadeEnabled = IosSettings.automix.flow
    override val smartMixInProgress = MutableStateFlow(false)
    override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    override val spotifyCanvasAutoHide = IosSettings.spotifyCanvasAutoHide.flow
    override val syncedLyrics = IosSettings.syncedLyrics.flow
    override val translationLanguage = IosSettings.translationLanguage.flow
    override val versionAlignmentInProgress = MutableStateFlow(false)

    override fun setLastPlayerScreen(value: LastPlayerScreen) {
        lastPlayerScreen.value = value
    }

    override fun setLyricsOffsetMs(value: Int) {
        IosSettings.lyricsOffsetMs.set(value)
    }
}

private fun StateFlow<String>.asQuality(): StateFlow<AudioQuality> =
    map { name -> AudioQuality.entries.firstOrNull { it.name == name } ?: AudioQuality.LOSSLESS }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Main), SharingStarted.Eagerly, AudioQuality.LOSSLESS)
