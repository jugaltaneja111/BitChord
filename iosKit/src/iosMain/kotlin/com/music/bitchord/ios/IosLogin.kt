package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.music.bitchord.auth.normalizeDataSyncId
import com.music.bitchord.data.TrackLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataRecord
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * Google sign-in for YouTube Music, as the phone does it (app/…/auth/YtMusicLoginScreen.kt): the
 * real accounts.google.com page in an in-app browser, so 2FA and passkeys work as they do
 * anywhere, then the session read off the music.youtube.com page it lands on. Nothing is taken
 * until the listener says so, which leaves an account with several channels time to pick one.
 */
internal object IosLogin {
    const val MUSIC_ORIGIN = "https://music.youtube.com"

    const val LOGIN_URL = "https://accounts.google.com/ServiceLogin" +
        "?ltmpl=music&service=youtube&prompt=select_account" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

    /**
     * Safari's own. WKWebView's default leaves out the "Version/… Safari/…" tokens, and Google
     * answers a browser without them with "this browser or app may not be secure".
     */
    const val SAFARI_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"

    private val GOOGLE_HOSTS = listOf("google.com", "youtube.com", "gstatic.com", "googleusercontent.com")

    /**
     * Forgets the Google login the in-app browser holds. Without this a sign-out is not one: the
     * next sign-in is waved straight through as the account just signed out of.
     */
    fun clearGoogleCookies(onDone: () -> Unit = {}) {
        val store = WKWebsiteDataStore.defaultDataStore()
        val types = WKWebsiteDataStore.allWebsiteDataTypes()
        store.fetchDataRecordsOfTypes(types) { records ->
            val google = records.orEmpty().filterIsInstance<WKWebsiteDataRecord>().filter { record ->
                GOOGLE_HOSTS.any { record.displayName == it || record.displayName.endsWith(".$it") }
            }
            if (google.isEmpty()) {
                onDone()
            } else {
                store.removeDataOfTypes(types, google) { onDone() }
            }
        }
    }

    /** Whether a cookie header carries the secret Innertube requests are signed with (AuthStore.hasApiSid). */
    fun hasApiSid(cookieHeader: String): Boolean = cookieHeader.split(';').any { entry ->
        val name = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        name in API_SID_NAMES && value.isNotEmpty()
    }

    private val API_SID_NAMES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /** The page's own identity, stringified so it comes back as one string. See YtMusicLoginScreen. */
    const val YTCFG_PROBE = """
(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return JSON.stringify({
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    });
  } catch (e) {
    return null;
  }
})()
"""
}

private val probeJson = Json { ignoreUnknownKeys = true }

/** The page's session, or null when it holds none to take yet. */
private fun capture(view: WKWebView, onResult: (IosSession?) -> Unit) {
    view.configuration.websiteDataStore.httpCookieStore.getAllCookies { all ->
        // What a request to music.youtube.com would carry: its own cookies and the domain-wide
        // ones, one of each name, the more specific first.
        val cookies = all.orEmpty().filterIsInstance<NSHTTPCookie>()
            .filter { it.domain == "music.youtube.com" || it.domain == ".youtube.com" || it.domain == "youtube.com" }
            .sortedBy { if (it.domain == "music.youtube.com") 0 else 1 }
            .distinctBy { it.name }
        val header = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        if (!IosLogin.hasApiSid(header)) {
            onResult(null)
            return@getAllCookies
        }
        view.evaluateJavaScript(IosLogin.YTCFG_PROBE) { raw, _ ->
            val config = (raw as? String)
                ?.let { runCatching { probeJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            fun JsonObject.string(key: String): String? =
                (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (config == null || config.string("loggedIn") != "true") {
                TrackLog.w("IosLogin", "confirmation requested before the page exposed a signed-in identity")
                onResult(null)
                return@evaluateJavaScript
            }
            val pageId = config.string("pageId")
            onResult(
                IosSession(
                    cookie = header,
                    pageId = pageId,
                    dataSyncId = pageId ?: normalizeDataSyncId(config.string("dataSyncId")),
                    authUser = config.string("authUser"),
                    visitorData = config.string("visitorData"),
                    clientVersion = config.string("clientVersion"),
                ),
            )
        }
    }
}

/** Reports whether the page that just finished is YouTube Music's, i.e. ready to confirm. */
private class LoginNavigationDelegate(val onPageReady: (Boolean) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onPageReady(webView.URL?.absoluteString?.startsWith(IosLogin.MUSIC_ORIGIN) == true)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosLoginScreen(onClose: () -> Unit) {
    var pageReady by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WKWebView?>(null) }
    // The view holds its delegate weakly.
    val delegate = remember { LoginNavigationDelegate { pageReady = it } }
    var cleared by remember { mutableStateOf(false) }
    remember { IosLogin.clearGoogleCookies { cleared = true } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text("Sign in to YouTube Music", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (pageReady) {
                    Text(
                        if (failed) {
                            "Not signed in on this page yet"
                        } else {
                            "Switch channel from the avatar menu if needed, then use this account"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            if (pageReady) {
                TextButton(
                    enabled = !checking,
                    onClick = {
                        val view = webView ?: return@TextButton
                        checking = true
                        failed = false
                        capture(view) { session ->
                            if (session == null) {
                                checking = false
                                failed = true
                            } else {
                                IosAccount.signIn(session) { accepted ->
                                    checking = false
                                    if (accepted) onClose() else failed = true
                                }
                            }
                        }
                    },
                ) {
                    Text(if (checking) "Checking…" else "Use this account")
                }
            }
        }
        // Opened only once the old Google login is gone, so the account picker is a real choice.
        if (cleared) {
            UIKitView(
                factory = {
                    val configuration = WKWebViewConfiguration().apply {
                        websiteDataStore = WKWebsiteDataStore.defaultDataStore()
                    }
                    WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                        customUserAgent = IosLogin.SAFARI_USER_AGENT
                        navigationDelegate = delegate
                        loadRequest(NSURLRequest(uRL = NSURL(string = IosLogin.LOGIN_URL)))
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
