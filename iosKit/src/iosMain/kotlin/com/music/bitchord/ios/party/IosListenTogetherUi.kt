package com.music.bitchord.ios.party

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.listentogether.PartyActivity
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyPreview
import com.music.bitchord.ios.ActionRow
import com.music.bitchord.ios.Divider
import com.music.bitchord.ios.Footer
import com.music.bitchord.ios.Group
import com.music.bitchord.ios.IosAccount
import com.music.bitchord.ios.IosPlayerHost
import com.music.bitchord.ios.SwitchRow
import com.music.bitchord.ios.shareLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIPasteboard

private val Danger = Color(0xFFFF6B6B)
private val SheetColor = Color(0xFF1C1C1E)
private val FieldFill = Color(0xFF2C2C2E)
private val Secondary = Color(0xFF8E8E93)
private val Online = Color(0xFF34C759)

/** Which sheet is over the page; one at a time, as on the phone. */
private sealed interface PartySheet {
    data object Create : PartySheet
    data object JoinCode : PartySheet
    /** [server] as [IosListenTogether.switchParty] reads it: null idle, blank built-in, or an address. */
    data class Confirm(val preview: PartyPreview, val server: String?) : PartySheet
    data object Invite : PartySheet
}

/**
 * The Listen Together page and its sheets, ported from the desktop's `DesktopListenTogetherDialog`
 * and, where the desktop is thinner, Android's `ListenTogetherScreen`, its sheets and its
 * `PartyServerEditor`.
 *
 * [inviteCode] and [inviteServer] are a tapped invite (parse it with
 * [IosListenTogether.parseInvite]); the page shows its confirmation and then calls
 * [onInviteHandled] so it is not offered twice. A link is a request to look, never a join: the
 * listener always sees who is inside, and agrees, first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListenTogetherPage(
    contentPadding: PaddingValues,
    topPadding: Dp,
    inviteCode: String?,
    inviteServer: String?,
    onInviteHandled: () -> Unit,
    onSignIn: () -> Unit,
) {
    val state by IosListenTogether.state.collectAsState()
    val customServer by IosListenTogether.customServerUrl.collectAsState()
    val serverStatus by IosListenTogether.serverStatus.collectAsState()
    val signedIn by IosAccount.signedIn.collectAsState()
    // Read so the page recomposes on an account or channel switch, which changes the avatar.
    IosAccount.activeAccountId.collectAsState()
    IosAccount.accounts.collectAsState()
    val scope = rememberCoroutineScope()

    var sheet by remember { mutableStateOf<PartySheet?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf(IosListenTogether.nickname()) }
    var maxMembers by remember { mutableIntStateOf(5) }
    var codeInput by remember { mutableStateOf("") }
    var editingServer by remember { mutableStateOf(false) }
    /** An invite on another server than this device's: the code, and where it lives. */
    var pendingSwitch by remember { mutableStateOf<Pair<String, String>?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val onNicknameChange: (String) -> Unit = {
        nickname = it.take(80)
        IosListenTogether.setNickname(nickname)
    }

    /** Both doors in — a tapped link and six characters typed — show the same confirmation. */
    val openPreview: (String, String?) -> Unit = { code, server ->
        busy = true
        failure = null
        scope.launch {
            IosListenTogether.previewParty(code, server)
                .onSuccess { sheet = PartySheet.Confirm(it, server) }
                .onFailure { failure = it.message }
            busy = false
        }
    }

    val shareInvite: () -> Unit = {
        state.code?.let { code ->
            shareLink("${IosListenTogether.inviteUrl(code)}\n\nJoin my BitChord party with code $code")
        }
    }

    val copyCode: () -> Unit = {
        state.code?.let { code ->
            UIPasteboard.generalPasteboard.string = code
            IosPlayerHost.showMessage("Code copied")
        }
    }

    // A membership outlives the socket; opening it when the page is looked at is what puts a
    // party the system suspended back on the air.
    LaunchedEffect(Unit) { IosListenTogether.ensureConnected() }
    DisposableEffect(Unit) {
        IosListenTogether.setScreenActive(true)
        onDispose { IosListenTogether.setScreenActive(false) }
    }

    // A signed-out listener keeps the invite while the sign-in page is open.
    LaunchedEffect(inviteCode, inviteServer, signedIn) {
        val code = inviteCode ?: return@LaunchedEffect
        if (!signedIn || busy) return@LaunchedEffect
        if (state.code.equals(code, ignoreCase = true)) {
            onInviteHandled()
            return@LaunchedEffect
        }
        val switch = IosListenTogether.serverSwitchFor(inviteServer)
        if (switch != null) {
            pendingSwitch = code to switch
            return@LaunchedEffect
        }
        openPreview(code, null)
        onInviteHandled()
    }

    pendingSwitch?.let { (code, server) ->
        val toBuiltIn = server.isBlank()
        AlertDialog(
            onDismissRequest = {
                pendingSwitch = null
                onInviteHandled()
            },
            title = { Text(if (toBuiltIn) "Join on the BitChord server?" else "Join on another server?") },
            text = {
                Text(
                    if (toBuiltIn) {
                        "Party $code is on BitChord's own server. Joining switches this device off your custom server."
                    } else {
                        "Party $code is on $server. Joining points this device at that server."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    // Only consent to talk to that server; the confirmation still comes next.
                    openPreview(code, server)
                    onInviteHandled()
                }) { Text("Continue", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    onInviteHandled()
                }) { Text("Cancel") }
            },
            containerColor = SheetColor,
        )
    }

    if (editingServer) {
        ServerEditor(inParty = state.inParty, onDismiss = { editingServer = false })
    }

    sheet?.let { open ->
        ModalBottomSheet(
            onDismissRequest = {
                sheet = null
                // Closed with the message unread is still read: left set, it would surface on the
                // page as a line about a sheet that is no longer there.
                failure = null
            },
            sheetState = sheetState,
            containerColor = SheetColor,
        ) {
            when (open) {
                PartySheet.Create -> CreateSheet(
                    avatarUrl = IosListenTogether.myAvatarUrl(),
                    nickname = nickname,
                    onNicknameChange = onNicknameChange,
                    maxMembers = maxMembers,
                    onMaxMembersChange = { maxMembers = it.coerceIn(2, 10) },
                    busy = busy,
                    error = failure,
                    onCreate = {
                        busy = true
                        failure = null
                        scope.launch {
                            failure = IosListenTogether.createParty(nickname, maxMembers).exceptionOrNull()?.message
                            busy = false
                            if (failure == null) {
                                runCatching { sheetState.hide() }
                                sheet = null
                                // A beat with nothing over the page, so the listener sees it has
                                // become the party before the invite rises over it.
                                delay(INVITE_SHEET_DELAY_MS)
                                if (sheet == null && IosListenTogether.state.value.inParty) sheet = PartySheet.Invite
                            }
                        }
                    },
                )

                PartySheet.JoinCode -> JoinCodeSheet(
                    code = codeInput,
                    onCodeChange = { typed ->
                        // A pasted link is as good as the code in it.
                        codeInput = IosListenTogether.parseInvite(typed)?.code
                            ?: typed.filter(Char::isLetterOrDigit).uppercase().take(IosListenTogether.CODE_LENGTH)
                        // Typing is the retry: "no such party" left up reads as a verdict on what
                        // is there now.
                        failure = null
                    },
                    busy = busy,
                    error = failure,
                    onSubmit = { openPreview(codeInput, null) },
                )

                is PartySheet.Confirm -> ConfirmSheet(
                    preview = open.preview,
                    avatarUrl = IosListenTogether.myAvatarUrl(),
                    nickname = nickname,
                    onNicknameChange = onNicknameChange,
                    busy = busy,
                    error = failure,
                    onCancel = {
                        sheet = null
                        failure = null
                    },
                    onJoin = {
                        busy = true
                        failure = null
                        scope.launch {
                            // A server named by the invite always wins; otherwise already being in
                            // a party means a switch, which keeps this device where it is if the
                            // new party turns it away.
                            val result = if (open.server != null || IosListenTogether.state.value.inParty) {
                                IosListenTogether.switchParty(open.server, open.preview.code, nickname)
                            } else {
                                IosListenTogether.joinParty(open.preview.code, nickname)
                            }
                            failure = result.exceptionOrNull()?.message
                            busy = false
                            if (failure == null) {
                                codeInput = ""
                                sheet = null
                            }
                        }
                    },
                )

                PartySheet.Invite -> InviteSheet(
                    code = state.code.orEmpty(),
                    link = IosListenTogether.inviteUrl(state.code.orEmpty()),
                    onShare = {
                        sheet = null
                        shareInvite()
                    },
                    onCopy = copyCode,
                )
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Text(
                "Listen together",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        if (!signedIn) {
            item {
                Group("Account") {
                    ActionRow("Sign in", value = "Not signed in", onClick = onSignIn)
                }
                Footer("A party is joined as your YouTube Music account, so friends see your name and picture.")
            }
        }

        if (!state.inParty) {
            item {
                val enabled = signedIn && IosListenTogether.hasServer && !busy
                Group("") {
                    PartyAction("Start a party", "Pick a nickname and how many can join", enabled) {
                        failure = null
                        sheet = PartySheet.Create
                    }
                    Divider()
                    PartyAction("Join with a code", "You'll see who is inside before joining", enabled) {
                        failure = null
                        sheet = PartySheet.JoinCode
                    }
                }
                Footer("Everyone in a party hears the same song at the same moment, on their own device.")
            }
        } else {
            item { PartyCodeGroup(state, onCopy = copyCode, onShare = { sheet = PartySheet.Invite }) }
            item { NowPlayingGroup(state) }
            item { MembersGroup(state) }
            if (state.activity.isNotEmpty()) item { ActivityGroup(state.activity) }
            item {
                Group("") {
                    ActionRow("Leave party", destructive = true) {
                        scope.launch { IosListenTogether.leaveParty() }
                    }
                }
                Footer("The party carries on without you.")
            }
        }

        // Only when nothing covers it: every failure a sheet can produce is shown inside it, and
        // printing the same line down here as well prints it where it cannot be read.
        if (sheet == null) {
            (failure ?: state.error)?.takeIf(String::isNotBlank)?.let { message ->
                item {
                    Text(
                        message,
                        color = Danger,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 32.dp).padding(top = 16.dp),
                    )
                }
            }
        }

        // Last, and empty by default: nobody setting up a party needs to think about an address,
        // so this is where somebody running their own server comes looking.
        item {
            Group("Party server") {
                ActionRow("Custom server", value = customServer.ifBlank { "Built-in" }) {
                    // Changing the address under a live membership would leave this device holding
                    // a token for a server it no longer talks to.
                    if (state.inParty) IosPlayerHost.showMessage("Leave the party to change servers")
                    else editingServer = true
                }
            }
            Footer(serverStatusLine(serverStatus))
        }
    }
}

@Composable
private fun PartyAction(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Secondary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PartyCodeGroup(state: IosListenTogether.State, onCopy: () -> Unit, onShare: () -> Unit) {
    Group("Party code") {
        Text(
            state.code.orEmpty(),
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            // Wide tracking: a string to be read out loud and typed by somebody else.
            letterSpacing = 8.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
        )
        Divider()
        ActionRow("Copy code", onClick = onCopy)
        Divider()
        ActionRow("Share invite", onClick = onShare)
    }
    Footer("Friends join with this code or the invite link.")
}

/**
 * What the party is playing, and where its playhead is right now — recomputed on a tick rather
 * than read out of the last frame, because that *is* the feature: two phones side by side should
 * show the same number between server updates.
 */
@Composable
private fun NowPlayingGroup(state: IosListenTogether.State) {
    val track = state.playback.track
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.playback.seq, state.playback.isPlaying, state.clockSynced) {
        while (true) {
            positionMs = IosListenTogether.partyPositionMs() ?: 0L
            delay(250)
        }
    }
    Group("Now playing") {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (track?.thumbnailUrl != null) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(FieldFill),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title?.takeIf(String::isNotBlank) ?: "Nothing playing yet",
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = if (track == null) {
                    if (state.you?.isHost == true) "Play a song and it will appear for everyone." else ""
                } else {
                    buildString {
                        if (track.artist.isNotBlank()) append(track.artist).append(" · ")
                        append(elapsed(positionMs))
                        track.durationMs?.let { append(" / ").append(elapsed(it)) }
                    }
                }
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                state.playback.startedByName?.takeIf { track != null && it.isNotBlank() }?.let {
                    Text("Started by $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(connectionLine(state), style = MaterialTheme.typography.bodySmall, color = if (state.connected) MaterialTheme.colorScheme.onSurfaceVariant else Danger)
            }
        }
        val upcoming = state.queue.items
            .drop(com.music.bitchord.data.listentogether.partyQueueIndexOf(state.queue, state.playback, track?.videoId) + 1)
            .take(5)
        if (track != null && upcoming.isNotEmpty()) {
            Divider()
            Text(
                "UP NEXT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            )
            upcoming.forEachIndexed { index, next ->
                Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = Secondary, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(next.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(next.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MembersGroup(state: IosListenTogether.State) {
    val isHost = state.you?.isHost == true
    Group("Listening · ${state.members.size} of ${state.maxMembers}") {
        if (isHost) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Party size", color = Color.White)
                    Text("Never removes anyone already inside", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Stepper("−", state.maxMembers > maxOf(2, state.members.size)) {
                    IosListenTogether.setMaxMembers(state.maxMembers - 1)
                }
                Text("${state.maxMembers}", color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
                Stepper("+", state.maxMembers < 10) { IosListenTogether.setMaxMembers(state.maxMembers + 1) }
            }
            Divider()
            SwitchRow(
                title = "Only I control playback",
                subtitle = "Guests can still pause their own device",
                checked = state.hostOnlyControl,
                onCheckedChange = IosListenTogether::setHostOnlyControl,
                onClick = { IosListenTogether.setHostOnlyControl(!state.hostOnlyControl) },
            )
            Divider()
        }
        if (!state.controlsLocked) {
            SwitchRow(
                title = "AutoPlay",
                subtitle = "Keep the party going with similar songs",
                checked = state.playback.autoplayEnabled,
                onCheckedChange = IosListenTogether::setAutoplay,
                onClick = { IosListenTogether.setAutoplay(!state.playback.autoplayEnabled) },
            )
            Divider()
        }
        state.members.forEachIndexed { index, member ->
            if (index > 0) Divider()
            MemberRow(
                member = member,
                isYou = member.memberId == state.you?.memberId,
                canKick = isHost && !member.isHost,
                onKick = { IosListenTogether.kick(member.memberId) },
            )
        }
    }
    Footer(
        if (state.controlsLocked) "The host is controlling playback. Pausing only pauses this device."
        else "Anyone in the party can play, pause and skip.",
    )
}

@Composable
private fun MemberRow(member: PartyMember, isYou: Boolean, canKick: Boolean, onKick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PartyAvatar(member.avatarUrl, 34.dp, member.displayName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                member.displayName.ifBlank { "Someone" } + if (isYou) " · You" else "",
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "Away" rather than "offline": the slot is still theirs through the server's grace
            // period, so a tunnel or a locked screen does not read to everyone else as leaving.
            Text(
                when {
                    member.isHost && !member.connected -> "Host · Away"
                    member.isHost -> "Host"
                    !member.connected -> "Away"
                    else -> "Listening"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canKick) TextButton(onClick = onKick) { Text("Remove", color = Danger) }
    }
}

@Composable
private fun ActivityGroup(entries: List<PartyActivity>) {
    val formatter = remember { NSDateFormatter().apply { dateFormat = "HH:mm" } }
    Group("Activity") {
        Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
            entries.take(12).forEach { entry ->
                val time = formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(entry.atMs / 1000.0))
                Text(
                    "$time  ${entry.by}: ${entry.detail.ifBlank { entry.action }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
    Footer("Only what happened while you were here.")
}

// ------------------------------------------------------------------- sheets --

@Composable
private fun SheetBody(content: @Composable () -> Unit) {
    // Scrolls, so the field and the button under it stay reachable above the keyboard.
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun SheetHeader(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
    subtitle?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun SheetError(message: String?) {
    if (message.isNullOrBlank()) return
    Spacer(Modifier.height(12.dp))
    Text(message, color = Danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}

@Composable
private fun NicknameField(nickname: String, onNicknameChange: (String) -> Unit, avatarUrl: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PartyAvatar(avatarUrl, 44.dp, nickname)
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = onNicknameChange,
            label = { Text("Nickname") },
            placeholder = { Text("Your profile name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CreateSheet(
    avatarUrl: String?,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    maxMembers: Int,
    onMaxMembersChange: (Int) -> Unit,
    busy: Boolean,
    error: String?,
    onCreate: () -> Unit,
) {
    SheetBody {
        SheetHeader("Start a party", "Choose how friends see you and how many can join.")
        NicknameField(nickname, onNicknameChange, avatarUrl)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Party size", color = Color.White)
                Text("2–10 people", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Stepper("−", !busy && maxMembers > 2) { onMaxMembersChange(maxMembers - 1) }
            Text("$maxMembers", color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
            Stepper("+", !busy && maxMembers < 10) { onMaxMembersChange(maxMembers + 1) }
        }
        SheetError(error)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Start party", busy, enabled = true, onClick = onCreate)
    }
}

@Composable
private fun JoinCodeSheet(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onSubmit: () -> Unit,
) {
    SheetBody {
        SheetHeader("Join a party", "Enter the six-character code, or paste an invite link.")
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            placeholder = { Text("ABC123") },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp, textAlign = TextAlign.Center, color = Color.White),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        SheetError(error)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Look up party", busy, enabled = code.length == IosListenTogether.CODE_LENGTH, onClick = onSubmit)
    }
}

@Composable
private fun ConfirmSheet(
    preview: PartyPreview,
    avatarUrl: String?,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onJoin: () -> Unit,
) {
    SheetBody {
        Icon(Icons.Rounded.Groups, null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(10.dp))
        SheetHeader(
            if (preview.hostName.isBlank()) "Join this party?" else "Join ${preview.hostName}'s party?",
            if (preview.memberCount > 0) "${preview.memberCount} of ${preview.maxMembers} people · ${preview.code}" else preview.code,
        )
        if (preview.members.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                preview.members.take(5).forEach { PartyAvatar(it.avatarUrl, 46.dp, it.displayName) }
            }
            Spacer(Modifier.height(20.dp))
        }
        NicknameField(nickname, onNicknameChange, avatarUrl)
        if (preview.isFull) SheetError("This party is full.")
        SheetError(error)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Join party", busy, enabled = !preview.isFull, onClick = onJoin)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = Color.White) }
    }
}

@Composable
private fun InviteSheet(code: String, link: String, onShare: () -> Unit, onCopy: () -> Unit) {
    SheetBody {
        SheetHeader("Invite friends", "Send the link, or read out the code.")
        Text(code, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
        Spacer(Modifier.height(8.dp))
        Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Share invite link", busy = false, enabled = true, onClick = onShare)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onCopy) { Text("Copy code", color = Color.White) }
    }
}

/**
 * The listener's own party server, as the phone's `PartyServerEditor` edits it: a draft, a Test
 * that reports on exactly that draft, Save, and Remove back to the built-in server.
 */
@Composable
private fun ServerEditor(inParty: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stored = IosListenTogether.customServerUrl.value
    // Seeded once, when the dialog opens: re-seeding from the store while it is typed into would
    // overwrite the typing.
    var input by remember { mutableStateOf(stored) }
    // What the last Test said, and whether it was good news.
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val save: (String) -> Unit = { value ->
        busy = true
        status = null
        scope.launch {
            IosListenTogether.setCustomServerUrl(value)
                .onSuccess { resolved ->
                    IosPlayerHost.showMessage(
                        when {
                            IosListenTogether.customServerUrl.value.isBlank() -> "Using the built-in server"
                            resolved.health != IosListenTogether.Health.ONLINE -> "Saved, but no party server is answering"
                            resolved.isFallback -> "Saved. It isn't answering, so the built-in server is in use"
                            else -> "Connected to your server"
                        },
                    )
                    onDismiss()
                }
                .onFailure { status = it.message.orEmpty() to false }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Custom server") },
        text = {
            // Scrolls, so the field stays reachable above the keyboard.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Run your own party server and point BitChord at it. Leave it empty to use the one built in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        // A result describes the address it was run against; edited, it is stale.
                        status = null
                    },
                    // Never the built-in address, even as a placeholder.
                    placeholder = { Text("https://party.example.com") },
                    singleLine = true,
                    enabled = !inParty,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let { (text, good) ->
                    Spacer(Modifier.height(8.dp))
                    Text(text, color = if (good) Online else Danger, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (stored.isNotBlank() && !inParty) {
                        TextButton(enabled = !busy, onClick = { save("") }) { Text("Remove", color = Danger) }
                    }
                    TextButton(
                        enabled = !busy && input.isNotBlank(),
                        onClick = {
                            IosListenTogether.normalizeServerUrl(input)
                                .onFailure { status = it.message.orEmpty() to false }
                                .onSuccess { normalized ->
                                    busy = true
                                    status = null
                                    scope.launch {
                                        val probe = IosListenTogether.probeHealthWithLatency(normalized)
                                        status = if (probe.isOnline) "Online · ${probe.latencyMs} ms" to true
                                        else "Not answering" to false
                                        busy = false
                                    }
                                }
                        },
                    ) { Text(if (busy) "Testing…" else "Test") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !inParty && input.isNotBlank(), onClick = { save(input) }) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
        containerColor = SheetColor,
    )
}

// ------------------------------------------------------------------- pieces --

@Composable
private fun PartyAvatar(url: String?, size: Dp, fallback: String = "") {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(FieldFill),
        )
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(FieldFill), contentAlignment = Alignment.Center) {
            if (fallback.isNotBlank()) {
                Text(fallback.trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Rounded.Person, null, tint = Secondary, modifier = Modifier.size(size * 0.55f))
            }
        }
    }
}

@Composable
private fun Stepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(FieldFill).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.White else Secondary) }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = FieldFill,
            disabledContentColor = Secondary,
        ),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
        else Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** One line saying whether this device is actually keeping up, and how well. */
private fun connectionLine(state: IosListenTogether.State): String = when {
    state.connection != IosListenTogether.Connection.LIVE -> "Reconnecting…"
    // Live, but the clock has not been measured: the position above is the server's last word on
    // it rather than this device's own reckoning, and saying so beats a spinner.
    !state.clockSynced -> "Syncing clocks…"
    else -> "In sync · ${state.roundTripMs} ms"
}

/** Whether the server is answering — never *where* it is; the built-in address is not shown. */
private fun serverStatusLine(status: IosListenTogether.ServerStatus): String = when {
    status.health == IosListenTogether.Health.CHECKING -> "Checking the party server…"
    status.health == IosListenTogether.Health.OFFLINE -> "The party server isn't answering. It may be waking up; try again in a minute."
    status.isFallback -> "Your server isn't answering, so the built-in one is in use · ${status.latencyMs} ms"
    else -> "Party server online · ${status.latencyMs} ms"
}

private fun elapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** The beat between the create sheet going down and the invite rising, so the page change shows. */
private const val INVITE_SHEET_DELAY_MS = 450L
