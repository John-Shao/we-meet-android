@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.CaptureRepository
import com.we.meet.data.repository.CaptureTranscriptionRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
/** The record owns sealed-file transcription, including retries and temporary-audio cleanup. */
@Composable
internal fun RecordCaptureTools(
    viewer: String,
    record: RecordDto,
    captures: CaptureRepository,
    transcriptions: CaptureTranscriptionRepository,
    currentViewer: () -> String?,
    onRefresh: () -> Unit,
) {
    // 入口按钮自己持有开合状态;要把它放进菜单时用 [RecordCaptureToolsSheet]——
    // 菜单项一旦被点就随菜单一起从组合里移除,**内置状态会跟着销毁**,弹层永远开不出来。
    var open by remember(viewer, record.id) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
        Text(stringResource(R.string.records_transcription_manage))
    }
    if (open) RecordCaptureToolsSheet(viewer, record, captures, transcriptions, currentViewer,
        onClose = { open = false; onRefresh() })
}

/**
 * 「转写管理」底部弹层本体 —— 与入口分开,调用方自己决定何时显示它。
 *
 * 分成两半的原因见上:需要「菜单项只负责开、面板作为兄弟节点常驻」的场景,
 * 不能把状态放在菜单项里。
 */
@Composable
internal fun RecordCaptureToolsSheet(
    viewer: String,
    record: RecordDto,
    captures: CaptureRepository,
    transcriptions: CaptureTranscriptionRepository,
    currentViewer: () -> String?,
    onClose: () -> Unit,
) {
    val captureId = record.captureId ?: return
    if (!record.capabilities.readTranscript || !record.capabilities.controlCapture) return
    var retry by remember(viewer, record.id) { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onClose) {
        val result = visibleRead(viewer, record.id, captureId, retry, intervalMs = 5000) {
            captures.read(viewer, captureId).mapCatching { capture ->
                require(capture.recordId == record.id)
                capture
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.records_transcription_manage), style = MaterialTheme.typography.titleLarge)
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState(onRetry = { retry++ }, message = stringResource(R.string.records_unavailable))
                else -> {
                    val capture = result.getOrThrow()
                    if (record.retentionMode == "text") CaptureRetentionPanel(viewer, capture, transcriptions)
                    CaptureAsrPanel(viewer, capture, transcriptions, currentViewer)
                }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.records_close)) }
        }
    }
}
