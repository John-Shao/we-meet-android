package com.we.meet.ui.calendar

import androidx.compose.ui.graphics.compositeOver
import com.we.meet.ui.theme.DarkCalendarEventContent
import com.we.meet.ui.theme.DarkCalendarEventSupportingContent
import com.we.meet.ui.theme.DarkSurfaceContainerLow
import com.we.meet.ui.theme.LightCalendarEventContent
import com.we.meet.ui.theme.LightCalendarEventSupportingContent
import com.we.meet.ui.theme.LightCalendarGridBackground
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarColorHierarchyTest {

    @Test
    fun calendarPaletteProducesAccessibleLightEventColors() {
        assertCalendarPalette(
            containerBase = LightCalendarGridBackground,
            content = LightCalendarEventContent,
            supportingContent = LightCalendarEventSupportingContent,
            fillAlpha = 0.12f,
        )
    }

    @Test
    fun calendarPaletteProducesAccessibleDarkEventColors() {
        assertCalendarPalette(
            containerBase = DarkSurfaceContainerLow,
            content = DarkCalendarEventContent,
            supportingContent = DarkCalendarEventSupportingContent,
            fillAlpha = 0.22f,
        )
    }

    private fun assertCalendarPalette(
        containerBase: androidx.compose.ui.graphics.Color,
        content: androidx.compose.ui.graphics.Color,
        supportingContent: androidx.compose.ui.graphics.Color,
        fillAlpha: Float,
    ) {
        CALENDAR_COLOR_PALETTE.forEach { value ->
            val raw = requireNotNull(parseCalendarColor(value))
            val colors = resolveCalendarEventColors(
                rawAccent = raw,
                containerBase = containerBase,
                content = content,
                supportingContent = supportingContent,
                fillAlpha = fillAlpha,
            )
            val resolvedContainer = colors.container.compositeOver(containerBase)
            val resolvedPastContainer = colors.pastContainer.compositeOver(containerBase)

            assertTrue(
                "$value accent must reach 3:1 against its event container",
                colorContrastRatio(colors.accent, resolvedContainer) >= 3f,
            )
            assertTrue(
                "$value title must reach 4.5:1 against its event container",
                colorContrastRatio(colors.content, resolvedContainer) >= 4.5f,
            )
            assertTrue(
                "$value supporting text must reach 4.5:1 against its event container",
                colorContrastRatio(colors.supportingContent, resolvedContainer) >= 4.5f,
            )
            assertTrue(
                "$value past event must use a lighter translucent tint",
                colors.pastContainer.alpha < colors.container.alpha,
            )
            assertTrue(
                "$value past event tint must remain visible against the grid",
                resolvedPastContainer != containerBase,
            )
        }
    }
}
