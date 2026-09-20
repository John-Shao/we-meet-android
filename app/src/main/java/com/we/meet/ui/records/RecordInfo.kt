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
import com.we.meet.data.api.dto.RecordSpeakerActivityDto
import com.we.meet.data.repository.CaptureRepository
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.MeetingDeliveryRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens

@Composable
internal fun RecordInfo(record: RecordDto, captures: CaptureRepository?, viewer: String, modifier: Modifier = Modifier, fullDuration: Long? = mediaDuration(record)) {
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
        InfoRow(R.string.records_media_duration, fullDuration?.let(::sourceTime) ?: stringResource(R.string.records_media_duration_unknown))
        record.mediaTiming?.takeIf { it.basis == "partial_audio" && validMediaDuration(it.savedDurationMs) }?.let {
            InfoRow(R.string.records_saved_audio, sourceTime(requireNotNull(it.savedDurationMs)))
            Text(stringResource(R.string.records_media_partial), style = MaterialTheme.typography.bodySmall)
        }
        InfoRow(R.string.records_info_owner, record.owner?.takeIf { it.isNotBlank() } ?: stringResource(R.string.records_owner_unknown))
        InfoRow(R.string.records_info_created, record.createdAt?.let(::recordTime) ?: stringResource(R.string.records_owner_unknown))
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

/** Read the caller's existing receipts, without invoking export creation. */
@Composable
internal fun RecordDocuments(viewer: String, record: RecordDto, repository: MeetingDeliveryRepository,
    onDocument: (String) -> Unit, onHumanSource: ((String) -> Unit)? = null, onSource: (String) -> Unit) {
    if (!record.capabilities.readSummary) return
    var refresh by remember(viewer, record.id) { mutableIntStateOf(0) }
    var cursors by remember(viewer, record.id) { mutableStateOf(listOf<String?>(null)) }
    val read = visibleRead(viewer, record.id, record.revision, refresh, cursors.last()) { repository.exports(viewer, record.id, cursors.last()) }
    Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Text(stringResource(R.string.record_documents_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.record_documents_hint), style = MaterialTheme.typography.bodySmall)
        when {
            read == null -> WeMeetInlineLoading()
            read.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.record_export_read_error))
            else -> {
                val rows = read.getOrThrow().results
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.record_documents_empty))
                    if (read.getOrThrow().available) Text(stringResource(R.string.record_documents_create_hint))
                }
                rows.forEach { row ->
                    HorizontalDivider()
                    Text(stringResource(if (row.sourceKind == "human") R.string.record_export_human else R.string.record_export_ai))
                    Text("${recordTime(row.createdAt)} · ${row.language}", style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(exportStatus(row.status)))
                    if (row.status == "ready" && row.canOpen && row.documentId != null) {
                        TextButton(onClick = { onDocument(row.documentId) }) { Text(stringResource(R.string.record_export_open)) }
                    }
                    if (row.sourceKind == "human" && onHumanSource != null) TextButton(onClick = { onHumanSource(row.sourceId) }) { Text(stringResource(R.string.record_documents_source)) }
                    if (row.sourceKind == "ai") TextButton(onClick = { onSource(row.sourceId) }) { Text(stringResource(R.string.record_documents_source)) }
                }
                read.getOrThrow().nextCursor?.let { next ->
                    TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) }
                }
            }
        }
        if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
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

/**
 * 键值块:**标签在上、值在下**。
 *
 * 此前是「左列定宽 `Dimens.LabelColumnWidth`(88dp) + 右列值」的两列布局。左列宽度
 * 写死有两个问题:① 1.5× / 2.0× 字号下「媒体时长」这类标签会折行,右列的值就跟着
 * 错位(审计 A7 的静态推断);② 这一页的值是**可变长内容**(用户昵称、导出的文档名),
 * 竖排时它们能自然换行,横排时只能在右列里挤。
 *
 * 版式与参考稿的「会议信息」一致:标签一行小字(次要色),值一行正文色。
 */
@Composable
private fun InfoRow(label: Int, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun retentionLabel(mode: String): Int = when (mode) {
    "media" -> R.string.records_retention_media
    "text" -> R.string.records_retention_text
    else -> R.string.records_retention_unknown
}

@Composable
internal fun RecordSpeakers(repository: MeetingRecordRepository, viewer: String, record: RecordDto, modifier: Modifier = Modifier, onSource: ((Long) -> Unit)? = null, fullDuration: Long? = null) {
    var cursors by remember(viewer, record.id, record.revision) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember { mutableIntStateOf(0) }
    val result = visibleRead(viewer, record.id, record.revision, cursors.last(), refresh) { repository.speakers(viewer, record.id, record.revision, cursors.last()) }
    Column(modifier.fillMaxWidth()) {
        // 先把这一页的口径说清楚(Web 的 library.speakersHint):这里只列转写里已有的标签,
        // 不会把未识别的声音自动挂到成员上 —— 不说这句,用户会以为少了人。
        Text(stringResource(R.string.records_speakers_hint),
            Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.records_activity_basis), Modifier.padding(horizontal = Dimens.ScreenPadding), style = MaterialTheme.typography.bodySmall)
        when {
            result == null -> WeMeetInlineLoading()
            result.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_source_unavailable))
            else -> LazyColumn(contentPadding = PaddingValues(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                if (result.getOrThrow().results.isEmpty()) item { Text(stringResource(R.string.records_no_speakers)) }
                items(result.getOrThrow().results, key = { it.id }) { speaker ->
                    // 一个说话人就是一条识别器的猜测,知道现场的人可以指出他到底是谁。
                    // 服务端保留原标签,只把读者看到的名字换掉。
                    AttributableSpeakerRow(
                        repository = repository,
                        viewer = viewer,
                        recordId = record.id,
                        revision = record.revision,
                        speaker = speaker,
                        onAttributed = { refresh++ },
                    )
                    SpeakerActivity(speaker.activity, onSource, fullDuration)
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                    result.getOrThrow().nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                } }
            }
        }
    }
}

@Composable
internal fun SpeakerActivity(activity: RecordSpeakerActivityDto?, onSource: ((Long) -> Unit)? = null, fullDuration: Long? = null) {
    val duration = activity?.durationMs
    val share = activity?.sharePercent
    if (activity?.basis != "recognized_speaker_time" || activity.status !in listOf("available", "partial") || duration == null || duration < 0 || share == null || !share.isFinite() || share !in 0.0..100.0) {
        Text(stringResource(R.string.records_activity_unavailable), style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.records_activity_value, sourceTime(duration), share), style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { (share / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
        if (activity.status == "partial") Text(stringResource(R.string.records_activity_partial), style = MaterialTheme.typography.bodySmall)
        SpeakerTimeline(activity.timeline, mediaDuration = fullDuration, onSource = onSource)
    }
}
