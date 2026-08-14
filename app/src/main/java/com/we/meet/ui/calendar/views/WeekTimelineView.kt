package com.we.meet.ui.calendar.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTextStyles
import com.we.meet.ui.calendar.EventUi
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日视图顶部日期条使用的完整周。从 anchor 往前找最近的 [firstDayOfWeek]
 * 作本周首日并铺满 7 天；周末始终包含在内。
 */
fun weekColumnDays(
    anchorDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
): List<LocalDate> {
    val weekStart = anchorDate.minusDays(
        ((anchorDate.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong(),
    )
    return (0..6).map { weekStart.plusDays(it.toLong()) }
}

const val THREE_DAY_VIEW_DAYS = 3
const val THREE_DAY_VIEW_TEST_TAG = "calendar-three-day-view"

fun threeDayHeaderTestTag(date: LocalDate): String = "calendar-three-day-header-$date"

/** Three consecutive calendar days starting at the selected date. */
fun threeDayColumnDays(anchorDate: LocalDate): List<LocalDate> =
    (0 until THREE_DAY_VIEW_DAYS).map { anchorDate.plusDays(it.toLong()) }

/**
 * 固定三日视图：从选中日期起连续显示三天，周末不跳过。三列恰好铺满屏幕，
 * 不产生水平滚动范围；时间轴只保留纵向滚动。
 */
@Composable
fun ThreeDayTimelineView(
    anchorDate: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    onEventClick: (String) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onSlotTap: (date: LocalDate, minuteOfDay: Int) -> Unit,
    visibleStartMin: Int = 0,
    visibleEndMin: Int = 24 * 60,
    workingStartMin: Int = 9 * 60,
    workingEndMin: Int = 18 * 60,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** P8「降低已结束日程的亮度」:非空时,结束早于该时刻的块降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
    /** 当前预选时段(只在它所属日期落在当前三列里时才画)。 */
    draft: DraftSlot? = null,
    draftLabel: String? = null,
    onDraftAdjust: ((DraftSlot) -> Unit)? = null,
    onDraftConfirm: ((DraftSlot) -> Unit)? = null,
    /** 我的 uuid:我组织的非重复日程可长按拖动改期(null = 全都不可拖)。 */
    selfUserId: String? = null,
    /** 改期落点(整块移位 / 拖抓手改时长);横向跨列 = 改到那一天。 */
    onEventMove: ((eventId: String, date: LocalDate, startMin: Int, endMin: Int) -> Unit)? = null,
    /** 长按选中的日程 id:出上下抓手,可直接拖移 / 改时长。 */
    selectedEventId: String? = null,
    onEventSelect: ((eventId: String) -> Unit)? = null,
    /** 点左侧时刻刻度列 = 点在操作对象以外 → 收手。 */
    onRailTap: (() -> Unit)? = null,
) {
    val today = LocalDate.now(zoneId)
    val days = remember(anchorDate) {
        threeDayColumnDays(anchorDate)
    }
    val columns = remember(days, eventsByDay, dimPastNow, selfUserId) {
        days.map { date ->
            eventsByDay[date].orEmpty()
                .mapNotNull { it.toTimeBlockOrNull(date, dimPastNow, selfUserId) }
        }
    }

    val hourHeight = Dimens.Calendar.HourHeight
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(days.first(), visibleStartMin, visibleEndMin) {
        val offset = (8 * 60).coerceIn(visibleStartMin, visibleEndMin) - visibleStartMin
        scrollState.scrollTo(with(density) { (hourHeight * (offset / 60f)).toPx() }.toInt())
    }
    TimelineScaffold(
        modifier = Modifier.fillMaxSize().testTag(THREE_DAY_VIEW_TEST_TAG),
        columns = columns,
        hourHeight = hourHeight,
        scrollState = scrollState,
        visibleStartMin = visibleStartMin,
        visibleEndMin = visibleEndMin,
        workingStartMin = workingStartMin,
        workingEndMin = workingEndMin,
        nowMinute = if (days.contains(today)) {
            LocalTime.now(zoneId).let { it.hour * 60 + it.minute }
        } else null,
        nowLineInColumn = { i -> days[i] == today },
        onBlockTap = { _, key -> onEventClick(key) },
        onSlotTap = { col, minute -> onSlotTap(days[col], minute) },
        // 草稿的日期 ↔ 列索引换算(不在当前三天里就不画)。
        draft = draft?.let { d ->
            days.indexOf(d.date).takeIf { it >= 0 }
                ?.let { DraftSelection(it, d.startMin, d.endMin) }
        },
        draftLabel = draftLabel,
        onDraftAdjust = onDraftAdjust?.let { cb ->
            { sel: DraftSelection ->
                cb(DraftSlot(days[sel.colIndex], sel.startMin, sel.endMin))
            }
        },
        onDraftConfirm = onDraftConfirm?.let { cb ->
            { sel: DraftSelection ->
                cb(DraftSlot(days[sel.colIndex], sel.startMin, sel.endMin))
            }
        },
        onBlockMove = onEventMove?.let { cb ->
            { col: Int, key: String, s: Int, e: Int -> cb(key, days[col], s, e) }
        },
        selectedBlockKey = selectedEventId,
        onBlockSelect = onEventSelect,
        onRailTap = onRailTap,
        // 列窄,块内只显标题(时刻由位置 + 左侧刻度读取,点块看详情)。
        compactBlocks = true,
        // 三列与视口等宽；列数不超过可见列数，因此不会出现水平滚动。
        visibleColumnCount = THREE_DAY_VIEW_DAYS,
        railHeader = { CalendarTimeZoneHeader(anchorDate, zoneId) },
        // 日期条与时间网格使用相同列宽。
        columnHeader = { i ->
            WeekDayHeader(
                date = days[i],
                isToday = days[i] == today,
                isAnchor = days[i] == anchorDate,
                onClick = { onDayClick(days[i]) },
            )
        },
    )
}

/** 星期条单元格:星期(NARROW)+ 日期圆点(今日主色实心 / 锚点浅色底)。 */
@Composable
private fun WeekDayHeader(
    date: LocalDate,
    isToday: Boolean,
    isAnchor: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(threeDayHeaderTestTag(date))
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.SpaceXs),
    ) {
        Text(
            text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            style = WeMeetTextStyles.LabelTiny,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(Dimens.Calendar.WeekDotSize)
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
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
