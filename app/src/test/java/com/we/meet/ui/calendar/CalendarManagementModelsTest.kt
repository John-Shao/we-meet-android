package com.we.meet.ui.calendar

import com.we.meet.data.api.dto.CalendarCapabilitiesDto
import com.we.meet.data.api.dto.UnifiedCalendarDto
import com.we.meet.data.settings.CalendarDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarManagementModelsTest {
    @Test
    fun invalidCalendarColorsFallBackToDefault() {
        assertNull(parseCalendarColor(null))
        assertNull(parseCalendarColor("#123"))
        assertNull(parseCalendarColor("not-a-color"))
        assertNotNull(parseCalendarColor("#3370ff"))
        assertEquals(CALENDAR_COLOR_PALETTE.first(), validCalendarColorOrDefault("broken"))
    }

    @Test
    fun managedCalendarActionsFollowCapabilities() {
        val calendar = UnifiedCalendarDto(
            capabilities = CalendarCapabilitiesDto(
                canManage = true,
                canShare = true,
                canExport = true,
            ),
        )
        assertEquals(
            listOf(
                CalendarManagementAction.SHOW_ONLY,
                CalendarManagementAction.COLOR,
                CalendarManagementAction.SHARE,
                CalendarManagementAction.SETTINGS,
                CalendarManagementAction.EXPORT,
            ),
            actionsForCalendar(calendar),
        )
    }

    @Test
    fun subscribedCalendarCanBeRemovedButNotManaged() {
        val actions = actionsForCalendar(UnifiedCalendarDto(subscribed = true))
        assertEquals(
            listOf(
                CalendarManagementAction.SHOW_ONLY,
                CalendarManagementAction.COLOR,
                CalendarManagementAction.UNSUBSCRIBE,
            ),
            actions,
        )
    }

    @Test
    fun calendarsAreGroupedByManagementCapability() {
        val managed = UnifiedCalendarDto(
            id = "managed",
            capabilities = CalendarCapabilitiesDto(canManage = true),
        )
        val subscribed = UnifiedCalendarDto(id = "subscribed", subscribed = true)

        val groups = groupCalendars(listOf(subscribed, managed))

        assertEquals(listOf(managed), groups.managed)
        assertEquals(listOf(subscribed), groups.subscribed)
    }

    @Test
    fun calendarFormRejectsBlankNames() {
        assertEquals(false, isCalendarFormValid("  "))
        assertEquals(true, isCalendarFormValid(" Product launches "))
    }

    @Test
    fun discoverySearchUsesRequestedDebounce() {
        assertEquals(250L, CALENDAR_DISCOVER_DEBOUNCE_MS)
    }

    @Test
    fun discoveryUnsubscribeClearsSubscriptionAndVisibility() {
        val subscribed = UnifiedCalendarDto(
            id = "room-calendar",
            subscribed = true,
            enabled = true,
        )

        val unsubscribed = subscribed.withoutSubscription()

        assertEquals(false, unsubscribed.subscribed)
        assertEquals(false, unsubscribed.enabled)
    }

    @Test
    fun optimisticFailureRestoresSnapshotAndRequestsVisibleError() {
        val snapshot = listOf(UnifiedCalendarDto(id = "server", enabled = true))
        val optimistic = CalendarManagementUiState(
            calendars = listOf(UnifiedCalendarDto(id = "optimistic", enabled = false)),
            loading = false,
            busyIds = setOf("optimistic"),
        )

        val recovered = optimistic.afterOptimisticFailure(snapshot)

        assertEquals(snapshot, recovered.calendars)
        assertEquals(emptySet<String>(), recovered.busyIds)
        assertEquals(true, recovered.error)
    }

    @Test
    fun displayModeUsesThreeDayForUnknownStoredValue() {
        assertEquals(CalendarDisplayMode.MONTH, CalendarDisplayMode.fromKey("MONTH"))
        assertEquals(CalendarDisplayMode.MULTI_DAY, CalendarDisplayMode.fromKey("legacy"))
        assertEquals(CalendarDisplayMode.MULTI_DAY, CalendarDisplayMode.fromKey(null))
        CalendarDisplayMode.entries.forEach { mode ->
            assertEquals(mode, CalendarDisplayMode.fromKey(mode.name))
        }
    }
}
