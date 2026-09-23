@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    /**
     * 入口的宿主。默认是一颗独占一行的 `TextButton`(原样保留,面板自己的用例靠它);
     * 传了 [trigger] 就改成「调用方给行,自己管开合」—— 实录页把它放进逐字稿的溢出菜单,
     * 免得页面头部为它多占一整行。
     */
    trigger: (@Composable (open: () -> Unit) -> Unit)? = null,
) {
    val captureId = record.captureId ?: return
    if (!record.capabilities.readTranscript || !record.capabilities.controlCapture) return
    var open by remember(viewer, record.id) { mutableStateOf(false) }
    var retry by remember(viewer, record.id) { mutableIntStateOf(0) }
    if (trigger == null) TextButton(onClick = { open = true }) { Text(stringResource(R.string.records_transcription_manage)) }
    else trigger { open = true }
    if (open) ModalBottomSheet(onDismissRequest = { open = false; onRefresh() }) {
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
            TextButton(onClick = { open = false; onRefresh() }) { Text(stringResource(R.string.records_close)) }
        }
    }
}
