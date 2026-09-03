package com.we.meet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationTokensTest {

    private data class ExpectedTypeStyle(
        val style: TextStyle,
        val fontSize: Float,
        val lineHeight: Float,
        val fontWeight: FontWeight,
        val letterSpacing: Float,
    )

    @Test
    fun spacingScaleMatchesCrossClientContract() {
        assertEquals(0f, Dimens.SpaceNone.value)
        assertEquals(2f, Dimens.SpaceXxs.value)
        assertEquals(4f, Dimens.SpaceXs.value)
        assertEquals(8f, Dimens.SpaceS.value)
        assertEquals(12f, Dimens.SpaceM.value)
        assertEquals(16f, Dimens.SpaceL.value)
        assertEquals(24f, Dimens.SpaceXl.value)
        assertEquals(32f, Dimens.SpaceXxl.value)
        assertEquals(48f, Dimens.SpaceXxxl.value)
        assertEquals(64f, Dimens.SpaceXxxxl.value)
    }

    @Test
    fun shapeAndElevationScalesMatchCrossClientContract() {
        assertEquals(4f, Dimens.CornerXs.value)
        assertEquals(8f, Dimens.CornerS.value)
        assertEquals(12f, Dimens.CornerM.value)
        assertEquals(16f, Dimens.CornerL.value)
        assertEquals(24f, Dimens.CornerXl.value)

        assertEquals(RoundedCornerShape(4.dp), JusiShapes.extraSmall)
        assertEquals(RoundedCornerShape(8.dp), JusiShapes.small)
        assertEquals(RoundedCornerShape(12.dp), JusiShapes.medium)
        assertEquals(RoundedCornerShape(16.dp), JusiShapes.large)
        assertEquals(RoundedCornerShape(24.dp), JusiShapes.extraLarge)

        assertEquals(0f, Dimens.ElevationFlat.value)
        assertEquals(1f, Dimens.ElevationSubtle.value)
        assertEquals(3f, Dimens.ElevationRaised.value)
        assertEquals(6f, Dimens.ElevationOverlay.value)
        assertEquals(8f, Dimens.ElevationSticky.value)
        assertEquals(12f, Dimens.ElevationModal.value)
    }

    @Test
    fun typographyScaleMatchesMaterial3Contract() {
        val expected = listOf(
            ExpectedTypeStyle(JusiTypography.displayLarge, 57f, 64f, FontWeight.Normal, -0.25f),
            ExpectedTypeStyle(JusiTypography.displayMedium, 45f, 52f, FontWeight.Normal, 0f),
            ExpectedTypeStyle(JusiTypography.displaySmall, 36f, 44f, FontWeight.Normal, 0f),
            ExpectedTypeStyle(JusiTypography.headlineLarge, 32f, 40f, FontWeight.Normal, 0f),
            ExpectedTypeStyle(JusiTypography.headlineMedium, 28f, 36f, FontWeight.Normal, 0f),
            ExpectedTypeStyle(JusiTypography.headlineSmall, 24f, 32f, FontWeight.Normal, 0f),
            ExpectedTypeStyle(JusiTypography.titleLarge, 22f, 28f, FontWeight.Medium, 0f),
            ExpectedTypeStyle(JusiTypography.titleMedium, 16f, 24f, FontWeight.Medium, 0.15f),
            ExpectedTypeStyle(JusiTypography.titleSmall, 14f, 20f, FontWeight.Medium, 0.1f),
            ExpectedTypeStyle(JusiTypography.bodyLarge, 16f, 24f, FontWeight.Normal, 0.5f),
            ExpectedTypeStyle(JusiTypography.bodyMedium, 14f, 20f, FontWeight.Normal, 0.25f),
            ExpectedTypeStyle(JusiTypography.bodySmall, 12f, 16f, FontWeight.Normal, 0.4f),
            ExpectedTypeStyle(JusiTypography.labelLarge, 14f, 20f, FontWeight.Medium, 0.1f),
            ExpectedTypeStyle(JusiTypography.labelMedium, 12f, 16f, FontWeight.Medium, 0.5f),
            ExpectedTypeStyle(JusiTypography.labelSmall, 11f, 16f, FontWeight.Medium, 0.5f),
        )

        expected.forEach { expectedStyle ->
            assertEquals(expectedStyle.fontSize, expectedStyle.style.fontSize.value)
            assertEquals(expectedStyle.lineHeight, expectedStyle.style.lineHeight.value)
            assertEquals(expectedStyle.fontWeight, expectedStyle.style.fontWeight)
            assertEquals(expectedStyle.letterSpacing, expectedStyle.style.letterSpacing.value)
        }
    }
}
