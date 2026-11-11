package com.visionagent.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val HighContrastDarkColorScheme = darkColorScheme(
    primary = DescribeYellow,
    onPrimary = ButtonTextDark,
    secondary = ReadCyan,
    onSecondary = ButtonTextDark,
    tertiary = AskLime,
    onTertiary = ButtonTextDark,
    background = PureBlack,
    onBackground = HighContrastWhite,
    surface = DarkSurface,
    onSurface = HighContrastWhite,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = SecondaryTextWhite,
    error = HighContrastError,
    onError = PureBlack
)

/**
 * Vision Agent Material 3 Theme.
 * Always renders in high-contrast dark mode to maximize visibility for low-vision users
 * and battery efficiency on OLED displays.
 */
@Composable
fun VisionAgentTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = HighContrastDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = PureBlack.toArgb()
            window.navigationBarColor = PureBlack.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
