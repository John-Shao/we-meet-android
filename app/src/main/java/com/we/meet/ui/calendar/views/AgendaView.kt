package com.we.meet.ui.calendar.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日程视图(对齐 Web AgendaListView 重做):锚点日期起一年 [anchor, +1y)
 * 的平铺列表,按日期分组(「2026-07-23 周四」头,今天蓝色加粗锚点),只列
 * 有日程的日期;行内时间/标题/组织者复用 AgendaCard,点行进详情页(手机
 * 形态替代 Web 的右侧详情面板)。锚点由头部 ‹ 今天 › 按天调整或月历跳转。
 */
@Composable
fun AgendaView(
    anchorDate: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    onEventClick: (String) -> Unit,
    /** P8「降低已结束日程的亮度」:非空且日程已结束时整卡降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
) {
    val today = LocalDate.now()
    val windowEndExclusive = anchorDate.plusYears(1)

    val days = remember(anchorDate, eventsByDay) {
        eventsByDay.keys
            .filter { !it.isBefore(anchorDate) && it.isBefore(windowEndExclusive) }
            .filter { eventsByDay[it].orEmpty().isNotEmpty() }
            .sorted()
    }

    if (days.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.calendar_agenda_empty_today),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // 首帧定位到今天所在分组(锚点默认今天时即列表顶部;不在窗口则顶部)。
    // 扁平索引 = 之前各组的 1(日期头) + N(日程行)。
    LaunchedEffect(anchorDate, days) {
        if (days.none { it >= today }) return@LaunchedEffect
        var flat = 0
        for (d in days) {
            if (d >= today) break
            flat += 1 + eventsByDay[d].orEmpty().size
        }
        if (flat > 0) listState.scrollToItem(flat)
    }

    val dateFmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        days.forEach { date ->
            item(key = "h$date") {
                AgendaDateHeader(
                    date = date,
                    isToday = date == today,
                    dateFmt = dateFmt,
                )
            }
            items(eventsByDay[date].orEmpty(), key = { "$date-${it.id}" }) { event ->
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AgendaCard(
                        event = event,
                        onClick = { onEventClick(event.id) },
                        dimPastNow = dimPastNow,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** 日期分组头「2026-07-23 周四」;今天主色加粗作视觉锚点(对齐 Web)。 */
@Composable
private fun AgendaDateHeader(
    date: LocalDate,
    isToday: Boolean,
    dateFmt: DateTimeFormatter,
) {
    val color = if (isToday) {
        MaterialTheme.colorScheme.primary
    } else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Text(
            text = date.format(dateFmt),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            color = color,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
    }
}
