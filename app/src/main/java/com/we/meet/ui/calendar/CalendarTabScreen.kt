package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.we.meet.ui.calendar.views.DraftSlot
import com.we.meet.ui.calendar.views.WeekTimelineView
import com.we.meet.ui.calendar.views.draftSlotAt
import com.we.meet.ui.calendar.views.weekColumnDays
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
    /** 预选时段确认后按精确起止(epoch 秒)进创建表单;缺省退化成按天创建。 */
    onCreateEventAt: ((startEpochSecond: Long, endEpochSecond: Long) -> Unit)? = null,
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
    // P8 日历设置:周视图是否显示周末(App 默认关 → 聚焦工作周;Web 默认开)。
    val showWeekend by app.settingsStore.calendarShowWeekend.collectAsStateWithLifecycle()
    // 新建日程默认时长:点空白落预选块时的初始长度(与表单默认一致)。
    val defaultDurationMin by app.settingsStore.calendarDefaultDurationMin
        .collectAsStateWithLifecycle()

    // 对齐飞书:日/周视图点空白先落「预选时段」,拖上下手柄改起止,**再点一次**
    // 这个块才进创建表单 —— 误触不会直接弹表单。切视图/切日期即作废。
    var draft by remember { mutableStateOf<DraftSlot?>(null) }
    LaunchedEffect(ui.viewMode, ui.selectedDate) { draft = null }
    val draftLabel = stringResource(R.string.calendar_draft_add)
    val confirmDraft: (DraftSlot) -> Unit = { slot ->
        draft = null
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = slot.date.atStartOfDay(zone)
        val onAt = onCreateEventAt
        if (onAt != null) {
            onAt(
                dayStart.plusMinutes(slot.startMin.toLong()).toEpochSecond(),
                dayStart.plusMinutes(slot.endMin.toLong()).toEpochSecond(),
            )
        } else {
            onCreateEvent(slot.date.toEpochDay())
        }
    }

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
                showWeekend = showWeekend,
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
                        // 点空白 = 落/挪预选块(不再直接进表单)。
                        onSlotTap = { minute ->
                            draft = draftSlotAt(ui.selectedDate, minute, defaultDurationMin)
                        },
                        dimPastNow = dimPastNow,
                        draft = draft,
                        draftLabel = draftLabel,
                        onDraftAdjust = { draft = it },
                        onDraftConfirm = confirmDraft,
                    )

                    CalendarViewMode.WEEK -> WeekTimelineView(
                        anchorDate = ui.selectedDate,
                        eventsByDay = ui.eventsByDay,
                        onEventClick = onEventClick,
                        onDayClick = { vm.selectDate(it) },
                        onSlotTap = { date, minute ->
                            draft = draftSlotAt(date, minute, defaultDurationMin)
                        },
                        firstDayOfWeek = firstDow,
                        showWeekend = showWeekend,
                        dimPastNow = dimPastNow,
                        draft = draft,
                        draftLabel = draftLabel,
                        onDraftAdjust = { draft = it },
                        onDraftConfirm = confirmDraft,
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
    showWeekend: Boolean,
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
                text = headerTitle(ui, firstDow, showWeekend),
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

// 日期一律走数字化 ISO(yyyy-MM-dd 系),跨语言一致、窄屏不易换行;星期名
// 仍按 locale 本地化(getDisplayName)。
private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val ISO_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
private val ISO_MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val ISO_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd")

/** 按视图格式化标题(日/周/月/日程一律 yyyy-MM-dd 数字格式)。 */
@Composable
private fun headerTitle(ui: CalendarUiState, firstDow: DayOfWeek, showWeekend: Boolean): String {
    val locale = Locale.getDefault()
    return when (ui.viewMode) {
        CalendarViewMode.DAY ->
            ui.selectedDate.format(ISO_DATE) + " " +
                ui.selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale)

        CalendarViewMode.WEEK -> {
            // 与周视图列同源:关周末时区间收口到周五,和实际列一致。
            val cols = weekColumnDays(ui.selectedDate, firstDow, showWeekend)
            weekRangeTitle(cols.first(), cols.last())
        }

        CalendarViewMode.MONTH -> ui.monthAnchor.format(ISO_MONTH)

        // 日程视图:终点恒为锚点+1年,展示区间冗余且窄屏易换行,收敛成
        // 「2026-07-23 起一年」(与列表日期分组头同格式)。
        CalendarViewMode.AGENDA -> stringResource(
            R.string.calendar_agenda_anchor_year,
            ui.selectedDate.format(ISO_DATE),
        )
    }
}

/** 周区间标题:起点全量 yyyy-MM-dd,终点同月省到 dd、同年省到 MM-dd,跨年全量。 */
private fun weekRangeTitle(start: LocalDate, end: LocalDate): String {
    val endFmt = when {
        start.year == end.year && start.month == end.month -> ISO_DAY
        start.year == end.year -> ISO_MONTH_DAY
        else -> ISO_DATE
    }
    return "${start.format(ISO_DATE)} - ${end.format(endFmt)}"
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
    // 表态四态四色(接受=蓝 / 未反馈=紫 / 待定=琥珀 / 拒绝=灰),与日/周视图
    // 的竖条同一组色值;拒绝再加删除线,口径一致。
    val visual = rsvpVisualOf(event.myRsvp)
    val declined = visual == RsvpVisual.DECLINED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            // 色条要贴左缘且随卡片圆角裁切 → 先 clip 再铺底;IntrinsicSize.Min
            // 给色条的 fillMaxHeight 一个确定高度(Row 本身高度不定)。
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(rsvpAccentColor(visual)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
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
                    Text(
                        text = event.start.format(timeFmt),
                        style = MaterialTheme.typography.labelLarge,
                    )
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
                    color = if (declined) {
                        rsvpTextColor(RsvpVisual.DECLINED)
                    } else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (event.cancelled || declined) {
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
        }
    }
}
