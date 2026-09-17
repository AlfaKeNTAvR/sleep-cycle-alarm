package com.nikita.sleepcycle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark: this is a bedtime app, so it never switches to a light scheme.
private val NightColorScheme = darkColorScheme(
    primary = AmberAccent,
    onPrimary = OnAmberAccent,
    secondary = NightOnSurfaceMuted,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnBackground,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightOnSurfaceMuted,
    outline = NightSurfaceBorder,
    error = ErrorRed,
)

@Composable
fun SleepCycleAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColorScheme,
        typography = Typography,
        content = content,
    )
}
