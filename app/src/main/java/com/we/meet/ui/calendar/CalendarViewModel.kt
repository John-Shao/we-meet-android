package com.we.meet.ui.calendar

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.CalendarEventDto
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarUiState(
    val monthAnchor: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
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
            runCatching { fetchAll() }
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

    fun selectDate(date: LocalDate) {
        _ui.update { it.copy(selectedDate = date, monthAnchor = YearMonth.from(date)) }
    }

    fun goToMonth(month: YearMonth) {
        _ui.update { it.copy(monthAnchor = month) }
    }

    fun goToToday() {
        _ui.update {
            it.copy(selectedDate = LocalDate.now(), monthAnchor = YearMonth.now())
        }
    }

    private suspend fun fetchAll(): List<CalendarEventDto> {
        val all = mutableListOf<CalendarEventDto>()
        var page = 1
        while (page <= MAX_PAGES) {
            val res = calendarApi.listEvents(page = page)
            all += res.results
            if (res.next == null) break
            page++
        }
        if (page > MAX_PAGES) {
            // No date-range filter server-side yet; log the truncation instead
            // of silently pretending we covered everything.
            Log.w(TAG, "calendar list truncated at ${all.size} events")
        }
        return all
    }

    private companion object {
        const val TAG = "CalendarVM"
        const val MAX_PAGES = 5
    }
}
