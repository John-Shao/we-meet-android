package com.we.meet.ui.calendar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewWeek
import com.we.meet.ui.calendar.views.CalendarViewMode
import org.junit.Assert.assertSame
import org.junit.Test

class CalendarPrimaryToolbarTest {
    @Test
    fun managementIconFollowsViewMode() {
        assertSame(
            Icons.AutoMirrored.Filled.EventNote,
            calendarManagementIcon(CalendarViewMode.AGENDA),
        )
        assertSame(Icons.Filled.ViewDay, calendarManagementIcon(CalendarViewMode.DAY))
        assertSame(Icons.Filled.ViewWeek, calendarManagementIcon(CalendarViewMode.WEEK))
        assertSame(Icons.Filled.CalendarMonth, calendarManagementIcon(CalendarViewMode.MONTH))
    }
}
