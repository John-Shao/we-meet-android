package com.we.meet.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewWeek
import com.we.meet.data.settings.CalendarDisplayMode
import org.junit.Assert.assertSame
import org.junit.Test

class MainTabScreenTest {
    @Test
    fun calendarTabIconFollowsDisplayMode() {
        assertSame(
            Icons.AutoMirrored.Filled.EventNote,
            calendarTabIcon(CalendarDisplayMode.AGENDA),
        )
        assertSame(Icons.Filled.ViewDay, calendarTabIcon(CalendarDisplayMode.DAY))
        assertSame(Icons.Filled.ViewWeek, calendarTabIcon(CalendarDisplayMode.MULTI_DAY))
        assertSame(Icons.Filled.CalendarMonth, calendarTabIcon(CalendarDisplayMode.MONTH))
    }
}
