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
    // 与 Web 端 `MeetingRecordWorkspace` 的「录音信息」同一版式:两列键值表 ——
    // 左列定宽、值左对齐成一列。原来是「标签一行、值一行」堆三对,读起来是三段
    // 独立文字,扫不出「哪一行的值是什么」。
    Column(
        modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        InfoRow(R.string.records_source_filter, stringResource(recordSourceLabel(record)))
        InfoRow(R.string.records_start_time, recordTime(record.originAt))
        InfoRow(R.string.records_retention, stringResource(retentionLabel(record.retentionMode)))
    }
}

/** 键值行:标签走次要色且定宽,值与正文同色(对齐 Web `dl` 的 `dt`/`dd`)。 */
@Composable
private fun InfoRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Text(stringResource(label), Modifier.width(Dimens.LabelColumnWidth),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun retentionLabel(mode: String): Int = when (mode) {
    "media" -> R.string.records_retention_media
    "text" -> R.string.records_retention_text
    else -> R.string.records_retention_unknown
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
