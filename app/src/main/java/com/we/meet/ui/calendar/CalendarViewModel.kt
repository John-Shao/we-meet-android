package com.we.meet.ui.calendar

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.ui.calendar.views.CalendarViewMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarUiState(
    val monthAnchor: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    /** P8:日程(默认)/日/周/月 四视图;日程视图窗口 = 锚点起一年(对齐
     * Web),其余视图恒焦点月 ±1 月。 */
    val viewMode: CalendarViewMode = CalendarViewMode.AGENDA,
    val eventsByDay: Map<LocalDate, List<EventUi>> = emptyMap(),
    val loading: Boolean = false,
    val error: Boolean = false,
) {
    val selectedDayEvents: List<EventUi> get() = eventsByDay[selectedDate].orEmpty()
}

/**
 * 日历 tab VM. The API has no date-range filter, so we page the caller's full
 * event list (capped) and bucket client-side; month navigation is local.
 */
class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val calendarApi = (app as WeMeetApp).apiClient.calendarApi

    private val _ui = MutableStateFlow(CalendarUiState(loading = true))
    val ui: StateFlow<CalendarUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = it.eventsByDay.isEmpty(), error = false) }
            runCatching { fetchWindow(_ui.value) }
                .onSuccess { events ->
                    val parsed = events.mapNotNull { it.toParsed() }
                    _ui.update {
                        it.copy(eventsByDay = bucketByDay(parsed), loading = false)
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "calendar load failed", e)
                    _ui.update { it.copy(loading = false, error = true) }
                }
        }
    }

    fun setViewMode(mode: CalendarViewMode) {
        val old = _ui.value.viewMode
        _ui.update { it.copy(viewMode = mode) }
        // 日程视图窗口(一年)与其余视图(±1 月)不同,进出时重取。
        if ((mode == CalendarViewMode.AGENDA) != (old == CalendarViewMode.AGENDA)) refresh()
    }

    fun selectDate(date: LocalDate) {
        val month = YearMonth.from(date)
        val monthChanged = month != _ui.value.monthAnchor
        val dateChanged = date != _ui.value.selectedDate
        _ui.update { it.copy(selectedDate = date, monthAnchor = month) }
        // 日程视图窗口锚定在 selectedDate,改日期即改窗口。
        val windowMoved =
            if (_ui.value.viewMode == CalendarViewMode.AGENDA) dateChanged else monthChanged
        if (windowMoved) refresh()
    }

    fun goToMonth(month: YearMonth) {
        if (month == _ui.value.monthAnchor) return
        _ui.update { it.copy(monthAnchor = month) }
        refresh()
    }

    fun goToToday() {
        _ui.update {
            it.copy(selectedDate = LocalDate.now(), monthAnchor = YearMonth.now())
        }
        refresh()
    }

    /** Fetch only the events overlapping the visible window, server-side
     *  (?start&end):日程视图 = 锚点日期起一年(对齐 Web),其余 = 焦点月 ±1。 */
    private suspend fun fetchWindow(state: CalendarUiState): List<CalendarEventDto> {
        val zone = ZoneId.systemDefault()
        val agenda = state.viewMode == CalendarViewMode.AGENDA
        val start =
            if (agenda) state.selectedDate.atStartOfDay(zone)
            else state.monthAnchor.minusMonths(1).atDay(1).atStartOfDay(zone)
        val end =
            if (agenda) state.selectedDate.plusYears(1).atStartOfDay(zone)
            else state.monthAnchor.plusMonths(2).atDay(1).atStartOfDay(zone)
        val startIso = DateTimeFormatter.ISO_INSTANT.format(start.toInstant())
        val endIso = DateTimeFormatter.ISO_INSTANT.format(end.toInstant())
        val all = mutableListOf<CalendarEventDto>()
        var page = 1
        val maxPages = if (agenda) MAX_PAGES_AGENDA else MAX_PAGES
        while (page <= maxPages) {
            val res = calendarApi.listEvents(page = page, start = startIso, end = endIso)
            all += res.results
            if (res.next == null) break
            page++
        }
        return all
    }

    private companion object {
        const val TAG = "CalendarVM"
        const val MAX_PAGES = 5
        /** 一年窗口的翻页上限放宽一档。 */
        const val MAX_PAGES_AGENDA = 10
    }
}
