package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.innertube.potoken.PO_TOKEN_HTML
import com.music.bitchord.data.innertube.potoken.PoTokenException
import com.music.bitchord.data.innertube.potoken.PoTokenMinter
import com.music.bitchord.data.innertube.potoken.parseChallengeData
import com.music.bitchord.data.innertube.potoken.parseIntegrityTokenData
import com.music.bitchord.data.innertube.potoken.stringToU8
import com.music.bitchord.data.innertube.potoken.u8ToBase64
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.WebKit.WKContentWorld
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One loaded BotGuard on iOS: the phone's `PoTokenWebView`, in a WKWebView. The same page
 * ([PO_TOKEN_HTML]) runs the challenge YouTube's Create endpoint hands out, trades the result at
 * GenerateIT for an integrity token, and mints PoTokens from it. WKWebView awaits the page's
 * promises itself (`callAsyncJavaScript`), so no message bridge is needed.
 *
 * Kept in the window, invisibly, so its timers run as a page in view does rather than throttled.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPoTokenWebView private constructor() : PoTokenMinter {
    private val navigation = PageLoad()
    private val webView = WKWebView(
        frame = CGRectMake(0.0, 0.0, 1.0, 1.0),
        configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        },
    ).apply {
        customUserAgent = USER_AGENT
        alpha = 0.0
        userInteractionEnabled = false
        navigationDelegate = navigation
    }

    private var expiresAt = 0.0
    private var closed = false

    override val isExpired: Boolean
        get() = NSDate().timeIntervalSince1970 > expiresAt

    override var isDead: Boolean = false
        private set

    private suspend fun start() {
        withContext(Dispatchers.Main) {
            UIApplication.sharedApplication.keyWindow?.addSubview(webView)
            webView.loadHTMLString(PO_TOKEN_HTML, baseURL = NSURL(string = "https://www.youtube.com"))
        }
        navigation.loaded.await()
        val challenge = parseChallengeData(botguardRequest(CREATE_URL, "[ \"$REQUEST_KEY\" ]"))
        val botguardResponse = run(
            """
            var result = await runBotGuard($challenge);
            window.webPoSignalOutput = result.webPoSignalOutput;
            return result.botguardResponse;
            """,
        ) ?: throw PoTokenException("BotGuard gave no response")
        val (integrityToken, lifetimeSeconds) =
            parseIntegrityTokenData(botguardRequest(GENERATE_IT_URL, "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"))
        // Ten minutes' margin, as the phone leaves.
        expiresAt = NSDate().timeIntervalSince1970 + lifetimeSeconds - EXPIRY_MARGIN_S
        run(
            """
            window.integrityToken = $integrityToken;
            await createPoTokenMinter(window.webPoSignalOutput, window.integrityToken);
            return "ok";
            """,
        )
        TrackLog.d(TAG, "BotGuard ready; integrity token for ${lifetimeSeconds}s")
    }

    override suspend fun generatePoToken(identifier: String): String {
        if (isDead || closed) throw PoTokenException("PoToken page is dead or closed")
        return try {
            withTimeout(GENERATE_TIMEOUT_MS) {
                val bytes = run(
                    """
                    var token = await obtainPoToken(${stringToU8(identifier)});
                    return Array.prototype.join.call(token, ",");
                    """,
                ) ?: throw PoTokenException("obtainPoToken returned nothing")
                u8ToBase64(bytes)
            }
        } catch (e: TimeoutCancellationException) {
            isDead = true
            throw PoTokenException("poToken generation timed out after ${GENERATE_TIMEOUT_MS}ms")
        }
    }

    /** Runs [body] as an async function in the page and hands back what it returns, as text. */
    private suspend fun run(body: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.callAsyncJavaScript(
                body,
                arguments = null,
                inFrame = null,
                inContentWorld = WKContentWorld.pageWorld,
            ) { result: Any?, error: NSError? ->
                if (!cont.isActive) return@callAsyncJavaScript
                if (error != null) {
                    cont.resumeWithException(PoTokenException(error.userInfo["WKJavaScriptExceptionMessage"]?.toString() ?: error.localizedDescription))
                } else {
                    cont.resume(result?.toString())
                }
            }
        }
    }

    private suspend fun botguardRequest(url: String, body: String): String {
        val response = http.post(url) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
            header("Content-Type", "application/json+protobuf")
            header("x-goog-api-key", GOOGLE_API_KEY)
            header("x-user-agent", "grpc-web-javascript/0.1")
            setBody(body)
        }
        val text = response.bodyAsText()
        if (response.status.value != 200 || text.isEmpty()) {
            throw PoTokenException("Invalid botguard response (code=${response.status.value})")
        }
        return text
    }

    override fun close() {
        if (closed) return
        closed = true
        dispatchMain {
            webView.stopLoading()
            webView.navigationDelegate = null
            webView.removeFromSuperview()
        }
    }

    private class PageLoad : NSObject(), WKNavigationDelegateProtocol {
        val loaded = CompletableDeferred<Unit>()

        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            loaded.complete(Unit)
        }

        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            loaded.completeExceptionally(PoTokenException("BotGuard page failed to load: ${withError.localizedDescription}"))
        }

        override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
            loaded.completeExceptionally(PoTokenException("BotGuard page's process ended"))
        }
    }

    companion object {
        private const val TAG = "IosPoToken"
        private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val INIT_TIMEOUT_MS = 45_000L
        private const val GENERATE_TIMEOUT_MS = 15_000L
        private const val EXPIRY_MARGIN_S = 600.0

        private val http by lazy { appHttpClient() }

        /** A page with BotGuard loaded and a minter made, ready to mint. */
        suspend fun create(): IosPoTokenWebView {
            val page = withContext(Dispatchers.Main) { IosPoTokenWebView() }
            try {
                withTimeout(INIT_TIMEOUT_MS) { page.start() }
                return page
            } catch (e: Throwable) {
                withContext(NonCancellable) { page.close() }
                if (e is TimeoutCancellationException) throw PoTokenException("BotGuard page timed out after ${INIT_TIMEOUT_MS}ms")
                throw e
            }
        }
    }
}

private fun dispatchMain(block: () -> Unit) =
    platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue(), block)
