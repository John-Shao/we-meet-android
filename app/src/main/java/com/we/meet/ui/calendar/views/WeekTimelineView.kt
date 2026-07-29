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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** P8「降低已结束日程的亮度」:非空时,结束早于该时刻的块降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
    /** 当前预选时段(只在它所属日期落在本周列里时才画)。 */
    draft: DraftSlot? = null,
    draftLabel: String? = null,
    onDraftAdjust: ((DraftSlot) -> Unit)? = null,
    onDraftConfirm: ((DraftSlot) -> Unit)? = null,
    /** 我的 uuid:我组织的非重复日程可长按拖动改期(null = 全都不可拖)。 */
    selfUserId: String? = null,
    /** 长按拖动改期落点;横向跨列 = 改到那一天。 */
    onEventMove: ((eventId: String, date: LocalDate, startMin: Int, endMin: Int) -> Unit)? = null,
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

    val hourHeight = 56.dp
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(days.first()) {
        scrollState.scrollTo(with(density) { (hourHeight * 8).toPx() }.toInt())
    }
    // 显示周末时 7 天但一屏只铺 5 列,横滚到让今天(不在本周则锚点)可见。
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
        // 7 列过窄,块内只显标题(时刻由位置 + 左侧刻度读取,点块看详情)。
        compactBlocks = true,
        // 一屏 5 列:关周末时 = 全部 5 列(不滚);开周末时 7 列滚动看余下两天。
        visibleColumnCount = 5,
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
