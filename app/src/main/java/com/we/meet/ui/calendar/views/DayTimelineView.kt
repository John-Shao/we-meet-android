package com.we.meet.ui.calendar.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.ui.calendar.EventUi
import java.time.LocalDate
import java.time.LocalTime

/**
 * P8 日视图:全天条(chips)+ 单列时间轴。点日程块进详情,点空白按该时刻
 * 建日程(30min 吸附由调用方处理)。
 */
@Composable
fun DayTimelineView(
    date: LocalDate,
    events: List<EventUi>,
    onEventClick: (String) -> Unit,
    onSlotTap: (minuteOfDay: Int) -> Unit,
) {
    val blocks = remember(date, events) {
        events.mapNotNull { it.toTimeBlockOrNull(date) }
    }
    val allDayEvents = remember(date, events) { events.filter { it.allDay } }
    val isToday = date == LocalDate.now()

    val hourHeight = 56.dp
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // 首帧滚到 08:00(今天则当前时刻上方一点)。
    LaunchedEffect(date) {
        val anchorHour = if (isToday) {
            (LocalTime.now().hour - 1).coerceAtLeast(0)
        } else 8
        scrollState.scrollTo(with(density) { (hourHeight * anchorHour).toPx() }.toInt())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (allDayEvents.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HOUR_RAIL_WIDTH, end = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    allDayEvents.forEach { event ->
                        Text(
                            text = "${stringResource(R.string.calendar_all_day)} · ${event.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { onEventClick(event.id) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(0.dp))
        TimelineScaffold(
            columns = listOf(blocks),
            hourHeight = hourHeight,
            scrollState = scrollState,
            nowMinute = if (isToday) LocalTime.now().let { it.hour * 60 + it.minute } else null,
            onBlockTap = { _, key -> onEventClick(key) },
            onSlotTap = { _, minute -> onSlotTap(minute) },
        )
    }
}
