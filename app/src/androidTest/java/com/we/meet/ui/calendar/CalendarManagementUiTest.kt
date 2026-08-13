package com.we.meet.ui.calendar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.we.meet.ui.calendar.views.CalendarViewMode
import com.we.meet.ui.meetingroom.MeetingRoomWeekStrip
import com.we.meet.ui.theme.WeMeetTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarManagementUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun colorSheetReturnsSelectedPayload() {
        var selected: String? = null
        val target = CALENDAR_COLOR_PALETTE[3]
        composeRule.setContent {
            WeMeetTheme {
                CalendarColorSheet(
                    selected = CALENDAR_COLOR_PALETTE.first(),
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag("calendar-color-$target").performClick()
        composeRule.runOnIdle { assertEquals(target, selected) }
    }

    @Test
    fun homeToolbarKeepsPrimaryTabsAndManagementVisible() {
        var selected: CalendarPrimaryPage? = null
        composeRule.setContent {
            WeMeetTheme {
                CalendarPrimaryToolbar(
                    current = CalendarPrimaryPage.CALENDAR,
                    onSelect = { selected = it },
                    onOpenManagement = {},
                )
            }
        }

        composeRule.onNodeWithTag("calendar-primary-calendar").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar-primary-meeting_rooms").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("calendar-manage").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(CalendarPrimaryPage.MEETING_ROOMS, selected) }
    }

    @Test
    fun meetingRoomToolbarReplacesManagementWithCalendarSettings() {
        var settingsOpened = false
        composeRule.setContent {
            WeMeetTheme {
                CalendarPrimaryToolbar(
                    current = CalendarPrimaryPage.MEETING_ROOMS,
                    onSelect = {},
                    onOpenManagement = { settingsOpened = true },
                )
            }
        }

        composeRule.onNodeWithTag("calendar-manage").assertDoesNotExist()
        composeRule.onNodeWithTag("calendar-settings").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(true, settingsOpened) }
    }

    @Test
    fun calendarManagementIconFollowsViewMode() {
        val mode = mutableStateOf(CalendarViewMode.AGENDA)
        composeRule.setContent {
            WeMeetTheme {
                CalendarPrimaryToolbar(
                    current = CalendarPrimaryPage.CALENDAR,
                    calendarViewMode = mode.value,
                    onSelect = {},
                    onOpenManagement = {},
                )
            }
        }

        composeRule.onNodeWithTag("calendar-manage-icon-agenda").assertIsDisplayed()
        composeRule.runOnIdle { mode.value = CalendarViewMode.DAY }
        composeRule.onNodeWithTag("calendar-manage-icon-day").assertIsDisplayed()
        composeRule.runOnIdle { mode.value = CalendarViewMode.WEEK }
        composeRule.onNodeWithTag("calendar-manage-icon-week").assertIsDisplayed()
        composeRule.runOnIdle { mode.value = CalendarViewMode.MONTH }
        composeRule.onNodeWithTag("calendar-manage-icon-month").assertIsDisplayed()
    }

    @Test
    fun monthViewSwipeLeftRequestsNextMonth() {
        var monthDelta: Long? = null
        val date = LocalDate.of(2026, 8, 13)
        composeRule.setContent {
            WeMeetTheme {
                MonthViewBody(
                    ui = CalendarUiState(
                        selectedDate = date,
                        monthAnchor = YearMonth.from(date),
                        viewMode = CalendarViewMode.MONTH,
                        loading = false,
                    ),
                    firstDow = DayOfWeek.MONDAY,
                    dimPastNow = null,
                    today = date,
                    onSelect = {},
                    onEventClick = {},
                    onMonthSwipe = { monthDelta = it },
                )
            }
        }

        composeRule.onNodeWithTag("calendar-month-content").performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(1L, monthDelta) }
    }

    @Test
    fun meetingRoomWeekStripSwipesByWholeWeeks() {
        val selectedDate = LocalDate.of(2026, 8, 13)
        var changedDate: LocalDate? = null
        composeRule.setContent {
            WeMeetTheme {
                MeetingRoomWeekStrip(
                    selectedDate = selectedDate,
                    firstDayOfWeek = DayOfWeek.MONDAY,
                    onSelectDate = { changedDate = it },
                )
            }
        }

        composeRule.onNodeWithTag("meeting-room-week-strip")
            .performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(selectedDate.plusWeeks(1), changedDate) }

        composeRule.onNodeWithTag("meeting-room-week-strip")
            .performTouchInput { swipeRight() }
        composeRule.runOnIdle { assertEquals(selectedDate.minusWeeks(1), changedDate) }
    }
}
