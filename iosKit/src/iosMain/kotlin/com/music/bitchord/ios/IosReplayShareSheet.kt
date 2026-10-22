package com.music.bitchord.ios

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.TrackLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import kotlin.coroutines.resume

/** A share sheet's subject: the whole Replay when [page] is null, else that one card. */
internal data class ReplayShareRequest(val summary: ReplaySummary, val page: ReplayStoryPage?)

/**
 * The picture, and the two things to do with it — the phone's `ReplayShareSheet`.
 *
 * The image is the sheet because nobody shares a Replay they have not looked
 * at; the system share sheet on its own is a list of app names with no sight of
 * what is about to be sent. Share hands the PNG to it (which also offers Save
 * Image and Save to Files); Save puts it straight in Photos, since that is the
 * one destination people reach for often enough to deserve its own button.
 *
 * Painted dark whatever the app's theme, as the Replay itself is: the preview
 * is a dark picture, and on a light sheet it reads as a hole.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplayShareSheet(request: ReplayShareRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember(request) { mutableStateOf<ImageBitmap?>(null) }
    var file by remember(request) { mutableStateOf<String?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    var saving by remember(request) { mutableStateOf(false) }
    var note by remember(request) { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        runCatching {
            val memberSince = withContext(Dispatchers.Default) { replayMemberSince() }
            val holder = IosAccount.account.value?.name.orEmpty()
            val image = renderReplayPoster(request.summary, holder, memberSince, request.page)
            // Written out as soon as it is drawn, so both buttons act on the tap
            // rather than after an encode.
            file = withContext(Dispatchers.Default) { writePoster(image, request.summary.label) }
            preview = image.toComposeImageBitmap()
        }.onFailure {
            TrackLog.w("IosReplayShare", "poster failed: ${it.message}")
            failed = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Straight to full height: half-open, both buttons sat below the fold.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBackground,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = "Share my Replay",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.W800,
                color = Color.White,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = if (request.page == null) {
                    "A full picture with songs, artists, albums, genres, and habits."
                } else {
                    "A polished card with your main listening highlights."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))

            // Small enough that the buttons are on screen with it.
            Box(
                Modifier
                    .fillMaxWidth(0.42f)
                    .align(Alignment.CenterHorizontally)
                    .aspectRatio(POSTER_W.toFloat() / POSTER_H)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                val image = preview
                when {
                    image != null -> Image(
                        bitmap = image,
                        contentDescription = "Your Replay",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    failed -> Text(
                        text = "Could not create the picture",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp),
                    )
                    else -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                }
            }

            Spacer(Modifier.height(22.dp))

            // Disabled rather than hidden while the poster renders: buttons that
            // appear a second after the sheet does get tapped as they move.
            val path = file
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareAction(
                    label = if (saved) "Saved" else "Save",
                    icon = if (saved) Icons.Rounded.Check else Icons.Rounded.Download,
                    accent = false,
                    enabled = path != null && !saved && !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    val target = path ?: return@ShareAction
                    saving = true
                    note = null
                    scope.launch {
                        when (saveToPhotos(target)) {
                            PhotoSave.SAVED -> saved = true
                            PhotoSave.DENIED -> note = "Allow BitChord to add photos in Settings to save it."
                            PhotoSave.FAILED -> note = "Couldn’t save the picture."
                        }
                        saving = false
                    }
                }
                ShareAction(
                    label = "Share",
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = path != null,
                    modifier = Modifier.weight(1f),
                ) {
                    val target = path ?: return@ShareAction
                    sharePoster(target)
                    onDismiss()
                }
            }
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ShareAction(
    label: String,
    icon: ImageVector,
    /** The one that sends it, which is the one that should be reached for. */
    accent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = if (accent) ReplayAccent else Color.White.copy(alpha = 0.12f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background.copy(alpha = background.alpha * if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Crossfaded, so Save settling into Saved reads as one button changing state.
        Crossfade(targetState = label to icon, label = "shareAction") { (text, glyph) ->
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = glyph, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W700,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The poster as a PNG in the temporary directory, for the share sheet and Photos to read.
 *
 * One name per Replay period, overwritten: the folder is a hand-off point, not an album. The
 * name is what Save to Files and AirDrop show, so it says what the picture is.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writePoster(image: org.jetbrains.skia.Image, label: String): String {
    val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
    val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val path = NSTemporaryDirectory() + "bitchord-replay-$slug.png"
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    check(data.writeToFile(path, atomically = true)) { "couldn't write $path" }
    return path
}

/** The system share sheet with the PNG, over whatever is on top. */
private fun sharePoster(path: String) {
    val controller = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
    var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    top?.presentViewController(controller, animated = true, completion = null)
}

private enum class PhotoSave { SAVED, DENIED, FAILED }

/**
 * Adds the PNG to Photos.
 *
 * Add-only access, which is the narrower prompt (NSPhotoLibraryAddUsageDescription) and all
 * this needs: the app never reads the library. Limited counts as granted, since adding is
 * unaffected by which photos the app may see.
 */
private suspend fun saveToPhotos(path: String): PhotoSave = suspendCancellableCoroutine { continuation ->
    fun finish(result: PhotoSave) {
        if (continuation.isActive) continuation.resume(result)
    }
    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
        if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
            finish(PhotoSave.DENIED)
            return@requestAuthorizationForAccessLevel
        }
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            { PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(NSURL.fileURLWithPath(path)) },
            completionHandler = { success, _ -> finish(if (success) PhotoSave.SAVED else PhotoSave.FAILED) },
        )
    }
}

private val SheetBackground = Color(0xFF141416)
private val ReplayAccent = Color(0xFFFA2D48)
