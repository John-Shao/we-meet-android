package com.we.meet.ui.theme

import androidx.compose.ui.unit.dp

/**
 * App-wide spacing and sizing tokens.
 *
 * Unlike [WeMeetExtras] (which varies by light/dark and therefore lives in a
 * CompositionLocal), these values are theme-independent constants, so a plain
 * object is the simplest correct home. Prefer these over inline `.dp` literals
 * so button heights, screen padding, and the 8dp spacing grid stay consistent
 * across screens.
 */
object Dimens {

    // ---- Spacing (8dp grid; xs/xxs are the sanctioned sub-grid exceptions
    // for tight icon–text gaps) ----
    val SpaceXxs = 2.dp
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 12.dp
    val SpaceL = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp

    /**
     * Standard horizontal content inset (screen bodies, dialogs, sheets, list
     * rows). Prefer this over inline `horizontal = 16.dp`.
     */
    val ScreenPadding = 16.dp

    // ---- Controls ----
    /** Shared height for primary/full-width buttons across the app. */
    val ButtonHeight = 52.dp

    /** Google/Material minimum interactive target for accessibility. */
    val MinTouchTarget = 48.dp

    // ---- Icons ----
    val IconSmall = 18.dp
    val IconMedium = 24.dp
    val IconLarge = 28.dp
}
