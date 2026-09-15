package com.we.meet.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.records.visibleRead
import com.we.meet.ui.theme.Dimens

/** Metadata only: media and generated bodies load after opening their workspace. */
@Composable
internal fun MeetingRecordLinks(repository: MeetingRecordRepository, viewer: String, roomId: String, sessionId: String?, onRecord: (String) -> Unit, onSummary: (String) -> Unit) {
    var refresh by remember(viewer, roomId, sessionId) { mutableIntStateOf(0) }
    val result = visibleRead(viewer, roomId, sessionId, refresh) { repository.resolve(viewer, roomId, sessionId) }
    val record = result?.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        listOf(false, true).forEach { summary ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    Text(stringResource(if (summary) R.string.records_minutes else R.string.records_title), style = MaterialTheme.typography.titleMedium)
                    if (record != null) {
                        if (record.title.isNotBlank()) Text(record.title)
                        val time = runCatching { java.time.OffsetDateTime.parse(record.originAt).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")) }.getOrDefault("")
                        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(when {
                        result == null -> R.string.video_material_loading
                        record == null -> R.string.video_material_unavailable
                        !summary -> R.string.video_record_hint
                        record.hasSummary -> R.string.records_minutes_ready
                        else -> R.string.video_summary_pending
                    }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (record != null && (if (summary) record.capabilities.readSummary else record.capabilities.readTranscript)) {
                        TextButton(onClick = { if (summary) onSummary(record.id) else onRecord(record.id) }) {
                            Text(stringResource(if (summary) R.string.video_view_summary else R.string.video_view_record))
                        }
                    }
                    if (result?.isFailure == true) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                }
            }
        }
    }
}
