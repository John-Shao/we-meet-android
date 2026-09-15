package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens

@Composable
internal fun RecordInfo(record: RecordDto, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        item { Text(stringResource(R.string.records_source_filter), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(stringResource(sourceLabel(record.sourceType))) }
        item { Text(stringResource(R.string.records_start_time), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(recordTime(record.originAt)) }
        item {
            Text(stringResource(R.string.records_retention), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(when (record.retentionMode) { "media" -> R.string.records_retention_media; "text" -> R.string.records_retention_text; else -> R.string.records_retention_unknown }))
        }
    }
}

@Composable
internal fun RecordSpeakers(repository: MeetingRecordRepository, viewer: String, record: RecordDto, modifier: Modifier = Modifier) {
    var cursors by remember(viewer, record.id, record.revision) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember { mutableIntStateOf(0) }
    val result = visibleRead(viewer, record.id, record.revision, cursors.last(), refresh) { repository.speakers(viewer, record.id, record.revision, cursors.last()) }
    Column(modifier.fillMaxWidth()) {
        when {
            result == null -> WeMeetInlineLoading()
            result.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_source_unavailable))
            else -> LazyColumn(contentPadding = PaddingValues(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                if (result.getOrThrow().results.isEmpty()) item { Text(stringResource(R.string.records_no_speakers)) }
                items(result.getOrThrow().results, key = { it.id }) { speaker -> Text(speaker.label.ifBlank { stringResource(R.string.records_unknown_speaker) }, style = MaterialTheme.typography.titleMedium) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                    result.getOrThrow().nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                } }
            }
        }
    }
}
