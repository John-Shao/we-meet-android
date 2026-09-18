package com.we.meet.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RoomDto
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 预约列表默认只列前 N 条。待开始的会议可能攒到十几条(线上实测还有两个月前没人
 * 收尾的遗留预约),而这一节是「最近的安排」而不是完整清单 —— 不截断就会把整段
 * 「历史会议」顶出首屏。Web 端 `ScheduledMeetingsList.PREVIEW_COUNT` 是同一个数。
 */
internal const val SCHEDULED_PREVIEW_LIMIT = 10

/**
 * Pending video meetings from the server overview. The section stays visible
 * even when empty; delayed appointments are retained until they actually start.
 *
 * P8(对标飞书):点行经 [onEntryClick] 打开预约会议详情页,进入会议 /
 * 复制 / 删除等操作全部收进详情(ScheduledDetailScreen);长按删除已移除。
 */
@Composable
fun ScheduledMeetingsList(
    rooms: List<RoomDto>,
    onEntryClick: (room: RoomDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开状态跟着页面活:切走再回来仍然是用户上次选的那一档。
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rows = rooms.filter { it.slug != null }
    Column(modifier = modifier.fillMaxWidth()) {
        MeetingListSectionTitle(stringResource(R.string.scheduled_section_title))
        rows.take(if (expanded) rows.size else SCHEDULED_PREVIEW_LIMIT).forEach { room ->
            ScheduledRow(room = room, onClick = { onEntryClick(room) })
        }
        if (rows.size > SCHEDULED_PREVIEW_LIMIT) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (expanded) R.string.video_collapse else R.string.video_show_all, rows.size))
            }
        }
    }
}

@Composable
private fun ScheduledRow(
    room: RoomDto,
    onClick: () -> Unit,
) {
    MeetingListItem(
        title = room.name?.takeIf { it.isNotBlank() } ?: room.slug.orEmpty(),
        timestamp = parseScheduledMs(room.scheduled_at)
            ?.let { HistoryTimeFormatter.relativeListTimestamp(LocalContext.current, it) }
            ?: "—",
        icon = Icons.Outlined.Event,
        onClick = onClick,
    )
}

private fun parseScheduledMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val normalized = iso
        .replace(Regex("\\.\\d+"), "")
        .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return runCatching { parser.parse(normalized)?.time }.getOrNull()
}
