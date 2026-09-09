package com.we.meet.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RoomDto
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * "预约会议" list — surfaces rooms with a future `scheduled_at` that the
 * user hasn't joined from this device yet. Sits above the standard
 * history list on Home; renders nothing when there's no upcoming meeting
 * so it doesn't push the rest of the page down on cold-start home.
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
    if (rooms.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        MeetingListSectionTitle(stringResource(R.string.scheduled_section_title))
        rooms.forEach { room ->
            if (room.slug == null) return@forEach
            ScheduledRow(room = room, onClick = { onEntryClick(room) })
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
