package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.settings.CalendarWeekStart
import com.we.meet.ui.calendar.views.AgendaView
import com.we.meet.ui.calendar.views.CalendarViewMode
import com.we.meet.ui.calendar.views.DayTimelineView
import com.we.meet.ui.calendar.views.WeekTimelineView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 日历 tab — month grid + selected-day agenda + create FAB. */
@Composable
fun CalendarTabScreen(
    onEventClick: (eventId: String) -> Unit,
    onCreateEvent: (epochDay: Long) -> Unit,
    /** P8 日历设置页入口(header 齿轮)。 */
    onOpenSettings: () -> Unit = {},
) {
    val vm: CalendarViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    // P8 日历设置:每周的第一天(月网格/周视图跟随,默认周一)。
    val app = LocalContext.current.applicationContext as WeMeetApp
    val weekStart by app.settingsStore.calendarWeekStart.collectAsStateWithLifecycle()
    val firstDow =
        if (weekStart == CalendarWeekStart.SUNDAY) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
    // P8 日历设置:降低已结束日程的亮度(开关关闭 → null,不降)。
    val dimPast by app.settingsStore.calendarDimPast.collectAsStateWithLifecycle()
    val dimPastNow = if (dimPast) java.time.ZonedDateTime.now() else null

    // Returning from create/detail routes resumes HOME — refresh picks up
    // new events and RSVP changes without result-passing plumbing.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 对齐 Web:顶部分段切换器(日/周/月/日程)+ ‹ 今天 › + 按视图标题;
            // ‹ › 语义随视图:日/日程=±1 天,周=±7 天,月=±1 月。
            CalendarHeader(
                ui = ui,
                firstDow = firstDow,
                onSelectMode = { vm.setViewMode(it) },
                onPrev = {
                    when (ui.viewMode) {
                        CalendarViewMode.DAY,
                        CalendarViewMode.AGENDA,
                        -> vm.selectDate(ui.selectedDate.minusDays(1))
                        CalendarViewMode.WEEK -> vm.selectDate(ui.selectedDate.minusDays(7))
                        else -> vm.goToMonth(ui.monthAnchor.minusMonths(1))
                    }
                },
                onNext = {
                    when (ui.viewMode) {
                        CalendarViewMode.DAY,
                        CalendarViewMode.AGENDA,
                        -> vm.selectDate(ui.selectedDate.plusDays(1))
                        CalendarViewMode.WEEK -> vm.selectDate(ui.selectedDate.plusDays(7))
                        else -> vm.goToMonth(ui.monthAnchor.plusMonths(1))
                    }
                },
                onToday = { vm.goToToday() },
                onOpenSettings = onOpenSettings,
            )

            when {
                ui.loading && ui.eventsByDay.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                ui.error -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        stringResource(R.string.calendar_load_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { vm.refresh() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.calendar_retry))
                    }
                }

                else -> when (ui.viewMode) {
                    CalendarViewMode.AGENDA -> AgendaView(
                        anchorDate = ui.selectedDate,
                        eventsByDay = ui.eventsByDay,
                        onEventClick = onEventClick,
                        dimPastNow = dimPastNow,
                    )

                    CalendarViewMode.DAY -> DayTimelineView(
                        date = ui.selectedDate,
                        events = ui.eventsByDay[ui.selectedDate].orEmpty(),
                        onEventClick = onEventClick,
                        onSlotTap = { _ -> onCreateEvent(ui.selectedDate.toEpochDay()) },
                        dimPastNow = dimPastNow,
                    )

                    CalendarViewMode.WEEK -> WeekTimelineView(
                        anchorDate = ui.selectedDate,
                        eventsByDay = ui.eventsByDay,
                        onEventClick = onEventClick,
                        onDayClick = { vm.selectDate(it) },
                        onSlotTap = { date, _ -> onCreateEvent(date.toEpochDay()) },
                        firstDayOfWeek = firstDow,
                        dimPastNow = dimPastNow,
                    )

                    CalendarViewMode.MONTH -> MonthViewBody(
                        ui = ui,
                        firstDow = firstDow,
                        dimPastNow = dimPastNow,
                        onSelect = { vm.selectDate(it) },
                        onEventClick = onEventClick,
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { onCreateEvent(ui.selectedDate.toEpochDay()) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.calendar_create_title))
        }
    }
}

/** 月视图分支 = 原有月历网格 + 选中日列表(原样保留)。 */
@Composable
private fun MonthViewBody(
    ui: CalendarUiState,
    firstDow: DayOfWeek,
    dimPastNow: java.time.ZonedDateTime?,
    onSelect: (LocalDate) -> Unit,
    onEventClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        MonthGrid(
            month = ui.monthAnchor,
            selected = ui.selectedDate,
            eventsByDay = ui.eventsByDay,
            firstDow = firstDow,
            onSelect = onSelect,
        )
        when {
            ui.selectedDayEvents.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.calendar_no_events),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp,
                ),
            ) {
                items(ui.selectedDayEvents, key = { it.id }) { event ->
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

/**
 * 对齐 Web 日历的头部:
 * 第一行 = 日/周/月/日程 分段切换器(等宽,替代原底部滑窗)+ 设置齿轮;
 * 第二行 = ‹ 今天 › + 按视图格式化的标题(不加粗;日视图看今天时带蓝色
 * 「今天」后缀;日程视图 = 锚点起一年的闭区间)。
 */
@Composable
private fun CalendarHeader(
    ui: CalendarUiState,
    firstDow: DayOfWeek,
    onSelectMode: (CalendarViewMode) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp),
        ) {
            ViewModeSwitcher(current = ui.viewMode, onSelect = onSelectMode)
            Spacer(Modifier.weight(1f))
            // P8:日历设置(列表提醒开关/周起始/默认时长/默认提醒)。
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.calendar_settings_title),
                )
            }
        }
        val (prevCd, nextCd) = when (ui.viewMode) {
            CalendarViewMode.DAY, CalendarViewMode.AGENDA ->
                R.string.calendar_prev_day to R.string.calendar_next_day
            CalendarViewMode.WEEK ->
                R.string.calendar_prev_week to R.string.calendar_next_week
            CalendarViewMode.MONTH ->
                R.string.calendar_prev_month to R.string.calendar_next_month
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(prevCd),
                )
            }
            TextButton(onClick = onToday) { Text(stringResource(R.string.calendar_today)) }
            IconButton(onClick = onNext) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(nextCd),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = headerTitle(ui, firstDow),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ui.viewMode == CalendarViewMode.DAY && ui.selectedDate == LocalDate.now()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.calendar_today),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 日/周/月/日程 等宽分段切换器(Web CalendarViewSwitcher 同款)。 */
@Composable
private fun ViewModeSwitcher(
    current: CalendarViewMode,
    onSelect: (CalendarViewMode) -> Unit,
) {
    // 顺序对齐 Web VIEW_ORDER:日、周、月、日程。
    val order = listOf(
        CalendarViewMode.DAY,
        CalendarViewMode.WEEK,
        CalendarViewMode.MONTH,
        CalendarViewMode.AGENDA,
    )
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(2.dp),
    ) {
        order.forEach { mode ->
            val selected = mode == current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else Color.Transparent,
                    )
                    .clickable { onSelect(mode) }
                    .widthIn(min = 52.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(mode.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 按视图格式化标题(格式串在 strings 里按语言给,中文 =「2026年7月23日」系)。 */
@Composable
private fun headerTitle(ui: CalendarUiState, firstDow: DayOfWeek): String {
    val locale = Locale.getDefault()
    val fullFmt = DateTimeFormatter.ofPattern(
        stringResource(R.string.calendar_fmt_date_full), locale,
    )
    return when (ui.viewMode) {
        CalendarViewMode.DAY ->
            ui.selectedDate.format(fullFmt) + " " +
                ui.selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale)

        CalendarViewMode.WEEK -> {
            val start = ui.selectedDate.minusDays(
                ((ui.selectedDate.dayOfWeek.value - firstDow.value + 7) % 7).toLong(),
            )
            rangeTitle(start, start.plusDays(6), fullFmt)
        }

        CalendarViewMode.MONTH -> ui.monthAnchor.format(
            DateTimeFormatter.ofPattern(
                stringResource(R.string.calendar_fmt_month_title), locale,
            ),
        )

        // 日程视图:终点恒为锚点+1年,展示区间冗余且窄屏易换行,收敛成
        // 「2026-07-23 起一年」(与列表日期分组头同格式)。
        CalendarViewMode.AGENDA -> stringResource(
            R.string.calendar_agenda_anchor_year,
            ui.selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
        )
    }
}

/** 区间标题:同月省到「d日」、同年省到「M月d日」,跨年全量(对齐 Web)。 */
@Composable
private fun rangeTitle(
    start: LocalDate,
    end: LocalDate,
    fullFmt: DateTimeFormatter,
): String {
    val locale = Locale.getDefault()
    val endPatternRes = when {
        start.year == end.year && start.month == end.month -> R.string.calendar_fmt_date_d
        start.year == end.year -> R.string.calendar_fmt_date_md
        else -> R.string.calendar_fmt_date_full
    }
    val endFmt = DateTimeFormatter.ofPattern(stringResource(endPatternRes), locale)
    return "${start.format(fullFmt)} - ${end.format(endFmt)}"
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    firstDow: DayOfWeek,
    onSelect: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    // 6 fixed rows of 7; first column = firstDow(P8 日历设置,默认周一).
    val firstOfMonth = month.atDay(1)
    val leadingBlanks = (firstOfMonth.dayOfWeek.value - firstDow.value + 7) % 7
    val gridStart = firstOfMonth.minusDays(leadingBlanks.toLong())

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            (0L..6L).map(firstDow::plus).forEach { dow ->
                Text(
                    text = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(6) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { dayIndex ->
                    val date = gridStart.plusDays((week * 7 + dayIndex).toLong())
                    val inMonth = YearMonth.from(date) == month
                    val isSelected = date == selected
                    val isToday = date == today
                    val hasEvents = eventsByDay[date]?.isNotEmpty() == true

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.1f)
                            .padding(2.dp)
                            .clickable { onSelect(date) },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    },
                                    shape = CircleShape,
                                ),
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    !inMonth -> MaterialTheme.colorScheme.outlineVariant
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        if (hasEvents) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 1.dp)
                                    .size(4.dp)
                                    .background(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.tertiary,
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgendaCard(
    event: EventUi,
    onClick: () -> Unit,
    /** P8「降低已结束日程的亮度」:非空且日程已结束时整卡降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val dimmed = dimPastNow != null && event.end.isBefore(dimPastNow)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.width(52.dp)) {
            if (event.allDay) {
                Text(
                    text = stringResource(R.string.calendar_all_day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(text = event.start.format(timeFmt), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = event.end.format(timeFmt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (event.cancelled) {
                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                } else null,
            )
            event.organizerName?.takeIf { it.isNotBlank() }?.let { organizer ->
                Text(
                    text = organizer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (event.roomSlug != null) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = stringResource(R.string.event_join_meeting),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
