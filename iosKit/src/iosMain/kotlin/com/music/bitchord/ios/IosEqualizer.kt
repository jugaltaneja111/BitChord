package com.music.bitchord.ios

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone's equaliser screen: Dynamic, a tone pad that tilts the spectrum and shapes its middle;
 * Manual, seven bands under a preset list; and balance under both. Both tabs drive the same
 * filters (see [EqLayout]), so switching glides rather than clicks.
 */
@Composable
internal fun EqualizerPage(contentPadding: PaddingValues, topPadding: Dp) {
    val enabled by IosSettings.equalizerEnabled.flow.collectAsState()
    val mode by IosSettings.equalizerMode.flow.collectAsState()
    val toneX by IosSettings.equalizerToneX.flow.collectAsState()
    val toneY by IosSettings.equalizerToneY.flow.collectAsState()
    val focused by IosSettings.equalizerFocused.flow.collectAsState()
    val balance by IosSettings.equalizerBalance.flow.collectAsState()
    val bands by IosSettings.equalizerBands.flow.collectAsState()
    val preset = EqualizerPreset.matching(bands)
    val haptics = rememberHaptics()
    var pickingPreset by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = topPadding, bottom = contentPadding.calculateBottomPadding() + 32.dp),
    ) {
        Text(
            "Equalizer",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Group("") {
            Toggle("Equalizer", "Shapes the sound inside BitChord, before it reaches the system.", IosSettings.equalizerEnabled)
        }

        // Off, everything below reads as unavailable rather than merely idle.
        val settingsAlpha by animateFloatAsState(if (enabled) 1f else 0.38f, tween(220), label = "equalizerEnabled")
        Column(Modifier.graphicsLayer { alpha = settingsAlpha }) {
            Spacer(Modifier.height(18.dp))
            ModeSwitch(
                selected = mode,
                enabled = enabled,
                onSelect = { IosSettings.equalizerMode.set(it) },
            )
            if (mode == IosSound.MODE_MANUAL) {
                Group("") {
                    ActionRow("Preset", value = preset.label()) { if (enabled) pickingPreset = true }
                }
                Group("Bands") {
                    BandSliders(bands = bands, enabled = enabled, onChange = IosSound::setEqualizerBands)
                    Divider()
                    ActionRow("Reset all bands", destructive = true) {
                        if (enabled) {
                            haptics.play(Haptic.Select)
                            IosSound.setEqualizerPreset(EqualizerPreset.FLAT)
                        }
                    }
                }
                Footer("Moving a band leaves the preset behind and the curve becomes your own.")
            } else {
                Group("Tone") {
                    TonePad(x = toneX, y = toneY, enabled = enabled, onChange = IosSound::setEqualizerTone)
                    ToneReadout(x = toneX, y = toneY)
                    Divider()
                    ChoiceRow("Broad", selected = !focused, enabled = enabled) { IosSettings.equalizerFocused.set(false) }
                    Divider()
                    ChoiceRow("Focused", selected = focused, enabled = enabled) { IosSettings.equalizerFocused.set(true) }
                }
                Footer(
                    "Drag left for a warmer sound and right for a brighter one; down scoops the middle, up brings it forward. " +
                        if (focused) {
                            "Focused works on narrower stretches, so it changes less around what it moves."
                        } else {
                            "Broad moves wide stretches of the spectrum at once."
                        },
                )
            }
            Group("Balance") {
                BalanceControl(balance = balance, enabled = enabled, onChange = { IosSettings.equalizerBalance.set(it.coerceIn(-1f, 1f)) })
            }
            Footer("Shifts the sound towards one ear by quietening the other.")
        }
    }

    if (pickingPreset) {
        PresetSheet(
            selected = preset,
            onSelect = {
                IosSound.setEqualizerPreset(it)
                pickingPreset = false
            },
            onDismiss = { pickingPreset = false },
        )
    }
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp).padding(top = 8.dp),
    )
}

/** Dynamic | Manual, as the phone's segmented control draws them. */
@Composable
private fun ModeSwitch(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GroupColor)
            .padding(3.dp),
    ) {
        listOf("Dynamic", "Manual").forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Color(0xFF636366) else Color.Transparent)
                    .clickable(enabled = enabled && !active) { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

// ---- Tone pad --------------------------------------------------------------

/** The dot grid and its puck, snapping to whole steps with a tick at each. */
@Composable
private fun TonePad(x: Int, y: Int, enabled: Boolean, onChange: (Int, Int) -> Unit) {
    val haptics = rememberHaptics()
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val puckColor = Color.White
    val shadowColor = Color.Black.copy(alpha = 0.16f)
    val steps = EqLayout.TONE_STEPS
    // The gesture block outlives the composition that made it: read the latest values through these.
    val latestX by rememberUpdatedState(x)
    val latestY by rememberUpdatedState(y)
    val currentOnChange by rememberUpdatedState(onChange)
    val animatedX by animateFloatAsState(x.toFloat(), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh), label = "tonePadX")
    val animatedY by animateFloatAsState(y.toFloat(), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh), label = "tonePadY")

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .height(PAD_HEIGHT)
            .pointerInput(steps, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Claimed on the way down, or the scrolling page would take every vertical drag.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var lastX = latestX
                    var lastY = latestY
                    fun report(offset: Offset) {
                        val (newX, newY) = stepAt(offset, size.toSize(), PUCK_RADIUS.toPx(), steps)
                        if (newX != lastX || newY != lastY) {
                            lastX = newX
                            lastY = newY
                            haptics.play(Haptic.Tick)
                            currentOnChange(newX, newY)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = PUCK_RADIUS.toPx()
        val usableWidth = size.width - inset * 2
        val usableHeight = size.height - inset * 2
        val columns = steps * 2
        val dotRadius = DOT_RADIUS.toPx()
        for (column in 0..columns) {
            for (row in 0..columns) {
                val onAxis = column == steps || row == steps
                drawCircle(
                    color = dotColor.copy(alpha = if (onAxis) 0.55f else 0.3f),
                    radius = dotRadius,
                    center = Offset(inset + usableWidth * column / columns, inset + usableHeight * row / columns),
                )
            }
        }
        val puckCentre = Offset(
            inset + usableWidth * (animatedX + steps) / columns,
            inset + usableHeight * (steps - animatedY) / columns,
        )
        for (ring in 3 downTo 1) {
            drawCircle(
                color = shadowColor.copy(alpha = shadowColor.alpha / ring),
                radius = inset + ring * 2f,
                center = puckCentre.copy(y = puckCentre.y + ring),
            )
        }
        drawCircle(color = puckColor, radius = inset, center = puckCentre)
    }
}

private fun stepAt(offset: Offset, size: Size, inset: Float, steps: Int): Pair<Int, Int> {
    val usableWidth = (size.width - inset * 2).coerceAtLeast(1f)
    val usableHeight = (size.height - inset * 2).coerceAtLeast(1f)
    val fractionX = ((offset.x - inset) / usableWidth).coerceIn(0f, 1f)
    val fractionY = ((offset.y - inset) / usableHeight).coerceIn(0f, 1f)
    val x = (fractionX * steps * 2).roundToInt() - steps
    // Screen coordinates grow downwards and the control does not: up is more.
    val y = steps - (fractionY * steps * 2).roundToInt()
    return x to y
}

@Composable
private fun ToneReadout(x: Int, y: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Waves, contentDescription = "Tilt", tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(signed(x), style = MaterialTheme.typography.bodyLarge, color = Color.White)
        Spacer(Modifier.width(22.dp))
        Icon(Icons.AutoMirrored.Rounded.ShowChart, contentDescription = "Contour", tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(signed(y), style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                if (!selected) {
                    haptics.play(Haptic.Select)
                    onClick()
                }
            }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = AccentBlue, modifier = Modifier.size(22.dp))
    }
}

// ---- Manual bands ----------------------------------------------------------

@Composable
private fun BandSliders(bands: List<Float>, enabled: Boolean, onChange: (List<Float>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        EqLayout.MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val value = bands.getOrElse(index) { 0f }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatGain(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (abs(value) < 0.05f) MaterialTheme.colorScheme.onSurfaceVariant else AccentBlue,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                BandFader(
                    value = value,
                    enabled = enabled,
                    onChange = { updated -> onChange(bands.toMutableList().also { it[index] = updated }) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    formatFrequency(hz),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A drawn vertical fader whose fill runs from the centre, because the value is signed. */
@Composable
private fun BandFader(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val haptics = rememberHaptics()
    val trackColor = Color.White.copy(alpha = 0.18f)
    val range = EqLayout.MANUAL_RANGE_DB
    val latest by rememberUpdatedState(value)
    // The caller's lambda closes over the whole band list: a stale copy would write every other
    // band back to what it was when this fader first composed.
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .width(FADER_WIDTH)
            .height(FADER_HEIGHT)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = latest
                    fun report(offset: Offset) {
                        val inset = KNOB_RADIUS.toPx()
                        val usable = (size.height - inset * 2).coerceAtLeast(1f)
                        val fraction = ((offset.y - inset) / usable).coerceIn(0f, 1f)
                        val snapped = snapGain((1f - fraction * 2f) * range, range)
                        if (abs(snapped - last) > 0.001f) {
                            haptics.play(Haptic.Tick)
                            last = snapped
                            currentOnChange(snapped)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = KNOB_RADIUS.toPx()
        val usable = size.height - inset * 2
        val centreY = inset + usable / 2
        val trackWidth = TRACK_WIDTH.toPx()
        val centreX = size.width / 2
        val knobY = inset + usable * (1f - (value / range + 1f) / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(centreX - trackWidth / 2, inset),
            size = Size(trackWidth, usable),
            cornerRadius = CornerRadius(trackWidth / 2),
        )
        if (abs(value) > 0.05f) {
            drawRoundRect(
                color = AccentBlue,
                topLeft = Offset(centreX - trackWidth / 2, minOf(centreY, knobY)),
                size = Size(trackWidth, abs(centreY - knobY)),
                cornerRadius = CornerRadius(trackWidth / 2),
            )
        }
        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = Offset(centreX - trackWidth, centreY),
            end = Offset(centreX + trackWidth, centreY),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(color = Color.Black.copy(alpha = 0.18f), radius = inset + 1.5f, center = Offset(centreX, knobY + 1.5f))
        drawCircle(color = Color.White, radius = inset, center = Offset(centreX, knobY))
    }
}

/** Half-decibel steps, with a wider one at zero: flat is the value a band is dragged back to. */
private fun snapGain(raw: Float, range: Float): Float {
    if (abs(raw) < ZERO_DETENT_DB) return 0f
    return ((raw * 2f).roundToInt() / 2f).coerceIn(-range, range)
}

private fun formatGain(value: Float): String = when {
    abs(value) < 0.05f -> "0"
    value > 0 -> "+" + trimGain(value)
    else -> trimGain(value)
}

private fun trimGain(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.05f) value.roundToInt().toString() else oneDecimal(value)

private fun oneDecimal(value: Float): String {
    val tenths = (value * 10).roundToInt()
    val sign = if (tenths < 0) "-" else ""
    return "$sign${abs(tenths) / 10}.${abs(tenths) % 10}"
}

/** "60", "2.5k", "14k" — the labels a hardware equaliser prints. */
private fun formatFrequency(hz: Float): String = when {
    hz < 1_000f -> hz.roundToInt().toString()
    hz % 1_000f == 0f -> "${(hz / 1_000f).roundToInt()}k"
    else -> "${oneDecimal(hz / 1_000f)}k"
}

// ---- Balance ---------------------------------------------------------------

@Composable
private fun BalanceControl(balance: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val haptics = rememberHaptics()
    val trackColor = Color.White.copy(alpha = 0.18f)
    val latest by rememberUpdatedState(balance)
    val currentOnChange by rememberUpdatedState(onChange)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("L", style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(
                twoDecimals(balance),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text("R", style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(KNOB_RADIUS * 2 + 8.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = latest
                        fun report(offset: Offset) {
                            val inset = KNOB_RADIUS.toPx()
                            val usable = (size.width - inset * 2).coerceAtLeast(1f)
                            val raw = ((offset.x - inset) / usable).coerceIn(0f, 1f) * 2f - 1f
                            val snapped = if (abs(raw) < BALANCE_DETENT) 0f else raw
                            if (abs(snapped - last) > 0.004f) {
                                if (snapped == 0f && last != 0f) haptics.play(Haptic.Tick)
                                last = snapped
                                currentOnChange(snapped)
                            }
                        }
                        report(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                report(change.position)
                            }
                        }
                    }
                },
        ) {
            val inset = KNOB_RADIUS.toPx()
            val usable = size.width - inset * 2
            val centreY = size.height / 2
            val centreX = inset + usable / 2
            val trackHeight = TRACK_WIDTH.toPx()
            val knobX = inset + usable * (balance + 1f) / 2f
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(inset, centreY - trackHeight / 2),
                size = Size(usable, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2),
            )
            if (abs(balance) > 0.004f) {
                drawRoundRect(
                    color = AccentBlue,
                    topLeft = Offset(minOf(centreX, knobX), centreY - trackHeight / 2),
                    size = Size(abs(centreX - knobX), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2),
                )
            }
            drawCircle(color = Color.Black.copy(alpha = 0.18f), radius = inset + 1.5f, center = Offset(knobX, centreY + 1.5f))
            drawCircle(color = Color.White, radius = inset, center = Offset(knobX, centreY))
        }
    }
}

private fun twoDecimals(value: Float): String {
    val hundredths = (value * 100).roundToInt()
    val sign = if (hundredths < 0) "-" else ""
    val whole = abs(hundredths)
    return "$sign${whole / 100}.${(whole % 100).toString().padStart(2, '0')}"
}

// ---- Presets ---------------------------------------------------------------

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(selected: EqualizerPreset, onSelect: (EqualizerPreset) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Preset",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            )
            Divider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                EqualizerPreset.entries.forEach { preset ->
                    // Custom is a state the bands can be in, not a curve anyone can choose.
                    if (preset == EqualizerPreset.CUSTOM && selected != EqualizerPreset.CUSTOM) return@forEach
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = preset != EqualizerPreset.CUSTOM) {
                                haptics.play(Haptic.Select)
                                onSelect(preset)
                            }
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(preset.label(), style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
                        if (preset == selected) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = AccentBlue, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun EqualizerPreset.label(): String = when (this) {
    EqualizerPreset.FLAT -> "Flat"
    EqualizerPreset.ACOUSTIC -> "Acoustic"
    EqualizerPreset.BASS_BOOST -> "Bass boost"
    EqualizerPreset.BASS_CUT -> "Bass cut"
    EqualizerPreset.VOCAL -> "Vocal"
    EqualizerPreset.TREBLE_BOOST -> "Treble boost"
    EqualizerPreset.TREBLE_CUT -> "Treble cut"
    EqualizerPreset.LOUDNESS -> "Loudness"
    EqualizerPreset.SPOKEN_WORD -> "Spoken word"
    EqualizerPreset.ELECTRONIC -> "Electronic"
    EqualizerPreset.ROCK -> "Rock"
    EqualizerPreset.HIP_HOP -> "Hip-hop"
    EqualizerPreset.JAZZ -> "Jazz"
    EqualizerPreset.CLASSICAL -> "Classical"
    EqualizerPreset.SMALL_SPEAKERS -> "Small speakers"
    EqualizerPreset.LATE_NIGHT -> "Late night"
    EqualizerPreset.CUSTOM -> "Custom"
}

private val AccentBlue = Color(0xFF0A84FF)
private val PAD_HEIGHT = 230.dp
private val PUCK_RADIUS = 19.dp
private val DOT_RADIUS = 2.5.dp
private val FADER_HEIGHT = 150.dp
private val FADER_WIDTH = 34.dp
private val TRACK_WIDTH = 5.dp
private val KNOB_RADIUS = 10.dp
private const val ZERO_DETENT_DB = 0.6f
private const val BALANCE_DETENT = 0.04f
