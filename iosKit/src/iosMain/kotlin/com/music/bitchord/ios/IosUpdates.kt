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
 * The phone's AppUpdateChecker: BitChord isn't in a store, so nothing tells anyone a new version
 * is out but this — the project's latest GitHub release, asked once a launch and compared with
 * the version this build was made from. Where the phone downloads and installs the APK, an iPhone
 * can't install anything itself: the notice opens the release, and the listener signs the new
 * build as they did this one.
 */
internal object IosUpdates {
    private const val TAG = "IosUpdates"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest"

    data class Update(val version: String, val url: String, val notes: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<Update?>(null)
    val available: StateFlow<Update?> = _available.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Whether the once-a-launch notice is up. */
    var showing by mutableStateOf(false)

    /** The version this build was made from. */
    val current: String get() = BITCHORD_VERSION

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
            val tag = field("tag_name") ?: return null
            val url = field("html_url") ?: return null
            val version = tag.removePrefix("v")
            return Update(version, url, field("body")).takeIf { isNewer(version, current) }
        } finally {
            http.close()
        }
    }

    /**
     * Numeric, dotted ("1.10" above "1.9"), with a "-beta" build older than the release of the
     * same numbers, as the phone compares.
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        fun parse(raw: String) = raw.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } to ('-' in raw)
        val (l, lPre) = parse(latest)
        val (c, cPre) = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return cPre && !lPre
    }
}

/** The once-a-launch notice: what's new, and the release to get it from. */
@Composable
internal fun UpdateDialog() {
    val update = IosUpdates.available.value ?: return
    if (!IosUpdates.showing) return
    AlertDialog(
        onDismissRequest = { IosUpdates.showing = false },
        title = { Text(tr("Update available: v{0}", update.version)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    tr("BitChord {0} is out. On iPhone, install it by signing the new build, as you installed this one.", update.version),
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
