package com.we.meet.ui.theme

import androidx.compose.ui.graphics.Color

// Brand seed and a small handful of derived tones.  Material 3 will fill in
// the rest of the palette via lightColorScheme/darkColorScheme.
val Seed = Color(0xFF1F6FEB)

val LightPrimary = Seed
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6E4FF)
val LightOnPrimaryContainer = Seed
val LightBackground = Color(0xFFFCFCFD)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1A1C1E)

val DarkPrimary = Color(0xFF7BAAFB)
val DarkOnPrimary = Color(0xFF002F69)
val DarkPrimaryContainer = Color(0xFF1E3A7A)
val DarkOnPrimaryContainer = Color(0xFFD6E4FF)
val DarkBackground = Color(0xFF111418)
val DarkSurface = Color(0xFF1A1C1E)
val DarkOnSurface = Color(0xFFE2E2E5)

// --- Semantic tokens not covered by Material 3's ColorScheme ---------------
// These surface in [WeMeetExtras] and are resolved by [WeMeetTheme] based
// on the active light/dark mode, so callers never branch on the theme.

/** Thin tinted band used to separate zones on the Home page (飞书-style). */
val LightSurfaceBand = Color(0xFFF2F4F3)
val DarkSurfaceBand = Color(0xFF0A0A0A)
