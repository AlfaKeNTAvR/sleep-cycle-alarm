package com.nikita.sleepcycle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark: this is a bedtime app, so it never switches to a light scheme. Every slot below is set
// explicitly rather than left to darkColorScheme()'s own defaults: any slot left unset falls back to
// Material 3's baseline purple palette, which is how the time picker's selected-hour block, and any other
// component that reaches for e.g. primaryContainer or a surfaceContainer* tone, ended up purple instead of
// following this theme. Container/tertiary/inverse slots reuse the same handful of app colors (amber for
// anything "selected", the near-black surface family for anything "container") rather than introducing new
// ones, since this app's palette is intentionally small.
private val NightColorScheme = darkColorScheme(
    primary = AmberAccent,
    onPrimary = OnAmberAccent,
    primaryContainer = AmberAccent,
    onPrimaryContainer = OnAmberAccent,
    inversePrimary = OnAmberAccent,
    secondary = NightOnSurfaceMuted,
    onSecondary = NightBackground,
    secondaryContainer = NightSurface,
    onSecondaryContainer = NightOnBackground,
    tertiary = AmberAccentBright,
    onTertiary = OnAmberAccent,
    tertiaryContainer = AmberAccent,
    onTertiaryContainer = OnAmberAccent,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnBackground,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightOnSurfaceMuted,
    surfaceTint = AmberAccent,
    surfaceBright = NightSurface,
    surfaceDim = NightBackground,
    surfaceContainerLowest = NightBackground,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurface,
    surfaceContainerHighest = NightSurfaceBorder,
    inverseSurface = NightOnBackground,
    inverseOnSurface = NightBackground,
    error = ErrorRed,
    onError = NightOnBackground,
    errorContainer = ErrorRed,
    onErrorContainer = NightOnBackground,
    outline = NightSurfaceBorder,
    outlineVariant = NightSurfaceBorder,
)

@Composable
fun SleepCycleAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColorScheme,
        typography = Typography,
        content = content,
    )
}
