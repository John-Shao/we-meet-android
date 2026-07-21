package com.we.meet.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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
 * Each row shows the room name on the first line and the formatted
 * scheduled time (M月d日 HH:mm) on the second. Tapping the row routes
 * to PreviewScreen via [onEntryClick] (same target as a history row).
 * Long-pressing opens the delete context menu, in the same shape the
 * history list uses; backend semantics are identical.
 */
@Composable
fun ScheduledMeetingsList(
    rooms: List<RoomDto>,
    onEntryClick: (slug: String) -> Unit,
    onDeleteEntry: (identifier: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rooms.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.scheduled_section_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        rooms.forEach { room ->
            val slug = room.slug ?: return@forEach
            ScheduledRow(
                room = room,
                onClick = { onEntryClick(slug) },
                onDelete = { onDeleteEntry(slug) },
            )
        }
    }
}

@Composable
private fun ScheduledRow(
    room: RoomDto,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    DeletableRow(
        onClick = onClick,
        onDelete = onDelete,
        itemName = (room.name?.takeIf { it.isNotBlank() }) ?: room.slug.orEmpty(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // Match HistoryList's primary-container palette so
                    // both lists read as one visual family — the icon
                    // shape (Event vs Videocam) is what tells them apart.
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = (room.name?.takeIf { it.isNotBlank() }) ?: room.slug.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.scheduled_time_prefix,
                        formatScheduledAt(
                            LocalContext.current.getString(R.string.fmt_month_day_time),
                            room.scheduled_at,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp,
    )
}

private fun formatScheduledAt(pattern: String, iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    val normalized = iso
        .replace(Regex("\\.\\d+"), "")
        .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val ms = runCatching { parser.parse(normalized)?.time }.getOrNull() ?: return iso
    val out = SimpleDateFormat(pattern, Locale.getDefault())
    return out.format(Date(ms))
}
