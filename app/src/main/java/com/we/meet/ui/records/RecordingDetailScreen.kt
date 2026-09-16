@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens

@Composable
fun RecordingDetailScreen(repository: MeetingRecordRepository, viewer: String, recordId: String,
    onBack: () -> Unit, onRecord: (String) -> Unit, onSummary: (String) -> Unit, uploadRepository: RecordingUploadRepository? = null) {
    var refresh by remember(viewer, recordId) { mutableIntStateOf(0) }
    val result = visibleRead(viewer, recordId, refresh) {
        repository.record(viewer, recordId).mapCatching { record ->
            require(record.sourceType in listOf("audio_recording", "upload"))
            record
        }
    }
    Scaffold(topBar = { WeMeetTopBar(stringResource(R.string.recording_detail), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                else -> {
                    val record = result.getOrThrow()
                    RecordingDetailContent(record, onRecord, onSummary)
                    if (record.sourceType == "upload" && record.upload?.canControl == true && uploadRepository != null)
                        RecordingUploadStatus(uploadRepository, viewer, recordId)
                }
            }
        }
    }
}

/** No audio, transcript or summary bodies are loaded on the overview detail. */
@Composable
internal fun RecordingDetailContent(record: RecordDto, onRecord: (String) -> Unit, onSummary: (String) -> Unit) {
    Text(record.title.ifBlank { stringResource(R.string.home_ai_recording) }, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(recordingSourceLabel(record)) + " · " + recordingDate(record.originAt),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    record.upload?.let {
        Text(it.name)
        Text(android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, it.size))
        Text(stringResource(uploadStatusLabel(it.status)))
    }
    if (record.sourceType != "upload" && record.retentionMode in listOf("media", "text")) Text(stringResource(
        if (record.retentionMode == "text") R.string.capture_text_only else R.string.capture_keep_audio),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf(false, true).forEach { summary ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(if (summary) R.string.records_minutes else R.string.records_title),
                    style = MaterialTheme.typography.titleMedium)
                val allowed = if (summary) record.capabilities.readSummary else record.capabilities.readTranscript
                Text(stringResource(when {
                    !allowed -> R.string.video_material_unavailable
                    !summary -> R.string.video_record_hint
                    record.hasSummary -> R.string.records_minutes_ready
                    else -> R.string.video_summary_pending
                }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (allowed) TextButton(onClick = { if (summary) onSummary(record.id) else onRecord(record.id) }) {
                    Text(stringResource(if (summary) R.string.video_view_summary else R.string.video_view_record))
                }
            }
        }
    }
}
