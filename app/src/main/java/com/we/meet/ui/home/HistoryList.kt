package com.we.meet.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.data.history.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HistoryList(
    entries: List<HistoryEntry>,
    onEntryClick: (roomId: String) -> Unit,
    onDeleteEntry: (identifier: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.history_section_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        entries.forEach { entry ->
            HistoryRow(
                entry = entry,
                onClick = { onEntryClick(entry.roomId) },
                onDelete = {
                    // Prefer slug for the delete API call when present —
                    // it's the user-visible meeting code and avoids any
                    // ambiguity with synthesized entries that may not
                    // carry a real backend UUID.
                    val id = entry.slug.takeIf { it.isNotBlank() } ?: entry.roomId
                    onDeleteEntry(id)
                },
            )
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeRevealRow(
        // Right-swiping the row (drag back toward zero) retracts the
        // drawer — that's the cancel gesture, no explicit button needed.
        actionsWidth = ACTION_BUTTON_WIDTH,
        actions = { close ->
            ActionButton(
                label = stringResource(R.string.history_action_delete),
                bg = MaterialTheme.colorScheme.errorContainer,
                fg = MaterialTheme.colorScheme.onErrorContainer,
                onClick = {
                    onDelete()
                    close()
                },
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
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
                    text = entry.name.ifBlank { entry.slug },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = HistoryTimeFormatter.relativeListTimestamp(entry.firstJoinedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
internal fun ActionButton(
    label: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(ACTION_BUTTON_WIDTH)
            .fillMaxHeight()
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal val ACTION_BUTTON_WIDTH = 80.dp

object HistoryTimeFormatter {
    private fun timeFmt() = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun monthDayFmt() = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
    private fun fullDateFmt() = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())

    /** "今天 HH:mm" if same calendar day, else "M月d日 HH:mm". */
    fun relativeListTimestamp(epochMs: Long): String =
        if (isToday(epochMs)) "今天 ${timeFmt().format(Date(epochMs))}"
        else monthDayFmt().format(Date(epochMs))

    fun time(epochMs: Long): String = timeFmt().format(Date(epochMs))

    fun monthDayTime(epochMs: Long): String = monthDayFmt().format(Date(epochMs))

    fun fullDateTime(epochMs: Long): String = fullDateFmt().format(Date(epochMs))

    fun isToday(epochMs: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = epochMs }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }
}
