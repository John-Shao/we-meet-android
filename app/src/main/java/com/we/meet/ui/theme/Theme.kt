package com.we.meet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
)

/**
 * Semantic color tokens that sit outside Material 3's [ColorScheme].
 *
 * Add new tokens here (and to Color.kt as `Light…` / `Dark…` pairs) rather
 * than branching on [isSystemInDarkTheme] at call sites. Consumers read
 * them via [WeMeetTheme.extras].
 */
data class WeMeetExtras(
    /** Thin tinted band used to separate zones on the Home page. */
    val surfaceBand: Color,
)

private val LightExtras = WeMeetExtras(
    surfaceBand = LightSurfaceBand,
)

private val DarkExtras = WeMeetExtras(
    surfaceBand = DarkSurfaceBand,
)

private val LocalWeMeetExtras = staticCompositionLocalOf { LightExtras }

/**
 * Whether the app is painting its dark palette (mirrors [WeMeetTheme]'s
 * `darkTheme`). Lets non-color consumers — e.g. the docs WebView, which must
 * tell embedded docs which scheme to render — read the active mode without
 * re-deriving it from settings. Read via [WeMeetTheme.isDark].
 */
private val LocalWeMeetIsDark = staticCompositionLocalOf { false }

/** Accessor mirroring `MaterialTheme.colorScheme` for our extras. */
object WeMeetTheme {
    val extras: WeMeetExtras
        @Composable
        @ReadOnlyComposable
        get() = LocalWeMeetExtras.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalWeMeetIsDark.current
}

@Composable
fun WeMeetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(
        LocalWeMeetExtras provides if (darkTheme) DarkExtras else LightExtras,
        LocalWeMeetIsDark provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = JusiTypography,
            content = content,
        )
    }
}
