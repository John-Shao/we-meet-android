package com.we.meet.ui.theme

import androidx.compose.ui.graphics.Color
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
        assertTextPair("surface", LightOnSurface, LightSurface)
        assertTextPair("background", LightOnSurface, LightBackground)

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

        assertTextPair("RSVP accepted", LightRsvpAcceptedText, LightSurface)
        assertTextPair("RSVP needs action", LightRsvpNeedsText, LightSurface)
        assertTextPair("RSVP tentative", LightRsvpTentativeText, LightSurface)
        assertTextPair("RSVP declined", LightRsvpDeclinedText, LightSurface)
        assertTextPair("out-of-month day", LightCalendarOutOfMonthDay, LightBackground)
        assertTextPair("reminder label", LightCalendarReminderText, Color(0xFFFCECDA))
        assertNonTextPair("reminder icon", CalendarOnReminder, LightCalendarReminder)
    }

    @Test
    fun coreDarkPairsMeetWcagAa() {
        assertTextPair("primary", DarkOnPrimary, DarkPrimary)
        assertTextPair("primary container", DarkOnPrimaryContainer, DarkPrimaryContainer)
        assertTextPair("surface", DarkOnSurface, DarkSurface)
        assertTextPair("background", DarkOnSurface, DarkBackground)

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

        assertTextPair("RSVP accepted", DarkRsvpAcceptedText, DarkSurface)
        assertTextPair("RSVP needs action", DarkRsvpNeedsText, DarkSurface)
        assertTextPair("RSVP tentative", DarkRsvpTentativeText, DarkSurface)
        assertTextPair("RSVP declined", DarkRsvpDeclinedText, DarkSurface)
        assertTextPair("out-of-month day", DarkCalendarOutOfMonthDay, DarkBackground)
        assertNonTextPair("reminder icon", CalendarOnReminder, DarkCalendarReminder)
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
