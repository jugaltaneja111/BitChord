package com.music.bitchord.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The phone's AppUpdateChecker, for the iOS builds: asked once a launch whether the fork's
 * ios-latest pre-release was built from a different commit than this build. Upstream's numbered
 * releases are Android APKs and say nothing about the iOS port, so they aren't asked. Where the
 * phone downloads and installs the APK, an iPhone can't install anything itself: the notice opens
 * the release, and the listener signs the new build as they did this one.
 */
internal object IosUpdates {
    private const val TAG = "IosUpdates"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/jugaltaneja111/BitChord/releases/tags/ios-latest"

    /** [version] is the new build's short commit. */
    data class Update(val version: String, val url: String, val notes: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<Update?>(null)
    val available: StateFlow<Update?> = _available.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Whether the once-a-launch notice is up. */
    var showing by mutableStateOf(false)

    /** The version this build was made from, and its commit when CI built it. */
    val current: String get() = if (BITCHORD_COMMIT.isEmpty()) BITCHORD_VERSION else "$BITCHORD_VERSION (${BITCHORD_COMMIT.take(7)})"

    /** Asks GitHub; [announce] shows the notice when there is something newer. */
    fun check(announce: Boolean = true, quiet: Boolean = true) {
        if (_checking.value) return
        scope.launch {
            _checking.value = true
            try {
                val found = latest()
                _available.value = found
                if (found != null && announce) showing = true
                if (!quiet && found == null) IosPlayerHost.showMessage(tr("BitChord is up to date"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.d(TAG, "check failed: ${e.message}")
                if (!quiet) IosPlayerHost.showMessage(tr("Couldn’t check for updates"))
            } finally {
                _checking.value = false
            }
        }
    }

    fun openRelease() {
        val url = _available.value?.url ?: return
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
        showing = false
    }

    private suspend fun latest(): Update? {
        val http = appHttpClient { install(HttpTimeout) }
        try {
            val response = http.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
                timeout { requestTimeoutMillis = 15_000 }
            }
            if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
            val release = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return null
            fun field(name: String) = (release[name] as? JsonPrimitive)?.contentOrNull
            val commit = field("target_commitish") ?: return null
            val url = field("html_url") ?: return null
            // A build made outside CI doesn't know its commit, and can't tell.
            if (BITCHORD_COMMIT.isEmpty() || commit == BITCHORD_COMMIT) return null
            return Update(commit.take(7), url, field("body"))
        } finally {
            http.close()
        }
    }
}

/** The once-a-launch notice: what's new, and the release to get it from. */
@Composable
internal fun UpdateDialog() {
    val update = IosUpdates.available.value ?: return
    if (!IosUpdates.showing) return
    AlertDialog(
        onDismissRequest = { IosUpdates.showing = false },
        title = { Text(tr("New iOS build: {0}", update.version)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    tr("A newer BitChord build ({0}) is out. Install it by signing the new IPA, as you installed this one.", update.version),
                    style = MaterialTheme.typography.bodyMedium,
                )
                update.notes?.takeIf(String::isNotBlank)?.let { notes ->
                    Text(
                        notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", "").trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = IosUpdates::openRelease) { Text(tr("View release")) } },
        dismissButton = { TextButton(onClick = { IosUpdates.showing = false }) { Text(tr("Later")) } },
    )
}
