package com.signidesign.dailytasks.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Raw tokens. Nothing outside ui/theme should reference these directly —
// screens consume them through MaterialTheme.colorScheme or AppTheme.accent.
// ---------------------------------------------------------------------------

// Neutrals
val Paper = Color(0xFFFFFFFF)
val Ink = Color(0xFF0A0A0A)
val NightBackground = Color(0xFF050505) // true near-black, not grey
val NightSurface = Color(0xFF0D0D0D)

val Grey050 = Color(0xFFF7F7F7)
val Grey100 = Color(0xFFF0F0F0)
val Grey200 = Color(0xFFE2E2E2)
val Grey300 = Color(0xFFC9C9C9)
val Grey400 = Color(0xFFA8A8A8)
val Grey500 = Color(0xFF7C7C7C)
val Grey600 = Color(0xFF5C5C5C)
val Grey700 = Color(0xFF3A3A3A)
val Grey800 = Color(0xFF242424)
val Grey850 = Color(0xFF1B1B1B)
val Grey900 = Color(0xFF141414)

// Accent — the single expressive color in the system. Green, tuned per theme
// so it holds ~4.5:1 contrast against its background in both modes.
val GreenAccentLight = Color(0xFF0B7A45)
val OnGreenAccentLight = Color(0xFFFFFFFF)
val GreenContainerLight = Color(0xFFDDF3E6)
val OnGreenContainerLight = Color(0xFF06381F)

val GreenAccentDark = Color(0xFF4ADE80)
val OnGreenAccentDark = Color(0xFF04150C)
val GreenContainerDark = Color(0xFF0E2B1B)
val OnGreenContainerDark = Color(0xFF9BEFBB)
