package com.we.meet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * Material 3 shape scale shared by every Compose feature.
 *
 * Values mirror `we-meet/src/design-tokens/shape.tokens.json`; one Web px maps
 * to one Android dp. Prefer MaterialTheme.shapes at component call sites so
 * geometry follows the same semantic scale across App and Web.
 */
val JusiShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.CornerXs),
    small = RoundedCornerShape(Dimens.CornerS),
    medium = RoundedCornerShape(Dimens.CornerM),
    large = RoundedCornerShape(Dimens.CornerL),
    extraLarge = RoundedCornerShape(Dimens.CornerXl),
)
