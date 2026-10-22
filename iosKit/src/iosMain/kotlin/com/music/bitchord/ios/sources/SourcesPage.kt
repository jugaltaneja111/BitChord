package com.music.bitchord.ios.sources

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ios.Divider
import com.music.bitchord.ios.GroupColor
import com.music.bitchord.ios.GroupShape
import com.music.bitchord.ios.IosSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

private val Red = Color(0xFFFF6B6B)
private val Green = Color(0xFF34C759)
private val Accent = Color(0xFF0A84FF)

/**
 * Where audio comes from, and in what order: the phone's Sources screen as an
 * iOS settings page.
 *
 * One numbered list, because the numbers say what order the sources are tried
 * in and that is the one thing this page is for. The addons come first and are
 * the only rows whose order is the listener's — moved with the arrows, as the
 * Lyrics sources page moves its rows, where the phone drags them. JioSaavn and
 * YouTube rank by kind, which is fixed in [SourceKind]; arrows that let JioSaavn
 * be moved above an addon would be offering a choice the resolver does not
 * honour, so they get none.
 *
 * What the phone's page has that this one doesn't: the WebDAV and SMB library
 * sections (not sources in this sense, and not on iOS yet), the "Source
 * matching" length slider (it tunes the phone's mid-playback upgrade, which iOS
 * doesn't run), the Bluetooth codec permission row (iOS Bluetooth is always AAC,
 * so there is no codec to read), and the "not used on mobile data" line (this
 * page isn't told the connection's ceiling; the walk applies it per stream from
 * the request the player passes).
 */
@Composable
internal fun SourcesPage(contentPadding: PaddingValues, topPadding: Dp) {
    val configs by IosSources.configs.collectAsState()
    val atmos by SourcePrefs.dolbyAtmos.collectAsState()

    /** Last known reachability per source, filled in as the probes come back. */
    val health = remember { mutableStateMapOf<String, SourceHealth>() }

    var editing by remember { mutableStateOf<SourceConfig?>(null) }
    var confirmJioSaavn by remember { mutableStateOf(false) }

    // Bumped whenever the audio route changes, so a row waiting on a lossless
    // output redraws — and is probed — the moment a cable goes in.
    var route by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> route++ }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    val capable = remember(route) { LosslessOutput.capable }
    val routeName = remember(route) { LosslessOutput.routeName }

    // Every source that has a server to reach is probed, so an addon gets a
    // reachability line — the only feedback that a URL just pasted in was any
    // good, and the only place a manifest that fails validation gets to say
    // why. Except an addon waiting on a lossless output: it is not to be
    // contacted at all until one is connected, and connecting one re-runs this.
    val probeKey = configs.filter { it.kind.needsServer }
        .joinToString { "${it.id}@${it.baseUrl}@${it.checkValidLossless}" } + "@$capable"
    LaunchedEffect(probeKey) {
        configs.filter { it.kind.needsServer && it.isComplete && !it.awaitsLosslessOutput }.forEach { config ->
            val source = SourceRegistry.instance(config.id) ?: return@forEach
            health[config.id] = try {
                source.health()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceHealth.Unreachable(e.message ?: "Failed")
            }
            // An addon names itself, so the row takes that name rather than a
            // hostname. Written here, off the probe that has just fetched the
            // manifest anyway; it settles after one pass, since the next probe
            // finds the label already equal and writes nothing.
            if (source is AddonSource) {
                val named = runCatching { source.manifestName() }.getOrNull()
                val current = SourceRegistry.config(config.id)
                if (named != null && current != null && named != current.label) {
                    SourceRegistry.update(current.copy(label = named))
                }
            }
        }
    }

    val addons = configs.filter { it.kind.isUserAdded }
    val fixed = configs.filterNot { it.kind.isUserAdded }.sortedBy { it.kind.rank }
    val anyLosslessSource = configs.any { it.enabled && it.isComplete && it.kind.canServeLossless }

    LazyColumn(
        contentPadding = PaddingValues(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Sources",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item {
            Header("Sources, tried in this order")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(GroupShape).background(GroupColor)) {
                addons.forEachIndexed { index, config ->
                    if (index > 0) Divider()
                    SourceRow(
                        position = index + 1,
                        config = config,
                        health = health[config.id],
                        awaitingOutput = config.enabled && config.checkValidLossless && !capable,
                        routeName = routeName,
                        onClick = { editing = config },
                        onToggle = { SourceRegistry.setEnabled(config.id, it) },
                        // No arrows for a single addon: there is nothing to
                        // reorder, and a control that can't move anything lies.
                        onMoveUp = if (addons.size > 1 && index > 0) {
                            { SourceRegistry.reorderAddons(addons.map { it.id }.moved(index, index - 1)) }
                        } else {
                            null
                        },
                        onMoveDown = if (addons.size > 1 && index < addons.lastIndex) {
                            { SourceRegistry.reorderAddons(addons.map { it.id }.moved(index, index + 1)) }
                        } else {
                            null
                        },
                        reorderable = addons.size > 1,
                    )
                }
                fixed.forEachIndexed { index, config ->
                    if (addons.isNotEmpty() || index > 0) Divider()
                    SourceRow(
                        position = addons.size + index + 1,
                        config = config,
                        health = health[config.id],
                        awaitingOutput = false,
                        routeName = routeName,
                        onClick = null,
                        // YouTube gets no switch at all — see
                        // [SourceRegistry.setEnabled] for why one would be a lie.
                        onToggle = if (config.kind == SourceKind.YOUTUBE) {
                            null
                        } else {
                            { enabled ->
                                if (config.kind == SourceKind.JIOSAAVN && enabled && !config.enabled) {
                                    confirmJioSaavn = true
                                } else {
                                    SourceRegistry.setEnabled(config.id, enabled)
                                }
                            }
                        },
                        onMoveUp = null,
                        onMoveDown = null,
                        reorderable = false,
                    )
                }
                Divider()
                AddRow { editing = SourceConfig(kind = SourceKind.ADDON) }
            }
            Footer(
                buildString {
                    append(
                        "If a source does not have a track or cannot be reached, BitChord tries the next one. " +
                            "Sources above YouTube can replace its recording when they return a better version.",
                    )
                    if (!anyLosslessSource) {
                        append("\n\nNo enabled source can provide lossless audio. Add an addon to play lossless files without transcoding.")
                    }
                },
            )
        }
        item {
            Header("Immersive audio")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(GroupShape).background(GroupColor)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable { SourcePrefs.setDolbyAtmos(!atmos) }
                        .padding(16.dp, 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dolby Atmos", color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Prefer an addon's Atmos mix when it has one",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IosSwitch(atmos, SourcePrefs::setDolbyAtmos)
                }
            }
            Footer(
                "Off, addons are asked for the stereo mix and an Atmos-only answer is passed over for the next " +
                    "source. Atmos is a different mix rather than a better copy, and outside AirPods it plays as " +
                    "the surround mix folded down to stereo.",
            )
        }
    }

    editing?.let { config ->
        SourceEditor(
            config = config,
            onDismiss = { editing = null },
            onSaved = { editing = null },
            onDelete = {
                SourceRegistry.remove(config.id)
                health.remove(config.id)
                editing = null
            },
        )
    }

    if (confirmJioSaavn) {
        AlertDialog(
            onDismissRequest = { confirmJioSaavn = false },
            title = { Text("Enable JioSaavn?") },
            text = { Text("May match the wrong version of a song. Enable at your own risk.") },
            confirmButton = {
                TextButton(onClick = {
                    configs.firstOrNull { it.kind == SourceKind.JIOSAAVN }
                        ?.let { SourceRegistry.setEnabled(it.id, true) }
                    confirmJioSaavn = false
                }) { Text("Enable anyway") }
            },
            dismissButton = { TextButton(onClick = { confirmJioSaavn = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
private fun SourceRow(
    position: Int,
    config: SourceConfig,
    health: SourceHealth?,
    /** On, but its addon needs a lossless output and the audio is going somewhere else. */
    awaitingOutput: Boolean,
    routeName: String?,
    onClick: (() -> Unit)?,
    /** Null for a source that cannot be switched off, which gets a label instead. */
    onToggle: ((Boolean) -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    /** Whether this row's position is the listener's to set, so it gets arrows at all. */
    reorderable: Boolean,
) {
    // Dimmed for the same reason an off source is: it is not in the walk. The
    // switch stays where the user left it, so the row reads "on, but not now"
    // rather than "off".
    val dimmed = !config.enabled || awaitingOutput
    val fade = if (dimmed) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = if (reorderable) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$position",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp).alpha(fade),
        )
        Icon(
            kindIcon(config.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp).alpha(fade),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(fade)) {
            Text(config.displayName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                // Outranks everything: this one is not reached at all until
                // the output changes.
                awaitingOutput -> "Needs a lossless output · " +
                    (routeName?.let { "playing to $it; " } ?: "") + "connect a wired or USB output"
                else -> config.statusLine(health)
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                // Only a rejection is coloured, and only when it is what the
                // line actually says. A server that is merely down will be up
                // again without anyone doing anything, and painting that red
                // trains people to ignore the colour by the time it means
                // something.
                color = when {
                    awaitingOutput -> Red
                    health is SourceHealth.Rejected && config.isComplete -> Red
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (onToggle == null) {
            Text("Always on", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            IosSwitch(config.enabled, onToggle)
        }
        // Outside the dimming, and last in the row: a source switched off still
        // has a position worth setting for when it is switched back on.
        if (reorderable) {
            Column(verticalArrangement = Arrangement.Center) {
                Arrow(Icons.Rounded.KeyboardArrowUp, "Move up", onMoveUp)
                Arrow(Icons.Rounded.KeyboardArrowDown, "Move down", onMoveDown)
            }
        }
    }
}

@Composable
private fun Arrow(icon: ImageVector, label: String, onClick: (() -> Unit)?) {
    IconButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = label, tint = if (onClick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
    }
}

/**
 * The one row on this page that adds something rather than describing
 * something. At the bottom of the same group as the sources, because what it
 * adds goes into that list — first, above JioSaavn and YouTube.
 */
@Composable
private fun AddRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(20.dp))
        Icon(Icons.Rounded.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Add an addon", color = Accent)
            Text(
                "Point BitChord at an addon server you host or were given a link to",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

private fun kindIcon(kind: SourceKind): ImageVector = when (kind) {
    SourceKind.ADDON -> Icons.Rounded.Extension
    SourceKind.JIOSAAVN -> Icons.Rounded.GraphicEq
    SourceKind.YOUTUBE -> Icons.Rounded.PlayCircle
}

/** The second line of a row: what this source is, or what is wrong with it. */
private fun SourceConfig.statusLine(health: SourceHealth?): String = when {
    !isComplete -> "Tap to finish setting up"
    kind == SourceKind.JIOSAAVN -> "May match the wrong version of a song. Enable at your own risk."
    health is SourceHealth.Ok -> listOfNotNull(
        health.detail,
        kind.labels.take(3).joinToString(" · "),
        // The addon's own `allowDownloads: 0`, said where the user will look
        // when a download from it came from somewhere else.
        "Streaming only".takeIf { !allowDownloads },
        // And its `checkValidLossless`, said while it is satisfied too, so the
        // shut-out that follows unplugging a DAC is not a surprise.
        "Lossless output only".takeIf { checkValidLossless },
    ).joinToString(" · ")
    health is SourceHealth.Rejected -> health.reason
    health is SourceHealth.Unreachable -> "Can’t reach it right now · ${health.reason}"
    kind.needsServer -> "Checking…"
    else -> kind.labels.take(3).joinToString(" · ")
}

/**
 * Add or edit an addon.
 *
 * Test is offered rather than required: a server that happens to be asleep is
 * still worth saving, and refusing to store it until it answers would make
 * setting one up from a coffee shop impossible. What Test does buy is the
 * difference between "not answering" and "answering, but not with something
 * this app can use" — worth hearing about before the first track rather than
 * after it.
 */
@Composable
private fun SourceEditor(
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDelete: () -> Unit,
) {
    val isNew = SourceRegistry.config(config.id) == null
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }

    /**
     * Identify what is at the URL, then say so. Both buttons run this — the
     * only difference is whether a success is then stored. Testing and saving
     * asking the *same* question is the point: a Save that skipped
     * identification could store a config whose kind was guessed.
     */
    fun run(thenSave: Boolean) {
        busy = true
        status = null
        scope.launch {
            val identified = try {
                SourceRegistry.identify(baseUrl.trim(), config.takeUnless { isNew })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val found = identified.getOrNull()
            if (found == null) {
                statusIsGood = false
                status = identified.exceptionOrNull()?.message ?: "BitChord could not tell what that link is"
                busy = false
                return@launch
            }

            // Already here? Checked against the *identified* base rather than
            // the typed text, so an addon's root and its manifest.json are
            // recognised as the one source they are.
            SourceRegistry.duplicateOf(found.baseUrl, exceptId = config.id.takeUnless { isNew })?.let { existing ->
                statusIsGood = false
                status = "Already added as ${existing.displayName}"
                busy = false
                return@launch
            }

            // Identified, and now asked whether it actually works. The two are
            // different questions: a manifest can be perfectly well formed and
            // its server still be refusing every search.
            val health = try {
                SourceRegistry.probeCandidate(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceHealth.Unreachable(e.message ?: "Failed")
            }
            statusIsGood = health.isOk
            status = when (health) {
                is SourceHealth.Ok -> listOfNotNull("Connected", found.kind.label, health.detail).joinToString(" · ")
                is SourceHealth.Rejected -> health.reason
                is SourceHealth.Unreachable -> health.reason
            }
            busy = false

            // Saved even when the probe came back unhappy, but only once the
            // format is known: a server that is asleep is still worth storing,
            // while a URL nothing can be made of is not.
            if (thenSave) {
                if (isNew) SourceRegistry.add(found) else SourceRegistry.update(found)
                onSaved()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add an addon" else config.displayName) },
        text = {
            Column {
                Text(
                    "Paste the addon’s URL — either its root or its manifest.json. BitChord reads what it can " +
                        "search and stream, then asks it for tracks over plain HTTP.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = baseUrl,
                    // A result describes the address it was run against, so the
                    // moment that address is edited it stops being true.
                    onValueChange = { baseUrl = it; status = null },
                    placeholder = { Text("https://my-addon.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Testing…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusIsGood) Green else Red,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { run(thenSave = false) }, enabled = !busy && baseUrl.isNotBlank()) { Text("Test") }
                TextButton(onClick = { run(thenSave = true) }, enabled = !busy && baseUrl.isNotBlank()) { Text("Save") }
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete, enabled = !busy) { Text("Remove", color = Red) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
