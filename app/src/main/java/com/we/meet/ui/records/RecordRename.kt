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

@Composable
internal fun RecordRenameAction(repository: MeetingRecordRepository, viewer: String, record: RecordDto, onRenamed: () -> Unit) {
    if (!record.capabilities.rename || record.sourceType != "audio_recording" || record.isOngoing) return
    key(viewer, record.id) {
        var open by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf(record.title) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        TextButton(onClick = { draft = record.title; failed = false; open = true }) {
            Text(stringResource(R.string.record_rename))
        }
        if (open) AlertDialog(
            onDismissRequest = { if (!busy) open = false },
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
                            if (result.isSuccess) { open = false; onRenamed() }
                            else failed = true
                        } finally { busy = false }
                    }
                }) { Text(stringResource(if (busy) R.string.record_renaming else R.string.record_rename_save)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { open = false }) { Text(stringResource(R.string.capture_keep_recording)) }
            },
        )
    }
}
