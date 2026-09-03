package com.we.meet.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ColorContrastTest {

    @Test
    fun coreLightPairsMeetWcagAa() {
        assertTextPair("primary", LightOnPrimary, LightPrimary)
        assertTextPair("primary container", LightOnPrimaryContainer, LightPrimaryContainer)
        assertTextPair("secondary", LightOnSecondary, LightSecondary)
        assertTextPair("secondary container", LightOnSecondaryContainer, LightSecondaryContainer)
        assertTextPair("tertiary", LightOnTertiary, LightTertiary)
        assertTextPair("tertiary container", LightOnTertiaryContainer, LightTertiaryContainer)
        assertTextPair("surface", LightOnSurface, LightSurface)
        assertTextPair("background", LightOnSurface, LightBackground)
        assertTextPair("surface variant", LightOnSurfaceVariant, LightSurfaceVariant)
        assertTextPair("inverse surface", LightInverseOnSurface, LightInverseSurface)
        assertNonTextPair("outline", LightOutline, LightSurface)

        assertTextPair("danger", LightOnDanger, LightDanger)
        assertTextPair("danger container", LightOnDangerContainer, LightDangerContainer)
        assertTextPair("warning container", LightOnWarningContainer, LightWarningContainer)
        assertTextPair("success container", LightOnSuccessContainer, LightSuccessContainer)
        assertTextPair(
            "active container",
            LightOnAccentActiveContainer,
            LightAccentActiveContainer,
        )
        assertTextPair("neutral container", LightOnNeutralContainer, LightNeutralContainer)

        assertTextPair("RSVP accepted", LightRsvpAcceptedText, LightCalendarStatusBadgeContainer)
        assertTextPair("RSVP needs action", LightRsvpNeedsText, LightCalendarStatusBadgeContainer)
        assertTextPair("RSVP tentative", LightRsvpTentativeText, LightCalendarStatusBadgeContainer)
        assertTextPair("RSVP declined", LightRsvpDeclinedText, LightCalendarStatusBadgeContainer)
        assertTextPair("calendar event", LightCalendarEventContent, LightCalendarGridBackground)
        assertTextPair(
            "calendar event supporting",
            LightCalendarEventSupportingContent,
            LightCalendarGridBackground,
        )
        assertTextPair("out-of-month day", LightCalendarOutOfMonthDay, LightBackground)
        assertTextPair("reminder label", LightCalendarReminderText, Color(0xFFFCECDA))
        assertNonTextPair("reminder icon", CalendarOnReminder, LightCalendarReminder)
    }

    @Test
    fun coreDarkPairsMeetWcagAa() {
        assertTextPair("primary", DarkOnPrimary, DarkPrimary)
        assertTextPair("primary container", DarkOnPrimaryContainer, DarkPrimaryContainer)
        assertTextPair("secondary", DarkOnSecondary, DarkSecondary)
        assertTextPair("secondary container", DarkOnSecondaryContainer, DarkSecondaryContainer)
        assertTextPair("tertiary", DarkOnTertiary, DarkTertiary)
        assertTextPair("tertiary container", DarkOnTertiaryContainer, DarkTertiaryContainer)
        assertTextPair("surface", DarkOnSurface, DarkSurface)
        assertTextPair("background", DarkOnSurface, DarkBackground)
        assertTextPair("surface variant", DarkOnSurfaceVariant, DarkSurfaceVariant)
        assertTextPair("inverse surface", DarkInverseOnSurface, DarkInverseSurface)
        assertNonTextPair("outline", DarkOutline, DarkSurface)

        assertTextPair("danger", DarkOnDanger, DarkDanger)
        assertTextPair("danger container", DarkOnDangerContainer, DarkDangerContainer)
        assertTextPair("warning container", DarkOnWarningContainer, DarkWarningContainer)
        assertTextPair("success container", DarkOnSuccessContainer, DarkSuccessContainer)
        assertTextPair(
            "active container",
            DarkOnAccentActiveContainer,
            DarkAccentActiveContainer,
        )
        assertTextPair("neutral container", DarkOnNeutralContainer, DarkNeutralContainer)

        assertTextPair("RSVP accepted", DarkRsvpAcceptedText, DarkCalendarStatusBadgeContainer)
        assertTextPair("RSVP needs action", DarkRsvpNeedsText, DarkCalendarStatusBadgeContainer)
        assertTextPair("RSVP tentative", DarkRsvpTentativeText, DarkCalendarStatusBadgeContainer)
        assertTextPair("RSVP declined", DarkRsvpDeclinedText, DarkCalendarStatusBadgeContainer)
        assertTextPair("calendar event", DarkCalendarEventContent, DarkCalendarGridBackground)
        assertTextPair(
            "calendar event supporting",
            DarkCalendarEventSupportingContent,
            DarkCalendarGridBackground,
        )
        assertTextPair("out-of-month day", DarkCalendarOutOfMonthDay, DarkBackground)
        assertNonTextPair("reminder icon", CalendarOnReminder, DarkCalendarReminder)
    }

    @Test
    fun materialSchemesDoNotFallBackToMaterialDefaults() {
        assertEquals(LightSecondary, LightColors.secondary)
        assertEquals(LightTertiary, LightColors.tertiary)
        assertEquals(LightOnSurfaceVariant, LightColors.onSurfaceVariant)
        assertEquals(LightOutline, LightColors.outline)
        assertEquals(LightDanger, LightColors.error)
        assertEquals(LightInverseSurface, LightColors.inverseSurface)
        assertEquals(LightSurfaceContainerLow, LightColors.surfaceContainerLow)

        assertEquals(DarkSecondary, DarkColors.secondary)
        assertEquals(DarkTertiary, DarkColors.tertiary)
        assertEquals(DarkOnSurfaceVariant, DarkColors.onSurfaceVariant)
        assertEquals(DarkOutline, DarkColors.outline)
        assertEquals(DarkDanger, DarkColors.error)
        assertEquals(DarkInverseSurface, DarkColors.inverseSurface)
        assertEquals(DarkSurfaceContainerLow, DarkColors.surfaceContainerLow)
    }

    @Test
    fun calendarSurfacesExposeVisibleHierarchy() {
        assertNotEquals(LightCalendarGridBackground, LightCalendarNonWorkingSurface)
        assertNotEquals(LightCalendarGridBackground, LightCalendarUnavailableSurface)
        assertNotEquals(LightCalendarGridBackground, LightCalendarFocusedDaySurface)
        assertNotEquals(LightCalendarNonWorkingSurface, LightCalendarUnavailableSurface)
        assertNotEquals(LightCalendarUnavailableSurface, LightCalendarBusyContainer)
        assertNotEquals(DarkCalendarGridBackground, DarkCalendarNonWorkingSurface)
        assertNotEquals(DarkCalendarGridBackground, DarkCalendarUnavailableSurface)
        assertNotEquals(DarkCalendarGridBackground, DarkCalendarFocusedDaySurface)
        assertNotEquals(DarkCalendarNonWorkingSurface, DarkCalendarUnavailableSurface)
        assertNotEquals(DarkCalendarUnavailableSurface, DarkCalendarBusyContainer)
    }

    private fun assertTextPair(name: String, foreground: Color, background: Color) {
        assertContrast(name, foreground, background, minimum = 4.5)
    }

    private fun assertNonTextPair(name: String, foreground: Color, background: Color) {
        assertContrast(name, foreground, background, minimum = 3.0)
    }

    private fun assertContrast(
        name: String,
        foreground: Color,
        background: Color,
        minimum: Double,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$name contrast is %.2f:1; expected at least %.1f:1".format(ratio, minimum),
            ratio >= minimum,
        )
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = first.relativeLuminance()
        val secondLuminance = second.relativeLuminance()
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun Color.relativeLuminance(): Double =
        0.2126 * red.toDouble().linearize() +
            0.7152 * green.toDouble().linearize() +
            0.0722 * blue.toDouble().linearize()

    private fun Double.linearize(): Double =
        if (this <= 0.04045) this / 12.92 else ((this + 0.055) / 1.055).pow(2.4)
}
