package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import com.music.bitchord.data.systemNanoTime
import kotlin.math.floor
import kotlin.math.round
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkRect
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication

internal actual fun uptimeMillis(): Long = systemNanoTime() / 1_000_000L

// Skia blurs at every size.
internal actual val renderEffectBlurSupported: Boolean = true

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        onDispose { if (enabled) UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

// The first preferred language is the one an iOS app is localised into.
@Composable
internal actual fun appLanguageTag(): String =
    NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"

@Composable
internal actual fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val entry = remember { PlayerBack.Entry { current.value() } }
    DisposableEffect(enabled) {
        if (enabled) PlayerBack.push(entry)
        onDispose { PlayerBack.remove(entry) }
    }
}

/**
 * The player's layers in back order, as on the desktop. iOS has no back button; the app shell
 * routes its back gesture here, and the newest enabled layer takes it. Main thread only.
 */
object PlayerBack {
    class Entry(val onBack: () -> Unit)

    private val entries = ArrayList<Entry>()

    internal fun push(entry: Entry) {
        entries.remove(entry)
        entries.add(entry)
    }

    internal fun remove(entry: Entry) {
        entries.remove(entry)
    }

    /** Back, from the app. False when no layer of the player wanted it. */
    fun dispatch(): Boolean {
        val top = entries.lastOrNull() ?: return false
        top.onBack()
        return true
    }
}

// A finger both scrolls the list and moves the drawer, as on the phone.
internal actual val drawerFollowsListScroll: Boolean get() = true

/** Linear, so a layer moved by a fraction of a pixel lands between two. */
private val subPixelSampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

/**
 * The desktop's version: Skia snaps glyphs to whole pixels vertically on iOS too, so the fraction
 * of [dy] goes through a resampled layer while there is one worth moving.
 */
internal actual fun DrawScope.clipShiftedDown(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    dy: Float,
    block: DrawScope.() -> Unit,
) {
    val whole = floor(dy)
    val fraction = dy - whole
    if (fraction < 0.02f || fraction > 0.98f) {
        clipRect(left = left, top = top, right = right, bottom = bottom) {
            translate(top = round(dy)) { block() }
        }
        return
    }
    val canvas = drawContext.canvas.nativeCanvas
    val paint = Paint().apply {
        imageFilter = ImageFilter.makeMatrixTransform(
            Matrix33.makeTranslate(0f, fraction),
            subPixelSampling,
            null,
        )
    }
    canvas.saveLayer(SkRect.makeLTRB(left, top - 1f, right, bottom + 1f), paint)
    clipRect(left = left, top = top - fraction, right = right, bottom = bottom - fraction) {
        translate(top = whole) { block() }
    }
    canvas.restore()
    paint.close()
}
