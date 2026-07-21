package com.we.meet.ui.calendar.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.ui.calendar.AgendaCard
import com.we.meet.ui.calendar.EventUi
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** 列表行模型:周分隔 sticky 头 / 某日(含当日红线与空态) / 头尾翻月钮。 */
private sealed interface AgendaRow {
    data class WeekHeader(val weekNo: Int, val from: LocalDate, val to: LocalDate) : AgendaRow
    data class Day(val date: LocalDate, val events: List<EventUi>) : AgendaRow
}

/**
 * P8 日程视图(默认视图,对标飞书):按周分组的列表流,「第N周」sticky 分隔,
 * 今天恒在(无日程显示空态)且日期头下带当前红线,首帧定位到今天。窗口 =
 * 焦点月 ±1 月(与取数窗口一致),头尾按钮翻月 —— 刻意不做无限流(整窗替换
 * 会抖动,见 P8 设计)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgendaView(
    monthAnchor: YearMonth,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    onEventClick: (String) -> Unit,
    onLoadPrev: () -> Unit,
    onLoadNext: () -> Unit,
    /** P8「降低已结束日程的亮度」:非空且日程已结束时整卡降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
) {
    val today = LocalDate.now()
    val windowStart = monthAnchor.minusMonths(1).atDay(1)
    val windowEndExclusive = monthAnchor.plusMonths(2).atDay(1)

    val rows = remember(monthAnchor, eventsByDay) {
        val days = (eventsByDay.keys.filter {
            !it.isBefore(windowStart) && it.isBefore(windowEndExclusive)
        } + listOfNotNull(today.takeIf {
            !it.isBefore(windowStart) && it.isBefore(windowEndExclusive)
        }))
            .distinct()
            .sorted()
        val out = mutableListOf<AgendaRow>()
        var lastWeekStart: LocalDate? = null
        val weekFields = WeekFields.ISO
        days.forEach { date ->
            val weekStart = date.with(DayOfWeek.MONDAY)
            if (weekStart != lastWeekStart) {
                out += AgendaRow.WeekHeader(
                    weekNo = date.get(weekFields.weekOfWeekBasedYear()),
                    from = weekStart,
                    to = weekStart.plusDays(6),
                )
                lastWeekStart = weekStart
            }
            out += AgendaRow.Day(date, eventsByDay[date].orEmpty())
        }
        out
    }

    val listState = rememberLazyListState()
    // 首帧 / 换窗后定位到今天(不在窗口则顶部)。+1 是头部「加载上一月」项。
    LaunchedEffect(monthAnchor, rows.size) {
        val idx = rows.indexOfFirst { it is AgendaRow.Day && it.date >= today }
        if (idx >= 0) listState.scrollToItem(idx + 1)
    }

    val monthDayFmt = remember { DateTimeFormatter.ofPattern("M/d") }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "load-prev") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onLoadPrev) {
                    Text(stringResource(R.string.calendar_agenda_load_prev))
                }
            }
        }
        rows.forEach { row ->
            when (row) {
                is AgendaRow.WeekHeader -> stickyHeader(key = "w${row.from}") {
                    Text(
                        text = stringResource(
                            R.string.calendar_week_range,
                            row.weekNo,
                            row.from.format(monthDayFmt),
                            row.to.format(monthDayFmt),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                is AgendaRow.Day -> item(key = "d${row.date}") {
                    AgendaDayBlock(
                        date = row.date,
                        events = row.events,
                        isToday = row.date == today,
                        onEventClick = onEventClick,
                        dimPastNow = dimPastNow,
                    )
                }
            }
        }
        item(key = "load-next") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 88.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = onLoadNext) {
                    Text(stringResource(R.string.calendar_agenda_load_next))
                }
            }
        }
    }
}

@Composable
private fun AgendaDayBlock(
    date: LocalDate,
    events: List<EventUi>,
    isToday: Boolean,
    onEventClick: (String) -> Unit,
    dimPastNow: java.time.ZonedDateTime?,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isToday) NowLineColor else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = if (isToday) NowLineColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.calendar_agenda_empty_today),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Spacer(Modifier.height(6.dp))
            events.forEach { event ->
                AgendaCard(
                    event = event,
                    onClick = { onEventClick(event.id) },
                    dimPastNow = dimPastNow,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        // 当天红线(飞书样式:左端圆点 + 横线,置于当日区块之下)。
        if (isToday) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(NowLineColor, CircleShape),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(NowLineColor),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
