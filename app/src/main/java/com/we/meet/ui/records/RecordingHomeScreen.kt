@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import com.we.meet.data.repository.RecordingUploadRepository
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordSource
import com.we.meet.ui.components.*
import com.we.meet.ui.home.ActionCard
import com.we.meet.ui.home.MeetingListItem
import com.we.meet.ui.home.MeetingListSectionTitle
import com.we.meet.ui.theme.Dimens
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 历史录音最多显示的条数。两处裁剪（入站映射与渲染）必须同源；Web 端
 * `RecordingOverview` 的 `HISTORY_LIMIT` 是同一个数。
 */
private const val RECORDING_HISTORY_LIMIT = 20

/** Entering the module only reads history; capture starts in its own destination. */
@Composable
fun RecordingHomeScreen(
    repository: MeetingRecordRepository, viewer: String, historyEnabled: Boolean,
    onOpenNavDrawer: () -> Unit, onCapture: () -> Unit,
    onDetail: (String) -> Unit, onMore: () -> Unit,
    uploadRepository: RecordingUploadRepository? = null,
) {
    var refresh by remember(viewer) { mutableIntStateOf(0) }
    val result = if (historyEnabled) visibleRead(viewer, refresh) {
        repository.records(viewer, source = RecordSource.RECORDINGS, isOngoing = false)
            .map { it.results.take(RECORDING_HISTORY_LIMIT) }
    } else null
    RecordingHomeContent(result, historyEnabled, onOpenNavDrawer, onCapture, onDetail, onMore, { refresh++ },
        importAction = { modifier -> if (uploadRepository != null) RecordingUploadAction(uploadRepository, viewer, onDetail, modifier, tile = true) })
}

@Composable
internal fun RecordingHomeContent(
    result: Result<List<RecordDto>>?, historyEnabled: Boolean,
    onOpenNavDrawer: () -> Unit, onCapture: () -> Unit,
    onDetail: (String) -> Unit, onMore: () -> Unit, onRetry: () -> Unit,
    importAction: @Composable (Modifier) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WeMeetTopBar(stringResource(R.string.home_ai_recording), onMenu = onOpenNavDrawer,
            menuDescription = stringResource(R.string.meeting_navigation),
            containerColor = MaterialTheme.colorScheme.background)
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceS, bottom = Dimens.SpaceM)) {
            ActionCard(Icons.Outlined.Mic, stringResource(R.string.records_start_recording),
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                onCapture, Modifier.weight(1f))
            Box(Modifier.weight(1f)) { importAction(Modifier.fillMaxWidth()) }
            Spacer(Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (historyEnabled) Column(Modifier.weight(1f).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
            MeetingListSectionTitle(stringResource(R.string.recording_history))
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState(onRetry = onRetry, message = stringResource(R.string.records_unavailable))
                else -> {
                    val rows = result.getOrThrow().take(RECORDING_HISTORY_LIMIT)
                    if (rows.isEmpty()) Text(stringResource(R.string.recording_history_empty),
                        Modifier.padding(Dimens.ScreenPadding), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rows.forEach { record -> key(record.id) {
                        MeetingListItem(record.title.ifBlank { stringResource(R.string.home_ai_recording) },
                            listOfNotNull(stringResource(recordingSourceLabel(record)), recordingDate(record.originAt),
                                record.upload?.let { stringResource(uploadStatusLabel(it.status)) }).joinToString(" · "),
                            if (record.upload?.mediaType == "video") Icons.Outlined.Videocam else Icons.Outlined.Mic, { onDetail(record.id) })
                    } }
                }
            }
            TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.video_more)) }
        }
    }
}

internal fun recordingDate(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))
}.getOrDefault("")

internal fun recordingSourceLabel(record: RecordDto): Int = when {
    record.sourceType != "upload" -> R.string.home_ai_recording
    record.upload?.mediaType == "video" -> R.string.record_import_video
    else -> R.string.record_import_audio
}

internal fun uploadStatusLabel(status: String): Int = when (status) {
    "succeeded" -> R.string.record_import_ready
    "failed" -> R.string.record_upload_failed
    "queued" -> R.string.record_import_pending
    else -> R.string.record_import_processing
}
