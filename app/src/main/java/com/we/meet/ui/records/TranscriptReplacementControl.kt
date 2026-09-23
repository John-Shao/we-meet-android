package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.data.api.dto.ReplacementConfirmation
import com.we.meet.data.api.dto.ReplacementPreview
import com.we.meet.data.repository.MeetingRecordRepository
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.UUID

/**
 * 批量查找替换的**独立入口按钮** —— 旧路径,行为不变(自带按钮 + 负责恢复未确认请求)。
 * 要把它放进工具栏菜单时用 [TranscriptReplacementDialog],由调用方持有 `open`。
 */
@Composable
internal fun TranscriptReplacementControl(repository: MeetingRecordRepository, viewer: String, recordId: String, onChanged: () -> Unit) {
    // 有上次未确认的请求时自动顶开:这一步原先在对话框里做,现在 open 归调用方,
    // 得由持有它的这一层自己判。
    val pending = visibleRead(viewer, recordId) { repository.pendingReplacement(viewer, recordId) }
    TranscriptReplacementEntry(repository, viewer, recordId, onChanged, autoOpen = pending?.getOrNull() != null)
}

@Composable
private fun TranscriptReplacementEntry(repository: MeetingRecordRepository, viewer: String, recordId: String, onChanged: () -> Unit, autoOpen: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(autoOpen) }
    LaunchedEffect(autoOpen) { if (autoOpen) open = true }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.batch_correction_title)) }
    TranscriptReplacementDialog(repository, viewer, recordId, open = open, onClose = { open = false }, onChanged = onChanged)
}

/**
 * 批量查找替换对话框本体 —— 与入口分开,调用方自己决定何时显示它。
 *
 * [open] 由调用方持有:菜单项当入口时,状态必须活在菜单之外(菜单项一点就从组合里
 * 移除,状态放在里面会一起被销毁)。
 */
@Composable
internal fun TranscriptReplacementDialog(repository: MeetingRecordRepository, viewer: String, recordId: String, open: Boolean, onClose: () -> Unit, onChanged: () -> Unit) {
    key(viewer, recordId) { ReplacementEditor(repository, viewer, recordId, open, onClose, onChanged) }
}

@Composable
private fun ReplacementEditor(repository: MeetingRecordRepository, viewer: String, recordId: String, open: Boolean, onClose: () -> Unit, onChanged: () -> Unit) {
    var find by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var requestKey by rememberSaveable { mutableStateOf<String?>(null) }
    var expectedHash by rememberSaveable { mutableStateOf<String?>(null) }
    var undoId by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ReplacementPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)
    var recovered by remember { mutableStateOf(false) }
    var recoveryAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(viewer, recordId, recoveryAttempt) {
        repository.pendingReplacement(viewer, recordId).onSuccess { pending ->
            if (pending != null) {
                find = pending.find; replacement = pending.replacement
                requestKey = pending.key; expectedHash = pending.expectedHash
            }
            recovered = true
        }.onFailure { message = R.string.batch_correction_storage_error }
    }
    if (!open) return
    val history = visibleRead(viewer, recordId, refresh) { repository.replacements(viewer, recordId) }
    LaunchedEffect(history?.isFailure) { if (history?.isFailure == true) preview = null }
    val authorized = history?.isSuccess == true && recovered
    val locked = busy || requestKey != null || undoId != null || !authorized
    fun failure(error: Throwable?, applying: Boolean) {
        val code = (error as? HttpException)?.code()
        if (applying && code in listOf(400, 409)) {
            requestKey = null; expectedHash = null; preview = null
        }
        if (code in listOf(401, 403, 404)) { preview = null; refresh++; changed() }
        message = when (code) {
            409 -> R.string.batch_correction_conflict
            400 -> R.string.batch_correction_invalid
            else -> R.string.batch_correction_error
        }
    }
    fun apply() {
        if (busy || !authorized) return
        val hash = expectedHash ?: preview?.previewHash ?: return
        val id = requestKey ?: UUID.randomUUID().toString()
        requestKey = id; expectedHash = hash; busy = true; message = null
        scope.launch {
            try {
                repository.applyReplacement(viewer, recordId, ReplacementConfirmation(id, find, replacement, hash))
                    .onSuccess {
                        requestKey = null; expectedHash = null; preview = null
                        message = if (it.undone) R.string.batch_correction_undone else R.string.batch_correction_applied
                        refresh++; changed()
                    }.onFailure { failure(it, true) }
            } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) { onClose(); preview = null; undoId = null } },
        title = { Text(stringResource(R.string.batch_correction_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = Dimens.SheetContentMaxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(R.string.batch_correction_hint))
                OutlinedTextField(value = find, onValueChange = { find = it.take(200); preview = null; message = null }, enabled = !locked,
                    label = { Text(stringResource(R.string.batch_correction_find)) })
                OutlinedTextField(value = replacement, onValueChange = { replacement = it.take(200); preview = null; message = null }, enabled = !locked,
                    label = { Text(stringResource(R.string.batch_correction_replacement)) })
                if (requestKey != null) Text(stringResource(R.string.batch_correction_pending))
                else TextButton(enabled = !locked && find.isNotBlank() && find != replacement, onClick = {
                    if (!busy) {
                        busy = true; preview = null; message = null
                        scope.launch {
                            try {
                                repository.previewReplacement(viewer, recordId, find, replacement)
                                    .onSuccess { preview = it }.onFailure { failure(it, false) }
                            } finally { busy = false }
                        }
                    }
                }) { Text(stringResource(R.string.batch_correction_preview)) }
                if (authorized && requestKey == null) preview?.let { value ->
                    Text(stringResource(R.string.batch_correction_count, value.changes.size, value.occurrences))
                    value.changes.forEach { row ->
                        Text(sourceTime(row.startMs))
                        Text(stringResource(R.string.batch_correction_before) + ": " + row.before)
                        Text(stringResource(R.string.batch_correction_after) + ": " + row.after)
                    }
                }
                message?.let { Text(stringResource(it)) }
                if (!recovered && message == R.string.batch_correction_storage_error) TextButton(onClick = { message = null; recoveryAttempt++ }) { Text(stringResource(R.string.records_refresh)) }
                Text(stringResource(R.string.batch_correction_history))
                if (history?.isFailure == true) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                history?.getOrNull()?.results?.forEach { row ->
                    Text("${row.find} → ${row.replacement.ifEmpty { stringResource(R.string.batch_correction_deleted) }} · ${row.changedSegments} · ${recordTime(row.createdAt)}")
                    if (row.undone) Text(stringResource(R.string.batch_correction_undone))
                    else TextButton(enabled = !locked, onClick = { undoId = row.id; preview = null; message = null }) { Text(stringResource(R.string.batch_correction_undo)) }
                }
                if (undoId != null) {
                    Text(stringResource(R.string.batch_correction_undo_hint))
                    TextButton(enabled = !busy, onClick = { undoId = null }) { Text(stringResource(R.string.batch_correction_cancel_undo)) }
                }
            }
        },
        confirmButton = {
            when {
                undoId != null -> TextButton(enabled = !busy && authorized, onClick = {
                    val id = undoId ?: return@TextButton
                    if (!busy) {
                        busy = true; message = null
                        scope.launch {
                            try {
                                repository.undoReplacement(viewer, recordId, id).onSuccess {
                                    undoId = null; preview = null; message = R.string.batch_correction_undone; refresh++; changed()
                                }.onFailure { failure(it, false) }
                            } finally { busy = false }
                        }
                    }
                }) { Text(stringResource(R.string.batch_correction_confirm_undo)) }
                else -> TextButton(enabled = !busy && authorized && (requestKey != null || preview?.changes?.isNotEmpty() == true), onClick = { apply() }) {
                    Text(stringResource(if (requestKey != null) R.string.batch_correction_retry else R.string.batch_correction_confirm))
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onClose(); preview = null; undoId = null }) { Text(stringResource(R.string.batch_correction_close)) } },
    )
}
