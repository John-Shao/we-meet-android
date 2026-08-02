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
import androidx.compose.ui.text.style.TextAlign
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTextStyles
import com.we.meet.data.settings.CALENDAR_WEEK_VISIBLE_DAYS_DEFAULT
import com.we.meet.ui.calendar.EventUi
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 周视图要显示的列(单一数据源:周视图列 + 头部区间标题共用)。从 anchor 往前
 * 找最近的 [firstDayOfWeek] 作本周首日铺满 7 天;[showWeekend] 关闭时滤掉周六
 * 周日,恒剩周一~周五(与首日无关,对齐 Google 工作周)。
 */
fun weekColumnDays(
    anchorDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
    showWeekend: Boolean,
): List<LocalDate> {
    val weekStart = anchorDate.minusDays(
        ((anchorDate.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong(),
    )
    val week = (0..6).map { weekStart.plusDays(it.toLong()) }
    return if (showWeekend) week
    else week.filter {
        it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
    }
}

/**
 * P8 周视图(飞书「三日」按团队约定改为 7 列周):顶部星期条(今日高亮圆点,
 * 点某天切到该日)+ 时间轴,红线只画在今天列。翻周走 header 的 ‹ ›(±7 天),
 * 不引入 Pager(见 P8 设计:降低状态同步面)。周末开关关闭时收敛为 5 列工作周。
 * 一屏只铺 [visibleDays] 天(日历设置项),其余横滑(列头与网格同步滚)。
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
    /** P8 日历设置:显示周末(默认 true;关闭 → 只列周一~周五 5 列)。 */
    showWeekend: Boolean = true,
    /** 日历设置:一屏铺几天(3~7);列数比它少时铺满不滚。 */
    visibleDays: Int = CALENDAR_WEEK_VISIBLE_DAYS_DEFAULT,
    /** P8「降低已结束日程的亮度」:非空时,结束早于该时刻的块降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
    /** 当前预选时段(只在它所属日期落在本周列里时才画)。 */
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
    val today = LocalDate.now()
    val days = remember(anchorDate, firstDayOfWeek, showWeekend) {
        weekColumnDays(anchorDate, firstDayOfWeek, showWeekend)
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
    LaunchedEffect(days.first()) {
        scrollState.scrollTo(with(density) { (hourHeight * 8).toPx() }.toInt())
    }
    // 一屏只铺 VISIBLE_DAYS 天,横滚到让今天(不在本周则锚点)可见。
    val revealIndex = remember(days) {
        days.indexOf(today).takeIf { it >= 0 }
            ?: days.indexOf(anchorDate).coerceAtLeast(0)
    }

    TimelineScaffold(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        hourHeight = hourHeight,
        scrollState = scrollState,
        nowMinute = if (days.contains(today)) {
            LocalTime.now().let { it.hour * 60 + it.minute }
        } else null,
        nowLineInColumn = { i -> days[i] == today },
        onBlockTap = { _, key -> onEventClick(key) },
        onSlotTap = { col, minute -> onSlotTap(days[col], minute) },
        // 草稿的日期 ↔ 列索引换算(不在本周的列里就不画)。
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
        // 一屏铺几天走日历设置(默认 3,对齐飞书「三日」)。比它多的天数横滑
        // 看;拖块跨列改日期不受影响。
        visibleColumnCount = visibleDays,
        revealColumnIndex = revealIndex,
        // 星期条随网格横滚锁定同步(飞书样式):放进 scaffold 的列头槽。
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
