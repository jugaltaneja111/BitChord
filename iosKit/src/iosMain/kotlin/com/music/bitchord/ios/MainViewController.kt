package com.music.bitchord.ios

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
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.UIKit.UIViewController

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
    startAutoplayIfAsked()
}

/** The shared pages' settings, at the phone's defaults until iOS has a settings screen. */
private object IosAppUiHost : AppUiHost {
    override val swipeToPlayNext: StateFlow<Boolean> = MutableStateFlow(false)
    override val homeRecentsViewType = MutableStateFlow(LibraryViewType.LIST)
    override fun setHomeRecentsViewType(value: LibraryViewType) {
        homeRecentsViewType.value = value
    }
    override val pinnedPlaylists: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val librarySort: StateFlow<LibrarySort> = MutableStateFlow(LibrarySort.DEFAULT)

    @Composable
    override fun downloadedIds(): Set<String> = emptySet()
}
