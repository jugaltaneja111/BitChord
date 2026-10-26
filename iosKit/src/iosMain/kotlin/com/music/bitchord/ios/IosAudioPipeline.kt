package com.music.bitchord.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.NerdStats
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortAirPlay
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortUSBAudio
import platform.AVFAudio.currentRoute
import platform.AVFAudio.IOBufferDuration
import platform.AVFAudio.outputLatency
import platform.AVFAudio.outputNumberOfChannels
import platform.AVFAudio.sampleRate
import kotlin.math.abs
import kotlin.math.roundToInt

/** The route iOS is playing to, as the Output stage reads it. */
private data class OutputRoute(
    val name: String?,
    val kind: String,
    val bluetooth: Boolean,
    val airPlay: Boolean,
    val sampleRateHz: Int?,
    val channels: Int?,
    val bufferMs: Int?,
    val latencyMs: Int?,
)

private fun currentOutput(): OutputRoute {
    val session = AVAudioSession.sharedInstance()
    val port = session.currentRoute.outputs.firstOrNull() as? AVAudioSessionPortDescription
    val type = port?.portType
    val kind = when (type) {
        AVAudioSessionPortBuiltInSpeaker -> tr("Phone speaker")
        AVAudioSessionPortHeadphones -> tr("Wired headphones")
        AVAudioSessionPortBluetoothA2DP, AVAudioSessionPortBluetoothLE -> tr("Bluetooth")
        AVAudioSessionPortAirPlay -> "AirPlay"
        AVAudioSessionPortUSBAudio -> tr("USB audio")
        null -> "—"
        else -> type
    }
    return OutputRoute(
        name = port?.portName,
        kind = kind,
        bluetooth = type == AVAudioSessionPortBluetoothA2DP || type == AVAudioSessionPortBluetoothLE,
        airPlay = type == AVAudioSessionPortAirPlay,
        sampleRateHz = session.sampleRate.takeIf { it > 0 }?.roundToInt(),
        channels = session.outputNumberOfChannels.toInt().takeIf { it > 0 },
        bufferMs = (session.IOBufferDuration * 1000).takeIf { it > 0 }?.roundToInt(),
        latencyMs = (session.outputLatency * 1000).takeIf { it > 0 }?.roundToInt(),
    )
}

private fun rateText(hz: Int): String {
    val khz = hz / 1000.0
    val tenths = (khz * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10}.${tenths % 10} kHz"
}

private fun dbText(db: Double): String {
    val tenths = (db * 10).roundToInt()
    val sign = if (tenths < 0) "−" else "+"
    return "$sign${abs(tenths) / 10}.${abs(tenths) % 10} dB"
}

/**
 * The phone's Audio Pipeline dialog, opened from the output drawer: the track as it arrived, the
 * decoder reading it, what the app's own processing does to it and where iOS sends it, then
 * whether the samples reach the output untouched. Read twice a second while open.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun IosAudioPipelineDialog(hazeState: HazeState, onDismiss: () -> Unit) {
    var snapshot by remember { mutableStateOf(IosPlayer.pipeline()) }
    var output by remember { mutableStateOf(currentOutput()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = IosPlayer.pipeline()
            output = currentOutput()
            delay(500)
        }
    }
    val eqEnabled by IosSettings.equalizerEnabled.flow.collectAsState()
    val eqMode by IosSettings.equalizerMode.flow.collectAsState()
    val eqBands by IosSettings.equalizerBands.flow.collectAsState()
    val loudnessOn by IosSettings.loudnessNormalization.flow.collectAsState()
    val skipSilence by IosSettings.skipSilence.flow.collectAsState()
    val crossfade by IosSettings.crossfadeSeconds.flow.collectAsState()
    val automix by IosSettings.automix.flow.collectAsState()
    val reduceBlur by IosSettings.reduceDynamicBlur.flow.collectAsState()

    val (format, chain, source) = snapshot
    val lossless = format?.mimeType?.let { it.endsWith("flac") || it.endsWith("alac") || it.contains("wav") || it.contains("raw") } == true
    val codec = NerdStats.codecLabel(format?.mimeType) ?: format?.mimeType ?: "—"
    val channelsText = when (format?.channels) {
        null -> "—"
        1 -> tr("Mono")
        2 -> tr("Stereo")
        else -> tr("{0} channels", format.channels)
    }
    val eqText = when {
        !eqEnabled -> tr("Off")
        eqMode == IosSound.MODE_MANUAL -> EqualizerPreset.matching(eqBands).name.lowercase().replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
        else -> tr("Dynamic")
    }
    val gainDb = chain?.loudnessGainDb
    val loudnessText = when {
        !loudnessOn -> tr("Off")
        gainDb == null -> tr("Not measured")
        abs(gainDb) < 0.05 -> tr("Off on this output")
        else -> dbText(gainDb)
    }
    val levelled = loudnessOn && gainDb != null && abs(gainDb) >= 0.05
    val resampled = format?.sampleRateHz != null && output.sampleRateHz != null && format.sampleRateHz != output.sampleRateHz
    val verdict = when {
        format == null -> "—"
        levelled -> tr("No — loudness normalization")
        eqEnabled -> tr("No — equalizer")
        resampled -> tr("No — iOS resamples to {0}", rateText(output.sampleRateHz!!))
        output.bluetooth -> tr("No — Bluetooth re-encodes it")
        output.airPlay -> tr("No — AirPlay re-encodes it")
        else -> tr("Yes")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 48.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .then(
                    if (reduceBlur) {
                        Modifier.background(Color(0xFF121212))
                    } else {
                        Modifier
                            .optimizedHazeEffect(state = hazeState, style = HazeMaterials.regular(Color(0xFF141414)))
                            .background(Color(0xFF121212).copy(alpha = 0.9f))
                    },
                )
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {}),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(tr("Audio Pipeline"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.W600, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("The signal path from the file to your ears"),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Stage(Icons.AutoMirrored.Rounded.InsertDriveFile, tr("Track")) {
                    InfoRow(tr("Source"), source ?: "—")
                    InfoRow(tr("Format"), codec)
                    InfoRow(tr("Bit depth"), if (lossless) format?.bitDepth?.let { "$it-bit" } ?: "—" else "—")
                    InfoRow(tr("Sample rate"), format?.sampleRateHz?.let(::rateText) ?: "—")
                    InfoRow(tr("Bitrate"), format?.estimatedKbps?.let { "$it kbps" } ?: "—")
                    InfoRow(tr("Channels"), channelsText)
                }
                Stage(Icons.Rounded.Memory, tr("Decoder")) {
                    InfoRow(
                        tr("Decoder"),
                        when (chain?.ffmpeg) {
                            null -> "—"
                            true -> "FFmpeg (libavcodec)"
                            false -> "AVFoundation"
                        },
                    )
                    InfoRow(tr("Output"), if (chain == null) "—" else tr("32-bit float PCM"))
                }
                Stage(Icons.Rounded.GraphicEq, tr("Processing")) {
                    InfoRow(tr("Audio tap"), if (chain?.tapAttached == true) tr("Active") else tr("Not attached"))
                    InfoRow(tr("Loudness gain"), loudnessText)
                    InfoRow(tr("Equalizer"), eqText)
                    InfoRow(tr("Skip silence"), if (skipSilence) tr("On") else tr("Off"))
                    InfoRow(
                        tr("Transitions"),
                        when {
                            automix -> "Automix"
                            crossfade > 0 -> tr("Crossfade {0} s", crossfade)
                            else -> tr("Off")
                        },
                    )
                }
                Stage(Icons.AutoMirrored.Rounded.VolumeUp, tr("Output")) {
                    InfoRow(tr("Device"), output.name?.let { if (it == output.kind) it else "$it (${output.kind})" } ?: output.kind)
                    InfoRow(tr("Sample rate"), output.sampleRateHz?.let(::rateText) ?: "—")
                    InfoRow(tr("Channels"), output.channels?.toString() ?: "—")
                    InfoRow(tr("Buffer"), output.bufferMs?.let { "$it ms" } ?: "—")
                    InfoRow(tr("Latency"), output.latencyMs?.let { "$it ms" } ?: "—")
                    InfoRow(tr("Output API"), "Core Audio")
                }
                Rule()
                InfoRow(tr("Bit-exact"), verdict, emphasis = true)
                Spacer(Modifier.height(8.dp))
            }
            Rule(Modifier.padding(horizontal = 0.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("Done"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.W600)
            }
        }
    }
}

@Composable
private fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
}

@Composable
private fun Stage(icon: ImageVector, title: String, rows: @Composable () -> Unit) {
    Rule()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.W600)
        }
        Spacer(Modifier.height(6.dp))
        rows()
    }
}

@Composable
private fun InfoRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 26.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = if (emphasis) FontWeight.W600 else FontWeight.Normal)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = Color.White.copy(alpha = if (emphasis) 1f else 0.9f),
            fontSize = 13.sp,
            fontWeight = if (emphasis) FontWeight.W600 else FontWeight.Normal,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
