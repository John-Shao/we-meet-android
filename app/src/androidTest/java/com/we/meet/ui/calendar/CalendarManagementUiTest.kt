package com.we.meet.ui.calendar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.settings.CalendarDisplayMode
import com.we.meet.ui.calendar.views.CalendarDateCell
import com.we.meet.ui.calendar.views.CalendarViewMode
import com.we.meet.ui.calendar.views.calendarWeekPageTestTag
import com.we.meet.ui.meetingroom.MeetingRoomWeekStrip
import com.we.meet.ui.theme.WeMeetTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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

        composeRule.onNodeWithTag("calendar-manage-icon-agenda", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { mode.value = CalendarViewMode.DAY }
        composeRule.onNodeWithTag("calendar-manage-icon-day", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { mode.value = CalendarViewMode.WEEK }
        composeRule.onNodeWithTag("calendar-manage-icon-week", useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { mode.value = CalendarViewMode.MONTH }
        composeRule.onNodeWithTag("calendar-manage-icon-month", useUnmergedTree = true).assertExists()
    }

    @Test
    fun managementViewModesExposeSelectedTabSemantics() {
        val mode = mutableStateOf(CalendarDisplayMode.DAY)
        composeRule.setContent {
            WeMeetTheme {
                CalendarModeStrip(
                    current = mode.value,
                    onSelect = { mode.value = it },
                )
            }
        }

        composeRule.onNodeWithTag("calendar-mode-DAY").assertIsSelected()
        composeRule.onNodeWithTag("calendar-mode-MONTH").assertIsNotSelected().performClick()
        composeRule.onNodeWithTag("calendar-mode-MONTH").assertIsSelected()
    }

    @Test
    fun dateCellAnnouncesFullDateTodayAndEvents() {
        val date = LocalDate.of(2026, 8, 14)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localizedDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
            .withLocale(Locale.getDefault())
            .format(date)
        val expectedDescription = listOf(
            localizedDate,
            context.getString(R.string.calendar_today),
            context.getString(R.string.calendar_has_events),
        ).joinToString(", ")

        composeRule.setContent {
            WeMeetTheme {
                CalendarDateCell(
                    date = date,
                    selected = true,
                    isToday = true,
                    indicatorColor = Color.Blue,
                    onClick = {},
                    modifier = Modifier.testTag("accessible-calendar-date"),
                )
            }
        }

        composeRule.onNodeWithTag("accessible-calendar-date")
            .assertIsSelected()
            .assertContentDescriptionEquals(expectedDescription)
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

        composeRule.onNodeWithTag(monthPageTestTag(YearMonth.of(2026, 8)))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(calendarMonthDateCellTestTag(date)).assertIsSelected()
        composeRule.onNodeWithTag("calendar-month-content").performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(1L, monthDelta) }
        composeRule.onNodeWithTag(monthPageTestTag(YearMonth.of(2026, 9)))
            .assertIsDisplayed()

        composeRule.runOnIdle { monthDelta = null }
        composeRule.onNodeWithTag("calendar-month-content").performTouchInput { swipeRight() }
        composeRule.runOnIdle { assertEquals(-1L, monthDelta) }
        composeRule.onNodeWithTag(monthPageTestTag(YearMonth.of(2026, 8)))
            .assertIsDisplayed()
    }

    @Test
    fun meetingRoomWeekStripSwipesByWholeWeeks() {
        val selectedDate = LocalDate.of(2026, 8, 13)
        val currentDate = mutableStateOf(selectedDate)
        var changedDate: LocalDate? = null
        composeRule.setContent {
            WeMeetTheme {
                MeetingRoomWeekStrip(
                    selectedDate = currentDate.value,
                    firstDayOfWeek = DayOfWeek.MONDAY,
                    onSelectDate = {
                        changedDate = it
                        currentDate.value = it
                    },
                )
            }
        }

        composeRule.onNodeWithTag(calendarWeekPageTestTag(selectedDate)).assertIsDisplayed()
        composeRule.onNodeWithTag("meeting-room-week-strip")
            .performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(selectedDate.plusWeeks(1), changedDate) }
        composeRule.onNodeWithTag(calendarWeekPageTestTag(selectedDate.plusWeeks(1)))
            .assertIsDisplayed()

        composeRule.onNodeWithTag("meeting-room-week-strip")
            .performTouchInput { swipeRight() }
        composeRule.runOnIdle { assertEquals(selectedDate, changedDate) }
        composeRule.onNodeWithTag(calendarWeekPageTestTag(selectedDate)).assertIsDisplayed()
    }
}
