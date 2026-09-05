package com.we.meet.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.data.api.dto.RoomDto
import java.text.SimpleDateFormat
import java.util.Date
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
        Text(
            text = stringResource(R.string.scheduled_section_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = Dimens.SpaceM),
        )
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
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpaceM),
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.ListThumbnail)
                    .clip(RoundedCornerShape(Dimens.CornerS))
                    // Keep future meetings emphasized; history rows use a
                    // neutral surface so the two sections keep a clear hierarchy.
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(Dimens.SpaceM))
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = (room.name?.takeIf { it.isNotBlank() }) ?: room.slug.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 无前缀,当天显示「今天 HH:mm」,否则「M月d日 HH:mm」(不带年,
                // 预约都是近期未来;与 Web 端同口径)。
                Text(
                    text = parseScheduledMs(room.scheduled_at)
                        ?.let {
                            HistoryTimeFormatter.relativeListTimestamp(
                                LocalContext.current, it,
                            )
                        }
                        ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = Dimens.DividerIndentThumbnail),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = Dimens.DividerThin,
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
