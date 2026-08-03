package com.we.meet.ui.meetingroom

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.data.api.dto.MeetingRoomTimelineEntryDto
import com.we.meet.ui.calendar.views.TimeBlock
import com.we.meet.ui.calendar.views.TimeSelection
import com.we.meet.ui.calendar.views.TimelineScaffold
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 一屏铺几列 —— 手机窄屏 3 列可读,更多会议室横滑。 */
private const val VISIBLE_ROOMS = 3

private fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

/** 相对当日 00:00 的分钟数,裁剪到 [0, 1440]。跨日预订天然被切在边界上。 */
private fun minutesInto(dayStart: ZonedDateTime, iso: String): Int? =
    runCatching {
        Duration.between(dayStart, Instant.parse(iso).atZone(dayStart.zone))
            .toMinutes()
            .toInt()
            .coerceIn(0, 24 * 60)
    }.getOrNull()

/**
 * 会议室占用时间轴 —— 纵向时间 × 横向会议室(P9 M1.5)。
 *
 * 方向与 Web 相反是**刻意的**:竖屏天然适合纵向时间轴,也与 App 既有的日/周
 * 视图一致。为对齐 Web 把它掰成横向反而会和旁边的日历格格不入。
 *
 * 复用 [TimelineScaffold] —— N 列资源轴、列头与网格共享横滚、24h 格线、当前
 * 时刻红线、选中时段高亮、不可用列置灰全都是现成的,这里只负责把 booking 投
 * 影成 [TimeBlock]。
 */
@Composable
internal fun MeetingRoomTimeline(
    rooms: List<MeetingRoomTimelineEntryDto>,
    /** 时间轴所在那一天的本地 00:00。 */
    dayStart: ZonedDateTime,
    /** 表单已选的时段,用来画高亮并判定哪些列不可选。 */
    slotStartIso: String,
    slotEndIso: String,
    /** 该时段空闲的会议室 id —— 其余列置灰不可选。 */
    freeIds: Set<String>,
    scrollState: ScrollState,
    onPickRoom: (MeetingRoomTimelineEntryDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rooms.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.meeting_room_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val selection = run {
        val s = minutesInto(dayStart, slotStartIso)
        val e = minutesInto(dayStart, slotEndIso)
        if (s != null && e != null && e > s) TimeSelection(s, e) else null
    }

    Column(modifier) {
        Text(
            text = stringResource(R.string.meeting_room_timeline_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpaceXs),
        )
        TimelineScaffold(
            modifier = Modifier.fillMaxSize(),
            columns = rooms.map { room ->
                room.bookings.mapNotNull { booking ->
                    val s = minutesInto(dayStart, booking.start) ?: return@mapNotNull null
                    val e = minutesInto(dayStart, booking.end) ?: return@mapNotNull null
                    if (e <= s) return@mapNotNull null
                    TimeBlock(
                        startMin = s,
                        endMin = e,
                        // private 日程对外只给色块,不给标题 —— 服务端已置 null,
                        // 这里原样透传即可(TimeBlock 的 label 本就允许为空)。
                        label = booking.title,
                        timeLabel = "${fmt(s)} – ${fmt(e)}",
                        key = booking.id,
                    )
                }
            },
            scrollState = scrollState,
            nowMinute = if (dayStart.toLocalDate() == LocalDate.now()) {
                LocalTime.now().let { it.hour * 60 + it.minute }
            } else {
                null
            },
            disabledColumn = { i -> rooms[i].id !in freeIds },
            selection = selection,
            visibleColumnCount = VISIBLE_ROOMS,
            // 点列 = 选这间会议室。时段以表单为准,不在这里改 —— 表单上方已经
            // 定好了起止时刻,从时间轴里悄悄改掉它只会让人意外。
            onSlotTap = { col, _ ->
                rooms.getOrNull(col)?.takeIf { it.id in freeIds }?.let(onPickRoom)
            },
            onBlockTap = { col, _ ->
                rooms.getOrNull(col)?.takeIf { it.id in freeIds }?.let(onPickRoom)
            },
            columnHeader = { i ->
                val room = rooms[i]
                Column(modifier = Modifier.padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXs)) {
                    Text(
                        text = room.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (room.capacity > 0) {
                            stringResource(
                                R.string.meeting_room_capacity_people,
                                room.capacity,
                            )
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}
