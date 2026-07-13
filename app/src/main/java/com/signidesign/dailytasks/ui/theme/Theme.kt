package com.signidesign.dailytasks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** User-facing theme preference; SYSTEM follows the OS, the others override it. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Semantic accent slots, kept outside Material's ColorScheme on purpose:
 * the Material scheme stays strictly monochrome, and every intentional use
 * of color goes through this palette. Adding future semantic colors
 * (warning, danger, info…) means adding slots here — no screen rework.
 */
@Immutable
data class AccentPalette(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color
)

private val LightAccent = AccentPalette(
    accent = GreenAccentLight,
    onAccent = OnGreenAccentLight,
    accentContainer = GreenContainerLight,
    onAccentContainer = OnGreenContainerLight
)

private val DarkAccent = AccentPalette(
    accent = GreenAccentDark,
    onAccent = OnGreenAccentDark,
    accentContainer = GreenContainerDark,
    onAccentContainer = OnGreenContainerDark
)

val LocalAccentPalette = staticCompositionLocalOf { LightAccent }

// Monochrome Material schemes. `primary` is mapped to the accent so stock
// Material components (Switch, Checkbox, buttons) pick it up for their
// active states without per-usage overrides — that is exactly the
// "positive-action reinforcement" role the accent has.
private val LightColors = lightColorScheme(
    primary = GreenAccentLight,
    onPrimary = OnGreenAccentLight,
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = OnGreenContainerLight,
    secondary = Grey700,
    onSecondary = Paper,
    secondaryContainer = Grey100,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Grey050,
    onSurfaceVariant = Grey600,
    surfaceContainerHighest = Grey100,
    surfaceContainerHigh = Grey050,
    surfaceContainer = Grey050,
    surfaceContainerLow = Paper,
    surfaceContainerLowest = Paper,
    outline = Grey300,
    outlineVariant = Grey200,
    error = Color(0xFFB3261E),
    onError = Paper
)

private val DarkColors = darkColorScheme(
    primary = GreenAccentDark,
    onPrimary = OnGreenAccentDark,
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = OnGreenContainerDark,
    secondary = Grey300,
    onSecondary = Ink,
    secondaryContainer = Grey800,
    onSecondaryContainer = Grey100,
    background = NightBackground,
    onBackground = Grey050,
    surface = NightBackground,
    onSurface = Grey050,
    surfaceVariant = Grey900,
    onSurfaceVariant = Grey400,
    surfaceContainerHighest = Grey800,
    surfaceContainerHigh = Grey850,
    surfaceContainer = Grey900,
    surfaceContainerLow = NightSurface,
    surfaceContainerLowest = NightBackground,
    outline = Grey700,
    outlineVariant = Grey800,
    error = Color(0xFFF2B8B5),
    onError = Ink
)

/** Convenience accessor: `AppTheme.accent.accent` etc. */
object AppTheme {
    val accent: AccentPalette
        @Composable @ReadOnlyComposable get() = LocalAccentPalette.current
}

@Composable
fun DailyTasksTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(
        LocalAccentPalette provides if (darkTheme) DarkAccent else LightAccent
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = DailyTypography,
            shapes = DailyShapes,
            content = content
        )
    }
}
