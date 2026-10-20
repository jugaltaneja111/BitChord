package com.music.bitchord.ios

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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

/** One on/off setting, kept in the app's defaults under [key]. */
internal class BoolPref(private val key: String, default: Boolean) {
    private val defaults = NSUserDefaults.standardUserDefaults

    /** Read by whatever the setting changes. Written only through [set], which keeps it. */
    val flow = MutableStateFlow(
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else default,
    )

    fun set(value: Boolean) {
        defaults.setBool(value, key)
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
}

private val GroupShape = RoundedCornerShape(14.dp)
private val GroupColor = Color(0xFF1C1C1E)

@Composable
internal fun SettingsPage(
    contentPadding: PaddingValues,
    topPadding: androidx.compose.ui.unit.Dp,
    onClearSearchHistory: () -> Unit,
) {
    val signedIn by IosAccount.signedIn.collectAsState()
    val account by IosAccount.account.collectAsState()
    val accounts by IosAccount.accounts.collectAsState()
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
            Group("Account") {
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
            }
        }
        item {
            Group("Playback") {
                Toggle("Autoplay", "Keep playing similar songs when the queue ends", IosSettings.autoplay)
            }
        }
        item {
            Group("Appearance") {
                Toggle("Full-screen cover art", "Runs the cover to the edges of the player instead of a square sleeve", IosSettings.fullBleedArtwork)
                Divider()
                Toggle("Legacy mesh gradient", "Brings back the drifting colour blobs behind the player from v1.5", IosSettings.legacyMeshGradient)
                Divider()
                Toggle("Reduce animation", "Freezes the main player's gradient instead of drifting", IosSettings.reduceAnimation)
                Divider()
                Toggle("Reduce dynamic blur", "Swaps frosted glass for solid fills across the app", IosSettings.reduceDynamicBlur)
            }
        }
        item {
            Group("Player") {
                Toggle("Hide song status", "Hides the “Playing from” or “Played by” text in the main player", IosSettings.hideSongStatus)
                Divider()
                Toggle("Show queue history", "Show up to 25 previously played songs above Now playing in the queue", IosSettings.showQueueHistory)
                Divider()
                Toggle("Show stats for nerds", "Codec, bitrate and sample rate on the player", IosSettings.showNerdStats)
            }
        }
        item {
            Group("Your data") {
                ActionRow("Clear search history") {
                    onClearSearchHistory()
                    IosPlayerHost.showMessage("Search history cleared")
                }
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

@Composable
private fun Group(header: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Text(
            header.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Column(Modifier.fillMaxWidth().clip(GroupShape).background(GroupColor)) { content() }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.10f))
}

@Composable
private fun Toggle(title: String, subtitle: String, pref: BoolPref) {
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
        Switch(
            checked = checked,
            onCheckedChange = pref::set,
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

@Composable
private fun ActionRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        title,
        color = if (destructive) Color(0xFFFF6B6B) else Color.White,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 15.dp),
    )
}
