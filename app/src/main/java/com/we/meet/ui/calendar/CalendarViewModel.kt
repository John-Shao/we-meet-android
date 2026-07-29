package com.we.meet.ui.calendar

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.data.api.dto.RescheduleEventRequest
import com.we.meet.ui.calendar.views.CalendarViewMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class CalendarUiState(
    val monthAnchor: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    /** P8:日程(默认)/日/周/月 四视图;日程视图窗口 = 锚点起一年(对齐
     * Web),其余视图恒焦点月 ±1 月。 */
    val viewMode: CalendarViewMode = CalendarViewMode.AGENDA,
    val eventsByDay: Map<LocalDate, List<EventUi>> = emptyMap(),
    val loading: Boolean = false,
    val error: Boolean = false,
    /** 我的 uuid(拉一次):日/周视图据此判定哪些块可长按拖动改期。 */
    val selfUserId: String? = null,
) {
    val selectedDayEvents: List<EventUi> get() = eventsByDay[selectedDate].orEmpty()
}

/**
 * 拖动改期失败的原因。[ROOM_CONFLICT] = 该日程订着的会议室在新时段被占
 * (core/api/calendar.py 的 `meeting_room_unavailable`,409);日程接口没有
 * 别的 409 语义,状态码本身就够判定,不必解析响应体。
 */
enum class MoveFailure { ROOM_CONFLICT, OTHER }

/**
 * 日历 tab VM. The API has no date-range filter, so we page the caller's full
 * event list (capped) and bucket client-side; month navigation is local.
 */
class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val apiClient = (app as WeMeetApp).apiClient
    private val calendarApi = apiClient.calendarApi

    private val _ui = MutableStateFlow(CalendarUiState(loading = true))
    val ui: StateFlow<CalendarUiState> = _ui.asStateFlow()

    /** 拖动改期失败 —— 屏幕订阅后按原因弹对应 Snackbar。 */
    private val _moveFailed = MutableSharedFlow<MoveFailure>(extraBufferCapacity = 1)
    val moveFailed: SharedFlow<MoveFailure> = _moveFailed.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            val me = runCatching { apiClient.userApi.getMe() }.getOrNull()?.id
            if (me != null) _ui.update { it.copy(selfUserId = me) }
        }
    }

    /**
     * 长按拖动改期:先乐观改本地(手感跟手),再 PATCH 起止;失败回滚并抛
     * [moveFailed]。只有 UI 判定 movable 的块(我组织的、非重复、单日内)
     * 会走到这里,后端还会再校验一次组织者身份。
     */
    fun moveEvent(eventId: String, date: LocalDate, startMin: Int, endMin: Int) {
        val zone = ZoneId.systemDefault()
        val newStart = date.atStartOfDay(zone).plusMinutes(startMin.toLong())
        val newEnd = date.atStartOfDay(zone).plusMinutes(endMin.toLong())
        val before = _ui.value.eventsByDay
        val moved = before.values.flatten().firstOrNull { it.id == eventId } ?: return
        if (moved.start == newStart && moved.end == newEnd) return

        // 乐观:只把这一条从旧桶摘掉、按新时刻放进新桶(其余桶原样保留 ——
        // 整表重建会用设备时区重算全天日程的覆盖日,可能整体错一天)。
        val optimistic = moved.copy(start = newStart, end = newEnd)
        val next = before.mapValues { (_, list) -> list.filter { it.id != eventId } }
            .toMutableMap()
        optimistic.coveredDates(zone).forEach { day ->
            next[day] = (next[day].orEmpty() + optimistic)
                .sortedWith(compareByDescending<EventUi> { it.allDay }.thenBy { it.start })
        }
        _ui.update { it.copy(eventsByDay = next) }

        viewModelScope.launch {
            runCatching {
                calendarApi.rescheduleEvent(
                    eventId,
                    RescheduleEventRequest(
                        startAt = DateTimeFormatter.ISO_INSTANT.format(newStart.toInstant()),
                        endAt = DateTimeFormatter.ISO_INSTANT.format(newEnd.toInstant()),
                    ),
                )
            }.onSuccess {
                // 后端可能顺带改了别的(会议室重订/系列约束),以服务端为准。
                refresh()
            }.onFailure { e ->
                Log.w(TAG, "reschedule failed", e)
                _ui.update { it.copy(eventsByDay = before) }
                _moveFailed.tryEmit(
                    if (e is HttpException && e.code() == 409) MoveFailure.ROOM_CONFLICT
                    else MoveFailure.OTHER,
                )
            }
        }
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
