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
 * P8 日视图:全天条(chips)+ 单列时间轴。点日程块进详情,点空白先落一个
 * 「预选时段」([draft],飞书交互:拖上下手柄改起止,再点一次才进创建表单)。
 */
@Composable
fun DayTimelineView(
    date: LocalDate,
    events: List<EventUi>,
    onEventClick: (String) -> Unit,
    onSlotTap: (minuteOfDay: Int) -> Unit,
    /** P8「降低已结束日程的亮度」:非空时,结束早于该时刻的块降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
    /** 当前预选时段(仅当它就是 [date] 当天时才画)。 */
    draft: DraftSlot? = null,
    draftLabel: String? = null,
    onDraftAdjust: ((DraftSlot) -> Unit)? = null,
    onDraftConfirm: ((DraftSlot) -> Unit)? = null,
    /** 我的 uuid:我组织的非重复日程可长按拖动改期(null = 全都不可拖)。 */
    selfUserId: String? = null,
    /** 长按拖动改期落点(日视图不跨列,日期恒为 [date])。 */
    onEventMove: ((eventId: String, date: LocalDate, startMin: Int, endMin: Int) -> Unit)? = null,
) {
    val blocks = remember(date, events, dimPastNow, selfUserId) {
        events.mapNotNull { it.toTimeBlockOrNull(date, dimPastNow, selfUserId) }
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
            // 日视图只有一列:同日的草稿投到 col 0,回调再补回日期。
            draft = draft?.takeIf { it.date == date }
                ?.let { DraftSelection(0, it.startMin, it.endMin) },
            draftLabel = draftLabel,
            onDraftAdjust = onDraftAdjust?.let { cb ->
                { sel: DraftSelection -> cb(DraftSlot(date, sel.startMin, sel.endMin)) }
            },
            onDraftConfirm = onDraftConfirm?.let { cb ->
                { sel: DraftSelection -> cb(DraftSlot(date, sel.startMin, sel.endMin)) }
            },
            onBlockMove = onEventMove?.let { cb ->
                { _: Int, key: String, s: Int, e: Int -> cb(key, date, s, e) }
            },
        )
    }
}
