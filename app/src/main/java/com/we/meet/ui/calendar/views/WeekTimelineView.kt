package com.we.meet.ui.calendar.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.ui.calendar.EventUi
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * P8 周视图(飞书「三日」按团队约定改为 7 列周):顶部星期条(今日高亮圆点,
 * 点某天切到该日)+ 7 列时间轴,红线只画在今天列。翻周走 header 的 ‹ ›
 * (±7 天),不引入 Pager(见 P8 设计:降低状态同步面)。
 */
@Composable
fun WeekTimelineView(
    anchorDate: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    onEventClick: (String) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onSlotTap: (date: LocalDate, minuteOfDay: Int) -> Unit,
    /** P8 日历设置:每周的第一天(默认周一,保持既有行为)。 */
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    /** P8「降低已结束日程的亮度」:非空时,结束早于该时刻的块降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
) {
    val today = LocalDate.now()
    // 从 anchor 往前找最近的 firstDayOfWeek(含自身)作为本周第一列。
    val weekStart = anchorDate.minusDays(
        ((anchorDate.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong(),
    )
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    val columns = remember(days, eventsByDay, dimPastNow) {
        days.map { date ->
            eventsByDay[date].orEmpty().mapNotNull { it.toTimeBlockOrNull(date, dimPastNow) }
        }
    }

    val hourHeight = 56.dp
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(weekStart) {
        scrollState.scrollTo(with(density) { (hourHeight * 8).toPx() }.toInt())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 星期条:与时间轴同布局(刻度列宽的占位 + 7 等分)。
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.width(HOUR_RAIL_WIDTH))
            days.forEach { date ->
                val isToday = date == today
                val isAnchor = date == anchorDate
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDayClick(date) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.primary
                                    isAnchor -> MaterialTheme.colorScheme.primaryContainer
                                    else -> androidx.compose.ui.graphics.Color.Transparent
                                },
                                shape = CircleShape,
                            ),
                    ) {
                        Text(
                            text = date.dayOfMonth.toString(),
                            fontSize = 12.sp,
                            color = when {
                                isToday -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
        TimelineScaffold(
            columns = columns,
            hourHeight = hourHeight,
            scrollState = scrollState,
            nowMinute = if (days.contains(today)) {
                LocalTime.now().let { it.hour * 60 + it.minute }
            } else null,
            nowLineInColumn = { i -> days[i] == today },
            onBlockTap = { _, key -> onEventClick(key) },
            onSlotTap = { col, minute -> onSlotTap(days[col], minute) },
            // 7 列过窄,块内只显标题(时刻由位置 + 左侧刻度读取,点块看详情)。
            compactBlocks = true,
        )
    }
}
