package com.we.meet.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.ui.theme.Dimens
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 历史会议最多显示的条数。三处上限必须同源：这里、[HomeViewModel] 的入站裁剪，
 * 以及后端 `rooms/video-meetings/` 的 `recent` 查询上限；Web 端
 * `RecentMeetingsList` 的 `COLLAPSED_COUNT` 是同一个数。
 */
internal const val VIDEO_HISTORY_LIMIT = 20

/** Ongoing and ended sessions, including sessions without generated material. */
@Composable
fun VideoHistoryList(rooms: List<RoomDto>, onSelect: (RoomDto) -> Unit, onMore: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        MeetingListSectionTitle(stringResource(R.string.history_section_title))
        if (rooms.isEmpty()) Text(stringResource(R.string.video_history_empty), Modifier.padding(Dimens.ScreenPadding), color = MaterialTheme.colorScheme.onSurfaceVariant)
        rooms.take(VIDEO_HISTORY_LIMIT).forEach { room ->
            key(room.meeting_session_id ?: room.id) {
                val time = runCatching {
                    OffsetDateTime.parse(room.started_at).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))
                }.getOrDefault("")
                val status = stringResource(if (room.status == "active") R.string.video_active else R.string.video_ended)
                MeetingListItem(room.name?.ifBlank { null } ?: room.slug.orEmpty(), "$status · $time", Icons.Outlined.Videocam, { onSelect(room) })
            }
        }
        if (onMore != null) TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.video_more)) }
    }
}
