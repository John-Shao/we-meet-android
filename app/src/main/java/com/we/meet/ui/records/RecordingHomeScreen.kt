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
        // 两个入口平铺整行：与「视频会议」区的三个入口同一骨架，右侧不留空档。
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceS, bottom = Dimens.SpaceM),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXl)) {
            ActionCard(Icons.Outlined.Mic, stringResource(R.string.records_start_recording),
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                onCapture, Modifier.weight(1f))
            Box(Modifier.weight(1f)) { importAction(Modifier.fillMaxWidth()) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (historyEnabled) {
            val rows = result?.getOrNull()?.take(RECORDING_HISTORY_LIMIT).orEmpty()
            Column(Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
                MeetingListSectionTitle(stringResource(R.string.recording_history))
                when {
                    result == null -> WeMeetInlineLoading()
                    result.isFailure -> WeMeetInlineErrorState(onRetry = onRetry, message = stringResource(R.string.records_unavailable))
                    rows.isEmpty() -> Text(stringResource(R.string.recording_history_empty),
                        Modifier.padding(Dimens.ScreenPadding), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> rows.forEach { record -> key(record.id) {
                        MeetingListItem(record.title.ifBlank { stringResource(R.string.home_ai_recording) },
                            // 与「会议实录」页同一条读数:时间 · 来源 · 上传状态,一行读完。
                            // Web 端两个列表页共用这一版式(「同一条记录在两个栏目里不该是
                            // 两种版式」),上传处理状态在这一页也要看得见。
                            listOfNotNull(
                                recordTime(record.originAt),
                                stringResource(recordingSourceLabel(record)),
                                record.upload?.let { stringResource(uploadStatusLabel(it.status)) },
                            ).joinToString(" · "),
                            if (record.upload?.mediaType == "video") Icons.Outlined.Videocam else Icons.Outlined.Mic, { onDetail(record.id) })
                    } }
                }
                // 列表为空时「更多」无处可去，不显示。
                if (rows.isNotEmpty()) TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.video_more)) }
            }
        }
    }
}

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
