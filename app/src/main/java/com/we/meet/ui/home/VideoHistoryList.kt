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

/** Ongoing and ended sessions, including sessions without generated material. */
@Composable
fun VideoHistoryList(rooms: List<RoomDto>, onSelect: (RoomDto) -> Unit, onMore: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        MeetingListSectionTitle(stringResource(R.string.history_section_title))
        if (rooms.isEmpty()) Text(stringResource(R.string.video_history_empty), Modifier.padding(Dimens.ScreenPadding), color = MaterialTheme.colorScheme.onSurfaceVariant)
        rooms.take(10).forEach { room ->
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
