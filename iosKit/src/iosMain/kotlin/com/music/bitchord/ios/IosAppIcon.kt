package com.music.bitchord.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image as SkiaImage
import platform.UIKit.UIApplication
import platform.UIKit.alternateIconName
import platform.UIKit.setAlternateIconName
import platform.UIKit.supportsAlternateIcons
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

/**
 * The home-screen icons, as the phone's AppIcon offers them. iOS draws its own rounded square,
 * so only the Liquid Glass set — drawn in that shape — comes across; the Samsung and Pixel
 * shapes are Android launchers' own. [iconName] is the alternate icon's name in the asset
 * catalog, null for the shipped one.
 */
internal enum class IosAppIcon(val iconName: String?, val label: String) {
    Default(null, "Default"),
    Glass("AppIconGlass", "Liquid Glass"),
    GlassDark("AppIconGlassDark", "Liquid Glass Dark"),
    GlassClear("AppIconGlassClear", "Clear"),
    GlassClearDark("AppIconGlassClearDark", "Clear Dark"),
    ;

    /** The small copy kept beside each icon for this picker; the icons themselves can't be read back. */
    val previewName: String get() = "Preview${iconName ?: "AppIcon"}"

    companion object {
        fun current(): IosAppIcon {
            val name = UIApplication.sharedApplication.alternateIconName
            return entries.firstOrNull { it.iconName == name } ?: Default
        }
    }
}

@Composable
internal fun AppIconPicker() {
    var selected by remember { mutableStateOf(IosAppIcon.current()) }
    val supported = remember { UIApplication.sharedApplication.supportsAlternateIcons }
    Column(Modifier.padding(vertical = 14.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IosAppIcon.entries.forEach { icon ->
                val isSelected = icon == selected
                Column(
                    Modifier
                        .width(76.dp)
                        .clickable(enabled = supported && !isSelected) {
                            UIApplication.sharedApplication.setAlternateIconName(icon.iconName) { error ->
                                if (error == null) {
                                    selected = icon
                                } else {
                                    IosPlayerHost.showMessage("Could not change the icon")
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val shape = RoundedCornerShape(15.dp)
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(shape)
                            .background(PlaceholderColor)
                            .border(if (isSelected) 2.5.dp else 0.5.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape),
                    ) {
                        rememberBundleImage(icon.previewName)?.let { bitmap ->
                            Image(bitmap, contentDescription = icon.label, modifier = Modifier.size(64.dp).clip(shape))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        icon.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
        if (!supported) {
            Text(
                "This install can't change its icon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
        }
    }
}

/** An image from the app's asset catalog, as Compose draws it. */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun rememberBundleImage(name: String): ImageBitmap? = remember(name) {
    val data = UIImage.imageNamed(name)?.let { UIImagePNGRepresentation(it) } ?: return@remember null
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

/** What the artwork cache holds on disk, in bytes. */
internal fun imageCacheSize(): Long =
    SingletonImageLoader.get(PlatformContext.INSTANCE).diskCache?.size ?: 0L

internal fun clearImageCache() {
    val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
}
