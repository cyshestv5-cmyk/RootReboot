package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MagicOsDarkColorScheme = darkColorScheme(
    primary = MagicBlue,
    onPrimary = MagicTextPrimary,
    primaryContainer = MagicBlueBright,
    onPrimaryContainer = MagicTextPrimary,
    secondary = MagicBadgeTeal,
    onSecondary = MagicTextPrimary,
    tertiary = MagicBadgeGreen,
    onTertiary = MagicTextPrimary,
    background = MagicBlack,
    onBackground = MagicTextPrimary,
    surface = MagicCardSurface,
    onSurface = MagicTextPrimary,
    surfaceVariant = MagicNestedPill,
    onSurfaceVariant = MagicTextSecondary,
    surfaceContainerHigh = MagicCardElevated,
    outline = MagicDivider,
    outlineVariant = MagicDivider,
    error = MagicBadgeRed,
    onError = MagicTextPrimary,
)

@Composable
fun MagicOsTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MagicOsDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
