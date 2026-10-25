package com.music.bitchord.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.Bitmap
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

/** The signature's widest share of the banner, and tallest. */
private const val SIGNATURE_WIDTH = 0.82f * 0.7f
private const val SIGNATURE_MAX_HEIGHT = 0.30f * 0.7f

/** Vector signatures decode at this width, so a small file stays crisp laid out large. */
private const val SVG_DECODE_WIDTH = 1600

/**
 * The artist's signature at the foot of their photograph, with a "Signed by" line under it, in
 * place of the name once it has drawn — the phone's header when Apple has no title logo, which
 * on iOS is always. Vectors are dark ink on transparency, tinted white to read on the photo;
 * scans go through [SignatureInk]. [onShown] says when the name can step aside.
 */
@Composable
internal fun ArtistSignatureImage(
    signature: ArtistSignature,
    name: String,
    bannerHeight: Dp,
    onShown: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val isSvg = signature.format == "svg"
    val aspect = signature.aspect.coerceIn(0.4f, 6f)
    val request = remember(signature.imageUrl) {
        ImageRequest.Builder(context).data(signature.imageUrl).apply {
            if (isSvg) size(SVG_DECODE_WIDTH, (SVG_DECODE_WIDTH / aspect).roundToInt()) else size(Size.ORIGINAL)
            if (!isSvg) transformations(SignatureInk)
        }.build()
    }
    var shown by remember(signature.imageUrl) { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            colorFilter = if (isSvg) ColorFilter.tint(Color.White) else null,
            onState = { state ->
                val now = state is AsyncImagePainter.State.Success
                if (now != shown) {
                    shown = now
                    onShown(now)
                }
            },
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).layout { measurable, constraints ->
                val widest = constraints.maxWidth * SIGNATURE_WIDTH
                val height = minOf(widest / aspect, bannerHeight.toPx() * SIGNATURE_MAX_HEIGHT).roundToInt().coerceAtLeast(1)
                val width = (height * aspect).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            },
        )
        if (shown) {
            val signed = tr("Signed by {0}", name)
            val at = signed.indexOf(name)
            Text(
                buildAnnotatedString {
                    append(signed)
                    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + name.length)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * The phone's SignatureInkTransformation: a scanned signature, dark ink on white paper, as white
 * strokes on transparency, each pixel's alpha its darkness. An image that doesn't read as a scan
 * — under [SCAN_WHITE_FRACTION] of it paper — is left as it is, so a photographed autograph never
 * turns into white blobs.
 */
internal object SignatureInk : Transformation() {
    private const val PAPER_LUMINANCE = 0.92f
    private const val SCAN_WHITE_FRACTION = 0.55f

    override val cacheKey = "signature-ink-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width <= 0 || height <= 0) return input
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val pixels = input.readPixels(info, width * 4, 0, 0) ?: return input
        val count = width * height
        var paper = 0
        for (i in 0 until count) if (luminance(pixels, i * 4) >= PAPER_LUMINANCE) paper++
        if (paper.toFloat() / count < SCAN_WHITE_FRACTION) return input
        for (i in 0 until count) {
            val at = i * 4
            val inAlpha = (pixels[at + 3].toInt() and 0xFF) / 255f
            val darkness = 1f - luminance(pixels, at)
            val alpha = if (inAlpha <= 0f || darkness <= 0f) 0 else (darkness * inAlpha * 255f).roundToInt().coerceIn(0, 255)
            pixels[at] = 0xFF.toByte()
            pixels[at + 1] = 0xFF.toByte()
            pixels[at + 2] = 0xFF.toByte()
            pixels[at + 3] = alpha.toByte()
        }
        val out = Bitmap()
        if (!out.allocPixels(info) || !out.installPixels(info, pixels, width * 4)) return input
        return out
    }

    private fun luminance(pixels: ByteArray, at: Int): Float {
        val r = (pixels[at].toInt() and 0xFF) / 255f
        val g = (pixels[at + 1].toInt() and 0xFF) / 255f
        val b = (pixels[at + 2].toInt() and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}

/**
 * The phone's Signature API card: the API's address, kept only once `GET /v1/health` answers.
 * Removing it forgets the provider.
 */
@Composable
internal fun SignatureApiEditor(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stored = IosSettings.signatureApiUrl.flow.value
    var input by remember { mutableStateOf(stored) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun test(onGood: (String) -> Unit) {
        val normalized = IosArtistSignatures.normalizeBaseUrl(input)
        if (!IosArtistSignatures.isValidBaseUrl(normalized)) {
            status = tr("Could not reach the API at that URL") to false
            return
        }
        busy = true
        status = null
        scope.launch {
            val ok = IosArtistSignatures.checkHealth(normalized)
            busy = false
            status = (if (ok) tr("Connected") else tr("Could not reach the API at that URL")) to ok
            if (ok) onGood(normalized)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Signature API URL")) },
        text = {
            Column {
                Text(tr("Shows a handwritten signature on an artist’s page"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    placeholder = { Text("https://signatures.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Testing…"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                status?.let { (text, good) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (good) Color(0xFF34C759) else Color(0xFFFF6B6B),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { test {} }, enabled = !busy && input.isNotBlank()) { Text(tr("Test")) }
                TextButton(
                    onClick = {
                        test { url ->
                            IosSettings.signatureApiUrl.set(url)
                            IosArtistSignatures.clear()
                            onDismiss()
                        }
                    },
                    enabled = !busy && input.isNotBlank(),
                ) { Text(tr("Save")) }
            }
        },
        dismissButton = {
            Row {
                if (stored.isNotBlank()) {
                    TextButton(
                        onClick = {
                            IosSettings.signatureApiUrl.set("")
                            IosArtistSignatures.clear()
                            onDismiss()
                        },
                        enabled = !busy,
                    ) { Text(tr("Remove"), color = Color(0xFFFF6B6B)) }
                }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}
