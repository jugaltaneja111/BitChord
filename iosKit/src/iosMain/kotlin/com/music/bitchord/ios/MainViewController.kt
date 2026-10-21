package com.music.bitchord.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.AppUiHost
import com.music.bitchord.ui.player.PlayerPlatform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.UIKit.UIViewController

/**
 * A link the system handed the app: `bitchord://party/CODE` (or `?code=`), with the invite's
 * server if it carries one. Opens Listen Together on that invite.
 */
@Suppress("unused")
fun handleOpenUrl(url: String) {
    com.music.bitchord.ios.party.IosListenTogether.parseInvite(url)?.let { IosDeepLink.invite = it }
}

/** An invite waiting for the app to show it; [IosApp] takes it once the pages are up. */
internal object IosDeepLink {
    var invite by androidx.compose.runtime.mutableStateOf<com.music.bitchord.ios.party.IosListenTogether.ParsedInvite?>(null)
}

/** What the Swift shell shows: the whole app, drawn by Compose. Called once, at launch. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    start()
    return ComposeUIViewController {
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .components { add(KtorNetworkFetcherFactory(httpClient = { appHttpClient() })) }
                .build()
        }
        IosApp()
    }
}

private var started = false

private fun start() {
    if (started) return
    started = true
    // No PoToken minter on iOS yet: InnerTubeX tries only the clients that need none.
    InnerTubeXResolver.init(CachesPlayerCache(), UserDefaultsConfigStore(), poTokenProvider = null)
    AppUi.install(IosAppUiHost)
    PlayerPlatform.install(IosPlayerHost)
    // Before the first page asks for anything, so it is asked as the listener.
    IosAccount.restore()
    IosDownloads.restore()
    IosArtistFacts.start()
    IosSpotify.install()
    IosDiscord.start()
    IosNetwork.start()
    // Releases a party slot left from last time and runs the first server check.
    com.music.bitchord.ios.party.IosListenTogether.state
    com.music.bitchord.ios.canvas.IosCanvas.prioritizeSpotify = { IosSettings.prioritizeSpotifyCanvas.flow.value }
    startAutoplayIfAsked()
}

/** The shared pages' settings, at the phone's defaults until iOS has a settings screen. */
private object IosAppUiHost : AppUiHost {
    override val swipeToPlayNext: StateFlow<Boolean> = IosSettings.swipePlayNext.flow
    override val homeRecentsViewType = MutableStateFlow(LibraryViewType.LIST)
    override fun setHomeRecentsViewType(value: LibraryViewType) {
        homeRecentsViewType.value = value
    }
    override val pinnedPlaylists: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val librarySort: StateFlow<LibrarySort> = MutableStateFlow(LibrarySort.DEFAULT)

    @Composable
    override fun downloadedIds(): Set<String> = IosDownloads.ids.collectAsState().value
}
