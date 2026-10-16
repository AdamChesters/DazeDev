package com.adamchesters.dazedev.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = TealPrimary,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB9ECF5),
        onPrimaryContainer = Color(0xFF003B47),
        secondary = TealSecondary,
        onSecondary = Color(0xFF002A32),
        tertiary = OrangeAccent,
        onTertiary = Color.White,
        background = LightBackground,
        onBackground = LightForeground,
        surface = LightSurface,
        onSurface = LightForeground,
        surfaceVariant = LightMuted,
        onSurfaceVariant = Color(0xFF475558),
        outline = LightOutline,
        error = Color(0xFFB91C1C),
        onError = Color.White,
    )

private val DarkColors =
    darkColorScheme(
        primary = DarkPrimary,
        onPrimary = Color(0xFF002C36),
        primaryContainer = Color(0xFF004E5E),
        onPrimaryContainer = Color(0xFFCCF5FC),
        secondary = DarkSecondary,
        onSecondary = Color(0xFF002C36),
        tertiary = DarkAccent,
        onTertiary = Color(0xFF342500),
        background = DarkBackground,
        onBackground = DarkForeground,
        surface = DarkSurface,
        onSurface = DarkForeground,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = Color(0xFFC4CDCB),
        outline = DarkOutline,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

@Composable
fun DazeDevMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = DazeDevMobileTypography,
        content = content,
    )
}
