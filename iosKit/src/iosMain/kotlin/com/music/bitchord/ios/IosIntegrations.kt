package com.music.bitchord.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.math.roundToInt

/**
 * The phone's Account & integrations page, as far as the iOS app goes: scrobbling to Last.fm and
 * ListenBrainz, with the timing rules under them. Spotify and Discord join it as they arrive.
 */
@Composable
internal fun IntegrationsPage(contentPadding: PaddingValues, topPadding: Dp, onOpenSpotify: () -> Unit, onOpenDiscord: () -> Unit) {
    val lbEnabled by IosSettings.listenBrainzEnabled.flow.collectAsState()
    val lbToken by IosSettings.listenBrainzToken.flow.collectAsState()
    val lastfmEnabled by IosSettings.lastfmEnabled.flow.collectAsState()
    val lastfmSession by IosSettings.lastfmSessionKey.flow.collectAsState()
    val lastfmUser by IosSettings.lastfmUsername.flow.collectAsState()
    var editingToken by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Text(
                tr("Account & integrations"),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item {
            val spotify by IosSettings.spotifySpdc.flow.collectAsState()
            Group("Spotify") {
                ActionRow("Spotify", value = if (spotify.isNotBlank()) tr("Connected") else tr("Tap to connect"), onClick = onOpenSpotify)
                Divider()
                ActionRow(tr("Import a playlist link"), onClick = { IosOverlays.spotifyImport = true })
                if (spotify.isNotBlank()) {
                    Divider()
                    ActionRow(tr("Disconnect Spotify"), destructive = true, onClick = IosSpotify::disconnect)
                }
            }
            Footer(tr("Sign in to load your playlists. Playback uses YouTube Music."))
        }
        item {
            val discordToken by IosSettings.discordToken.flow.collectAsState()
            val discordOn by IosSettings.discordRpcEnabled.flow.collectAsState()
            val discordUser by IosSettings.discordUsername.flow.collectAsState()
            Group(tr("Rich presence")) {
                ActionRow(
                    "Discord",
                    value = when {
                        discordToken.isBlank() -> tr("Tap to connect")
                        !discordOn -> tr("Connected, presence off")
                        discordUser.isNotBlank() -> tr("Sharing as {0}", discordUser)
                        else -> tr("Sharing your listens")
                    },
                    onClick = onOpenDiscord,
                )
            }
            Footer(tr("Show what you are playing on your Discord profile."))
        }
        item {
            Group(tr("Scrobbling")) {
                SwitchRow(
                    title = "ListenBrainz",
                    subtitle = if (lbEnabled && lbToken.isNotBlank()) tr("Connected") else tr("Enter a token to enable"),
                    checked = lbEnabled && lbToken.isNotBlank(),
                    onCheckedChange = { on ->
                        if (on && lbToken.isBlank()) editingToken = true else IosSettings.listenBrainzEnabled.set(on)
                    },
                    onClick = { editingToken = true },
                )
                if (lbEnabled && lbToken.isNotBlank()) {
                    Divider()
                    Toggle(tr("Only scrobble primary artist"), tr("Use the first artist when a track credits multiple artists"), IosSettings.listenBrainzPrimaryArtistOnly)
                }
                Divider()
                SwitchRow(
                    title = "Last.fm",
                    subtitle = when {
                        lastfmSession.isNotBlank() -> tr("Signed in as {0} · tap to sign out", lastfmUser)
                        else -> tr("Tap to sign in")
                    },
                    checked = lastfmEnabled && lastfmSession.isNotBlank(),
                    onCheckedChange = { on ->
                        if (on && lastfmSession.isBlank()) signingIn = true else IosSettings.lastfmEnabled.set(on)
                    },
                    onClick = {
                        if (lastfmSession.isNotBlank()) {
                            IosSettings.lastfmSessionKey.set("")
                            IosSettings.lastfmUsername.set("")
                            IosSettings.lastfmEnabled.set(false)
                            IosSettings.lastfmScrobbleEnabled.set(false)
                            IosSettings.lastfmNowPlaying.set(false)
                        } else {
                            signingIn = true
                        }
                    },
                )
                if (lastfmEnabled && lastfmSession.isNotBlank()) {
                    Divider()
                    Toggle(tr("Scrobble tracks"), tr("Log plays to your Last.fm timeline"), IosSettings.lastfmScrobbleEnabled)
                    Divider()
                    Toggle(tr("Only scrobble primary artist"), tr("Use the first artist when a track credits multiple artists"), IosSettings.lastfmPrimaryArtistOnly)
                    Divider()
                    Toggle(tr("Now playing"), tr("Update Last.fm with what you are listening to"), IosSettings.lastfmNowPlaying)
                }
            }
            Footer(tr("Scrobble your listens to Last.fm and ListenBrainz."))
        }
        if (lastfmEnabled && lastfmSession.isNotBlank()) {
            item {
                val minDuration by IosSettings.scrobbleMinDuration.flow.collectAsState()
                val delayPercent by IosSettings.scrobbleDelayPercent.flow.collectAsState()
                val delaySeconds by IosSettings.scrobbleDelaySeconds.flow.collectAsState()
                Group(tr("Scrobble timing")) {
                    SliderSetting(tr("Minimum song duration"), tr("Shorter songs will not scrobble"), "${minDuration}s", minDuration.toFloat(), 15f..120f, 20) {
                        IosSettings.scrobbleMinDuration.set(it.roundToInt())
                    }
                    Divider()
                    SliderSetting(tr("Scrobble delay"), tr("How far into a song to scrobble it"), "${(delayPercent * 100).roundToInt()}%", delayPercent, 0.1f..1f, 8) {
                        IosSettings.scrobbleDelayPercent.set(it)
                    }
                    Divider()
                    SliderSetting(tr("Maximum delay"), tr("Maximum scrobble delay in seconds"), "${delaySeconds}s", delaySeconds.toFloat(), 30f..300f, 26) {
                        IosSettings.scrobbleDelaySeconds.set(it.roundToInt())
                    }
                }
            }
        }
    }

    if (editingToken) {
        var token by remember { mutableStateOf(lbToken) }
        AlertDialog(
            onDismissRequest = { editingToken = false },
            title = { Text(tr("ListenBrainz token")) },
            text = {
                Column {
                    Text(tr("Paste your ListenBrainz user token to enable scrobbling."), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, singleLine = true, label = { Text(tr("Token")) })
                    TextButton(onClick = { openUrl("https://listenbrainz.org/settings/") }) { Text(tr("Find your token")) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    IosSettings.listenBrainzToken.set(token.trim())
                    IosSettings.listenBrainzEnabled.set(token.isNotBlank())
                    editingToken = false
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { editingToken = false }) { Text(tr("Cancel")) } },
        )
    }

    if (signingIn) LastfmSignIn(onDone = { signingIn = false })
}

/** Username and password, as the phone signs in; a key and secret too when the build has none. */
@Composable
private fun LastfmSignIn(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val needsKey = !IosLastFm.hasBuiltInKey
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(IosSettings.lastfmApiKey.flow.value) }
    var secret by remember { mutableStateOf(IosSettings.lastfmSecret.flow.value) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // Return moves to the next field rather than doing nothing.
    val next = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = { if (!loading) onDone() },
        title = { Text(tr("Sign in to Last.fm")) },
        text = {
            // Scrolls, so the password stays reachable above the keyboard.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (needsKey) {
                    Text(
                        tr("This build has no Last.fm API key. Create one free at last.fm/api, then paste its key and shared secret here."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { openUrl("https://www.last.fm/api/account/create") }) { Text(tr("Create an API account")) }
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, singleLine = true, label = { Text(tr("API key")) }, keyboardOptions = next)
                    OutlinedTextField(value = secret, onValueChange = { secret = it }, singleLine = true, label = { Text(tr("Shared secret")) }, keyboardOptions = next)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(value = username, onValueChange = { username = it }, singleLine = true, label = { Text(tr("Username")) }, keyboardOptions = next)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(tr("Password")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && username.isNotBlank() && password.isNotEmpty() && (!needsKey || (apiKey.isNotBlank() && secret.isNotBlank())),
                onClick = {
                    loading = true
                    error = null
                    if (needsKey) {
                        IosSettings.lastfmApiKey.set(apiKey.trim())
                        IosSettings.lastfmSecret.set(secret.trim())
                    }
                    scope.launch {
                        IosLastFm.mobileSession(username.trim(), password)
                            .onSuccess { session ->
                                IosSettings.lastfmSessionKey.set(session.key)
                                IosSettings.lastfmUsername.set(session.name.ifBlank { username.trim() })
                                IosSettings.lastfmEnabled.set(true)
                                IosSettings.lastfmScrobbleEnabled.set(true)
                                IosArtistFacts.start()
                                onDone()
                            }
                            .onFailure { error = it.message ?: tr("Sign-in failed") }
                        loading = false
                    }
                },
            ) { Text(if (loading) tr("Signing in…") else tr("Sign in")) }
        },
        dismissButton = { TextButton(enabled = !loading, onClick = onDone) { Text(tr("Cancel")) } },
    )
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked, onCheckedChange)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    subtitle: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = sliderValue,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
internal fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp).padding(top = 8.dp),
    )
}

internal fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
}
