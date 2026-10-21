package com.music.bitchord.ios

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.setMuted
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta

/**
 * The view a clip plays in: its own layer is an [AVPlayerLayer], so it lays itself out with the
 * view and needs no frame bookkeeping.
 */
@OptIn(ExperimentalForeignApi::class)
private class CanvasPlayerView : UIView(frame = CGRectZero.readValue()) {
    companion object : UIViewMeta() {
        override fun layerClass() = AVPlayerLayer
    }

    val playerLayer: AVPlayerLayer get() = layer as AVPlayerLayer
}

/**
 * The phone's AndroidCanvasVideo on AVFoundation: muted, looping (an [AVPlayerLooper] over an
 * [AVQueuePlayer], which AVPlayer plays from HLS and MP4 alike), drawn only once its first frame
 * is ready so the still art underneath never flashes black, and retried once at the clip's
 * fallback URL. Muted players take no part in the audio session, so the music is never touched.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val latest by rememberUpdatedState(spec)
    var url by remember(spec.canvas.url) { mutableStateOf(spec.canvas.url) }
    val player = remember { AVQueuePlayer().apply { setMuted(true) } }
    var looper by remember { mutableStateOf<AVPlayerLooper?>(null) }
    val view = remember { CanvasPlayerView().apply { backgroundColor = UIColor.clearColor; alpha = 0.0 } }

    // A new clip: a fresh looper over a fresh item.
    DisposableEffect(url) {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl != null) {
            val item = AVPlayerItem(uRL = nsUrl)
            looper?.disableLooping()
            player.removeAllItems()
            looper = AVPlayerLooper.playerLooperWithPlayer(player, templateItem = item)
        }
        latest.onRenderedChanged(false)
        view.alpha = 0.0
        onDispose { }
    }
    DisposableEffect(Unit) {
        view.playerLayer.player = player
        onDispose {
            looper?.disableLooping()
            player.pause()
            player.removeAllItems()
            view.playerLayer.player = null
        }
    }
    // Plays while it can be seen; a track change's hand-off holds it still.
    LaunchedEffect(spec.pausedForTransition) {
        if (spec.pausedForTransition) player.pause() else player.play()
    }
    view.playerLayer.videoGravity = when (spec.contentMode) {
        CanvasContentMode.CROP -> AVLayerVideoGravityResizeAspectFill
        CanvasContentMode.FIT_PORTRAIT -> AVLayerVideoGravityResizeAspect
    }
    // First frame, aspect ratio and failure aren't pushed by AVFoundation without KVO; a light
    // poll reads them, and stops caring once the clip is up.
    LaunchedEffect(url) {
        var rendered = false
        var reportedRatio = 0f
        while (true) {
            val item = player.currentItem
            if (item?.status == AVPlayerItemStatusFailed) {
                val fallback = latest.canvas.fallbackUrl
                if (fallback != null && fallback != url) {
                    url = fallback
                } else {
                    latest.onRenderedChanged(false)
                }
                break
            }
            item?.presentationSize?.useContents {
                if (width > 0 && height > 0) {
                    val ratio = (width / height).toFloat()
                    if (ratio != reportedRatio) {
                        reportedRatio = ratio
                        latest.onAspectRatioChanged(ratio)
                    }
                }
            }
            if (!rendered && view.playerLayer.readyForDisplay) {
                rendered = true
                latest.onRenderedChanged(true)
                latest.onCoverChanged(1f)
            }
            delay(if (rendered) 1_000 else 100)
        }
    }
    // The caller fades the clip in and out with its own animation, read per frame.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val target = if (view.playerLayer.readyForDisplay) latest.presentationAlpha().coerceIn(0f, 1f).toDouble() else 0.0
            if (view.alpha != target) view.alpha = target
        }
    }
    UIKitView(
        factory = { view },
        modifier = modifier,
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
}

/**
 * A clip over a page header's still — an album's animated cover, an artist's Apple Music video —
 * always running, as on the phone: there is no transport here to follow, and the page is only up
 * while it is being read. It fades in over the still once its first frame is ready.
 */
@Composable
internal fun HeaderClip(canvas: CanvasArtwork, modifier: Modifier) {
    var shown by remember(canvas.url) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(400), label = "header clip")
    val spec = CanvasVideoSpec(
        canvas = canvas,
        isPlaying = true,
        contentMode = CanvasContentMode.CROP,
        alignPortraitTop = false,
        onAspectRatioChanged = {},
        portraitRevealBounds = IntSize.Zero,
        presentationAlpha = { alpha },
        onRenderedChanged = { shown = it },
        onFrameCaptured = {},
        refreshFrameEveryMs = null,
        frameCapturePx = 0,
        onCoverChanged = {},
        bottomFade = 0f,
        bottomFadeEndPx = null,
        pausedForTransition = false,
    )
    IosCanvasVideo(spec, modifier)
}
