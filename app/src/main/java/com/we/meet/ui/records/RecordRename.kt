package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

@Composable
internal fun CaptureSavedRecordTitle(repository: MeetingRecordRepository, viewer: String, recordId: String) {
    var refresh by remember(viewer, recordId) { mutableIntStateOf(0) }
    val result = visibleRead(viewer, recordId, refresh) { repository.record(viewer, recordId) }
    if (result == null) com.we.meet.ui.components.WeMeetInlineLoading()
    if (result?.isFailure == true) com.we.meet.ui.components.WeMeetInlineErrorState(
        message = stringResource(R.string.records_unavailable), onRetry = { refresh++ })
    result?.getOrNull()?.let { record ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(record.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            RecordRenameAction(repository, viewer, record) { refresh++ }
        }
    }
}

/** 谁能重命名:后端能力 + 这两类有标题的来源 + 不在录制中。三处入口共用这一条。 */
internal fun canRenameRecord(record: RecordDto): Boolean =
    record.capabilities.rename && record.sourceType in listOf("audio_recording", "upload") && !record.isOngoing

/**
 * 重命名的入口按钮。真正的对话框在 [RecordRenameDialog] —— 页面头部的三点菜单
 * 也需要同一份对话框,所以两者拆开,别让菜单项去嵌一颗按钮。
 */
@Composable
internal fun RecordRenameAction(repository: MeetingRecordRepository, viewer: String, record: RecordDto, onRenamed: () -> Unit) {
    if (!canRenameRecord(record)) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text(stringResource(R.string.record_rename))
    }
    if (open) RecordRenameDialog(repository, viewer, record, onClose = { open = false }, onRenamed = onRenamed)
}

/**
 * 重命名对话框本体。
 *
 * 调用方负责决定它何时出现([onClose] 在取消/成功后都会被调到),所以三处入口
 * (实录页的三点菜单、录制完成页的标题行、[RecordRenameAction])都能直接用。
 */
@Composable
internal fun RecordRenameDialog(repository: MeetingRecordRepository, viewer: String, record: RecordDto, onClose: () -> Unit, onRenamed: () -> Unit) {
    key(viewer, record.id) {
        var draft by remember { mutableStateOf(record.title) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!busy) onClose() },
            title = { Text(stringResource(R.string.record_rename)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                    OutlinedTextField(draft, { draft = it.take(500); failed = false }, singleLine = true,
                        enabled = !busy, label = { Text(stringResource(R.string.record_name)) })
                    if (failed) Text(stringResource(R.string.record_rename_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && draft.trim().isNotEmpty() && draft.trim() != record.title, onClick = {
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            val result = repository.rename(viewer, record.id, draft, record.title)
                            if (result.isSuccess) { onClose(); onRenamed() }
                            else failed = true
                        } finally { busy = false }
                    }
                }) { Text(stringResource(if (busy) R.string.record_renaming else R.string.record_rename_save)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.capture_keep_recording)) }
            },
        )
    }
}
