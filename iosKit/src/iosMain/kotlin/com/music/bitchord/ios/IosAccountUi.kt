package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** The app-wide overlays, opened from wherever their button is. */
internal object IosOverlays {
    var login by mutableStateOf(false)
    var switcher by mutableStateOf(false)
    var spotifyLogin by mutableStateOf(false)
    var spotifyImport by mutableStateOf(false)
    var discordLogin by mutableStateOf(false)
}

private val PillShape = RoundedCornerShape(50)
private val PillColor = Color(0xFF1C1C1E).copy(alpha = 0.92f)

/**
 * The phone's top-right pill: History, then the listener's photo (the account switcher), or a
 * Sign in button before there is anyone to switch between.
 */
@Composable
internal fun TopActions(onOpenHistory: () -> Unit, modifier: Modifier = Modifier) {
    val signedIn by IosAccount.signedIn.collectAsState()
    val account by IosAccount.account.collectAsState()
    Row(
        modifier
            .clip(PillShape)
            .background(PillColor)
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), PillShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (signedIn) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onOpenHistory),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.History, contentDescription = "History", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable { if (signedIn) IosOverlays.switcher = true else IosOverlays.login = true },
            contentAlignment = Alignment.Center,
        ) {
            val avatar = account?.thumbnailUrl
            if (signedIn && avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = "Switch account",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = if (signedIn) "Switch account" else "Sign in",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * The phone's account switcher (app/…/components/AccountProfileSelector.kt): every channel of
 * every Google login kept, grouped by login, then Add account, Manage accounts and Settings.
 * Managing turns each row into its login's sign-out.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun AccountSwitcher(
    hazeState: HazeState,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accounts by IosAccount.accounts.collectAsState()
    val activeAccountId by IosAccount.activeAccountId.collectAsState()
    val reduceBlur by IosSettings.reduceDynamicBlur.flow.collectAsState()
    var managing by remember { mutableStateOf(false) }
    // Brand channels made since the last look turn up here.
    LaunchedEffect(Unit) { IosAccount.loadChannels() }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .clip(shape)
                .then(
                    if (reduceBlur) {
                        Modifier.background(Color(0xFF1C1C1E))
                    } else {
                        Modifier.optimizedHazeEffect(state = hazeState, style = HazeMaterials.thin(Color(0xFF0D0D0F)))
                    },
                )
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                item {
                    Text(
                        "Switch account",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        modifier = Modifier.padding(20.dp, 18.dp, 20.dp, 8.dp),
                    )
                }
                accounts.forEach { account ->
                    item(key = "header:${account.id}") {
                        Text(
                            account.email.ifBlank { account.name.ifBlank { "Account" } },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp),
                        )
                    }
                    account.profiles.forEach { profile ->
                        item(key = "profile:${account.id}:${profile.id}") {
                            val selected = account.id == activeAccountId && profile.id == account.activeProfile?.id
                            ProfileRow(
                                profile = profile,
                                selected = selected,
                                managing = managing,
                                onClick = {
                                    if (managing) {
                                        IosAccount.removeAccount(account.id)
                                        if (IosAccount.accounts.value.isEmpty()) onDismiss()
                                    } else {
                                        IosAccount.selectProfile(account.id, profile.id)
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    SelectorAction(Icons.Rounded.Add, "Add account") {
                        onDismiss()
                        IosOverlays.login = true
                    }
                }
                item {
                    SelectorAction(Icons.Rounded.ManageAccounts, if (managing) "Done" else "Manage accounts") {
                        managing = !managing
                    }
                }
                item {
                    SelectorAction(Icons.Rounded.Settings, "Settings") {
                        onDismiss()
                        onOpenSettings()
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: IosProfile, selected: Boolean, managing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile.avatar != null) {
            AsyncImage(
                model = profile.avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF2C2C2E)),
            )
        } else {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF5E35B1)),
                contentAlignment = Alignment.Center,
            ) {
                Text(profile.name.take(1).lowercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                profile.handle.ifBlank { if (profile.brand) "Brand account" else "Personal" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            managing -> Text("Sign out", color = Color(0xFFFF6B6B))
            selected -> Icon(Icons.Rounded.Check, contentDescription = "Selected account", tint = Color.White)
        }
    }
}

@Composable
private fun SelectorAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White)
    }
}
