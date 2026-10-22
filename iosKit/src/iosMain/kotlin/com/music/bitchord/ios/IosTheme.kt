package com.music.bitchord.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/** The phone's dark scheme (app/…/ui/theme/Theme.kt); iOS draws in its own system face, SF Pro. */
internal val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
)

/** The phone's light scheme, as there. */
internal val LightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF7F7F9),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5EA),
)

/**
 * Whether the app's pages are drawn dark — the Theme setting, System following the iPhone — read
 * the way the phone's MainActivity reads it. The player and Replay's stories are dark whatever
 * this says.
 */
@Composable
internal fun rememberDarkTheme(): Boolean {
    val mode by IosSettings.themeMode.flow.collectAsState()
    val system = isSystemInDarkTheme()
    // System UI — alerts, the keyboard, the share sheet — follows the app rather than the iPhone.
    LaunchedEffect(mode) { applyInterfaceStyle(mode) }
    return when (mode) {
        "LIGHT" -> false
        "SYSTEM" -> system
        else -> true
    }
}

/**
 * Overrides the windows' light or dark so UIKit's own views match the app. System is left
 * unspecified, so the iPhone's setting still comes through — to UIKit and to [isSystemInDarkTheme]
 * alike, which is why it is never pinned to what the iPhone currently says.
 */
private fun applyInterfaceStyle(mode: String) {
    val style = when (mode) {
        "LIGHT" -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
        "SYSTEM" -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        else -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
    }
    UIApplication.sharedApplication.connectedScenes.forEach { scene ->
        (scene as? UIWindowScene)?.windows?.forEach { window -> (window as? UIWindow)?.setOverrideUserInterfaceStyle(style) }
    }
}

/** A settings group's fill: #1C1C1E on black, #F2F2F7 on white. */
internal val GroupColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant

/** The hairline between rows, and round a floating pill. */
internal val HairlineColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

/** Where artwork or an avatar is still to load, or a field inside a group. */
internal val PlaceholderColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

/** Whether the scheme in force is the dark one, for the few greys the two schemes don't carry. */
internal val isDarkScheme: Boolean
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** An off switch's track, iOS's own grey for each. */
internal val SwitchOffColor: Color
    @Composable @ReadOnlyComposable get() = if (isDarkScheme) Color(0xFF39393D) else Color(0xFFDCDCE0)
