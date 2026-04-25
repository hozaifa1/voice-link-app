package com.streamsync.app.ui.theme

import androidx.compose.ui.graphics.Color

// Semantic colors
val Warning = Color(0xFFFFA726)
val Error = Color(0xFFEF5350)
val ErrorDark = Color(0xFF4E2528)

data class ThemeColorPalette(
    val primary: Color,
    val primaryVariant: Color,
    val primaryLight: Color,
    val secondary: Color,
    val backgroundLight: Color,
    val backgroundDark: Color,
    val surfaceLight: Color,
    val surfaceDark: Color,
    val surfaceVariantDark: Color,
    val cardDark: Color,
    val blockBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textOnDark: Color,
    val textMuted: Color,
    val primaryContainerLight: Color,
    val secondaryContainerLight: Color,
    val surfaceVariantLight: Color,
    val outlineLight: Color,
    val outlineDark: Color,
    val gradientTopLight: Color,
    val gradientBottomLight: Color,
    val gradientTopDark: Color,
    val gradientBottomDark: Color,
    val dividerLight: Color,
    val dividerDark: Color
)

// StreamSync uses a fixed TEAL palette matching the screenrest-app reference.
val StreamSyncPalette = ThemeColorPalette(
    primary = Color(0xFF26A69A),
    primaryVariant = Color(0xFF00897B),
    primaryLight = Color(0xFF4DB6AC),
    secondary = Color(0xFF80CBC4),
    backgroundLight = Color(0xFFF5F7F6),
    backgroundDark = Color(0xFF0D1B1A),
    surfaceLight = Color(0xFFFFFFFF),
    surfaceDark = Color(0xFF142625),
    surfaceVariantDark = Color(0xFF1A302F),
    cardDark = Color(0xFF1C3331),
    blockBackground = Color(0xFF0A1514),
    textPrimary = Color(0xFF1A2B2A),
    textSecondary = Color(0xFF5F7A78),
    textOnDark = Color(0xFFE0F2F1),
    textMuted = Color(0xFF80A09E),
    primaryContainerLight = Color(0xFFD0F0ED),
    secondaryContainerLight = Color(0xFFE0F5F3),
    surfaceVariantLight = Color(0xFFEEF3F2),
    outlineLight = Color(0xFFBCC8C7),
    outlineDark = Color(0xFF3D5554),
    gradientTopLight = Color(0xFFE0F2F1),
    gradientBottomLight = Color(0xFFF5FAF9),
    gradientTopDark = Color(0xFF0D1F1E),
    gradientBottomDark = Color(0xFF0A1514),
    dividerLight = Color(0xFFB2DFDB),
    dividerDark = Color(0xFF1C3331)
)

// Legacy aliases retained for any existing references in non-screen code.
val Primary = StreamSyncPalette.primary
val PrimaryVariant = StreamSyncPalette.primaryVariant
val Secondary = StreamSyncPalette.secondary
val Background = StreamSyncPalette.backgroundLight
val Surface = StreamSyncPalette.surfaceLight
val OnPrimary = Color(0xFFFFFFFF)
val OnSecondary = Color(0xFFFFFFFF)
val OnBackground = StreamSyncPalette.textPrimary
val OnSurface = StreamSyncPalette.textPrimary
val OnError = Color(0xFFFFFFFF)

val DarkPrimary = StreamSyncPalette.primaryLight
val DarkPrimaryVariant = StreamSyncPalette.primaryVariant
val DarkSecondary = StreamSyncPalette.secondary
val DarkBackground = StreamSyncPalette.backgroundDark
val DarkSurface = StreamSyncPalette.surfaceDark
val DarkError = Error
val DarkOnPrimary = StreamSyncPalette.primaryVariant
val DarkOnSecondary = StreamSyncPalette.primaryVariant
val DarkOnBackground = StreamSyncPalette.textOnDark
val DarkOnSurface = StreamSyncPalette.textOnDark
val DarkOnError = Color(0xFFFFB4AB)
