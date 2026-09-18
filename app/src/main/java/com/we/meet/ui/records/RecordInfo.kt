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
import com.we.meet.data.repository.CaptureRepository
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens

@Composable
internal fun RecordInfo(record: RecordDto, captures: CaptureRepository?, viewer: String, modifier: Modifier = Modifier) {
    // 与 Web 端 `MeetingRecordWorkspace` 的「录音信息」同一版式:两列键值表 ——
    // 左列定宽、值左对齐成一列。原来是「标签一行、值一行」堆三对,读起来是三段
    // 独立文字,扫不出「哪一行的值是什么」。
    // 采集会话的状态只有录音件才有(线上会议没有 capture_id),读的就是 Web 那一次读。
    val captureId = record.captureId
    val capture = if (captures == null || captureId == null) null
    else visibleRead(viewer, captureId, record.revision) { captures.read(viewer, captureId) }
    Column(
        modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        InfoRow(R.string.records_source_filter, stringResource(recordSourceLabel(record)))
        InfoRow(R.string.records_start_time, recordTime(record.originAt))
        InfoRow(R.string.records_retention, stringResource(retentionLabel(record.retentionMode)))
        // 进行中的记录靠这两行才看得出「现在录到哪一步」——列表里只有一条「进行中」徽标。
        capture?.getOrNull()?.let {
            Text(stringResource(captureStatusLabel(it.status)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Web 的「录音信息」还有这一句:`source_available:false` 就直说来源没了,
        // 别让用户对着空原文猜是网络问题还是素材真的丢了。
        if (!record.sourceAvailable) {
            Text(stringResource(R.string.records_source_missing),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 采集会话状态:Web `library.captureStatus.*` 的六档。未知状态按「已结束」兜底。 */
private fun captureStatusLabel(status: String): Int = when (status) {
    "preparing" -> R.string.records_capture_preparing
    "recording" -> R.string.records_capture_recording
    "paused" -> R.string.records_capture_paused
    "interrupted" -> R.string.records_capture_interrupted
    "stopping" -> R.string.records_capture_stopping
    else -> R.string.records_capture_stopped
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
        // 先把这一页的口径说清楚(Web 的 library.speakersHint):这里只列转写里已有的标签,
        // 不会把未识别的声音自动挂到成员上 —— 不说这句,用户会以为少了人。
        Text(stringResource(R.string.records_speakers_hint),
            Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
