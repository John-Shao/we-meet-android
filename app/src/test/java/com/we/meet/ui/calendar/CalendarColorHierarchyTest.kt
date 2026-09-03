package com.we.meet.ui.calendar

import com.we.meet.ui.theme.DarkCalendarEventContent
import com.we.meet.ui.theme.DarkCalendarEventSupportingContent
import com.we.meet.ui.theme.DarkCalendarGridBackground
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
            fillAlpha = 0.18f,
        )
    }

    @Test
    fun calendarPaletteProducesAccessibleDarkEventColors() {
        assertCalendarPalette(
            containerBase = DarkCalendarGridBackground,
            content = DarkCalendarEventContent,
            supportingContent = DarkCalendarEventSupportingContent,
            fillAlpha = 0.28f,
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

            assertTrue(
                "$value accent must reach 3:1 against its event container",
                colorContrastRatio(colors.accent, colors.container) >= 3f,
            )
            assertTrue(
                "$value title must reach 4.5:1 against its event container",
                colorContrastRatio(colors.content, colors.container) >= 4.5f,
            )
            assertTrue(
                "$value supporting text must reach 4.5:1 against its event container",
                colorContrastRatio(colors.supportingContent, colors.container) >= 4.5f,
            )
        }
    }
}
