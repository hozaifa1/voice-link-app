package com.streamsync.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalThemeColorPalette = staticCompositionLocalOf { StreamSyncPalette }

private fun buildLightColorScheme(p: ThemeColorPalette) = lightColorScheme(
    primary = p.primary,
    onPrimary = Color.White,
    primaryContainer = p.primaryContainerLight,
    onPrimaryContainer = p.primaryVariant,
    secondary = p.secondary,
    onSecondary = Color.White,
    secondaryContainer = p.secondaryContainerLight,
    onSecondaryContainer = p.primaryVariant,
    background = p.backgroundLight,
    onBackground = p.textPrimary,
    surface = p.surfaceLight,
    onSurface = p.textPrimary,
    surfaceVariant = p.surfaceVariantLight,
    onSurfaceVariant = p.textSecondary,
    error = Error,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Error,
    outline = p.outlineLight
)

private fun buildDarkColorScheme(p: ThemeColorPalette) = darkColorScheme(
    primary = p.primaryLight,
    onPrimary = p.primaryVariant,
    primaryContainer = p.cardDark,
    onPrimaryContainer = p.textOnDark,
    secondary = p.secondary,
    onSecondary = p.primaryVariant,
    secondaryContainer = p.surfaceVariantDark,
    onSecondaryContainer = p.textOnDark,
    background = p.backgroundDark,
    onBackground = p.textOnDark,
    surface = p.surfaceDark,
    onSurface = p.textOnDark,
    surfaceVariant = p.surfaceVariantDark,
    onSurfaceVariant = p.textMuted,
    error = Error,
    errorContainer = ErrorDark,
    onErrorContainer = Color(0xFFFFB4AB),
    outline = p.outlineDark
)

@Composable
fun StreamSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = StreamSyncPalette
    val colorScheme = if (darkTheme) buildDarkColorScheme(palette) else buildLightColorScheme(palette)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalThemeColorPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
