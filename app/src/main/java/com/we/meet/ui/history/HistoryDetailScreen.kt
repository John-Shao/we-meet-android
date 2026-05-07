package com.we.meet.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.data.history.HistoryEntry
import com.we.meet.ui.home.HistoryTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    roomId: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val entries by app.historyStore.entries.collectAsStateWithLifecycle()
    val entry = remember(entries, roomId) { entries.firstOrNull { it.roomId == roomId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (entry == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            DetailHeaderCard(entry = entry)
            Spacer(Modifier.height(16.dp))
            Timeline(entry = entry)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeaderCard(entry: HistoryEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = entry.name.ifBlank { entry.slug },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        DetailRow(
            icon = Icons.Default.Schedule,
            text = formatTimeRange(entry),
        )
        DetailRow(
            icon = Icons.Default.Info,
            text = stringResource(R.string.history_detail_meeting_id, formatSlug(entry.slug)),
        )
        DetailRow(
            icon = Icons.Default.People,
            text = stringResource(
                R.string.history_detail_host,
                entry.host?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.history_unknown_host),
            ),
        )

        if (entry.participants.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.history_detail_participants),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                entry.participants.forEach { name -> ParticipantChip(name = name) }
            }
        }
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ParticipantChip(name: String) {
    val avatarText = name.trim().firstOrNull()?.toString() ?: "?"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarText,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Timeline(entry: HistoryEntry) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = if (HistoryTimeFormatter.isToday(entry.firstJoinedAtMs))
                stringResource(R.string.history_detail_timeline_today)
            else HistoryTimeFormatter.fullDateTime(entry.firstJoinedAtMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        // 加入会议 — always present.
        TimelineRow(
            time = HistoryTimeFormatter.time(entry.firstJoinedAtMs),
            label = stringResource(R.string.history_detail_joined),
        )
        entry.lastLeftAtMs?.let { leftMs ->
            TimelineRow(
                time = HistoryTimeFormatter.time(leftMs),
                label = stringResource(R.string.history_detail_left),
            )
        }
    }
}

@Composable
private fun TimelineRow(time: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

private fun formatSlug(slug: String): String {
    val digits = slug.filter { it.isDigit() }
    // Render 9-digit slugs as "123 456 789", 6-digit as "123 456", else raw.
    return when (digits.length) {
        9 -> "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
        6 -> "${digits.substring(0, 3)} ${digits.substring(3)}"
        else -> slug
    }
}

@Composable
private fun formatTimeRange(entry: HistoryEntry): String {
    val startMs = entry.firstJoinedAtMs
    val endMs = entry.lastLeftAtMs

    val datePart = if (HistoryTimeFormatter.isToday(startMs))
        "${monthDay(startMs)} (${stringResource(R.string.history_detail_timeline_today)})"
    else monthDay(startMs)

    val timeRange = if (endMs == null) {
        HistoryTimeFormatter.time(startMs)
    } else {
        "${HistoryTimeFormatter.time(startMs)} – ${HistoryTimeFormatter.time(endMs)}"
    }

    return if (endMs != null) {
        val duration = formatDuration(endMs - startMs)
        "$datePart $timeRange  |  $duration"
    } else {
        "$datePart $timeRange"
    }
}

private fun monthDay(epochMs: Long): String {
    // Reuse the month-day formatter but drop the time suffix — we render time
    // separately as a range.
    val full = HistoryTimeFormatter.monthDayTime(epochMs)
    return full.substringBefore(' ', full)
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 秒"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val parts = buildList {
        if (hours > 0) add("${hours} 时")
        if (minutes > 0) add("${minutes} 分")
        if (hours == 0L && minutes < 10) add("${seconds} 秒")
    }
    return if (parts.isEmpty()) "${totalSeconds} 秒" else parts.joinToString(" ")
}
