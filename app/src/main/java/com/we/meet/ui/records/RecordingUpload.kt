package com.we.meet.ui.records

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.data.api.RecordingUploadCapabilities
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun RecordingUploadAction(repository: RecordingUploadRepository, viewer: String, onRecord: (String) -> Unit, modifier: Modifier = Modifier) {
    // Capabilities contain no record content. Keep them while the system document picker pauses us.
    val capabilities by produceState<Result<RecordingUploadCapabilities>?>(null, repository, viewer) {
        value = repository.capabilities(viewer)
    }
    val config = capabilities?.getOrNull()?.takeIf { it.available } ?: return
    var open by rememberSaveable(viewer) { mutableStateOf(false) }
    var uri by rememberSaveable(viewer) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(viewer) { mutableStateOf("") }
    var size by rememberSaveable(viewer) { mutableStateOf<Long?>(null) }
    var key by rememberSaveable(viewer) { mutableStateOf(UUID.randomUUID().toString()) }
    var context by rememberSaveable(viewer) { mutableStateOf("") }
    var hotwords by rememberSaveable(viewer) { mutableStateOf("") }
    var advanced by rememberSaveable(viewer) { mutableStateOf(false) }
    var busy by remember(viewer) { mutableStateOf(false) }
    var error by remember(viewer) { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val jobs = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) jobs.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    resolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { rows ->
                        check(rows.moveToFirst())
                        val length = rows.getColumnIndex(OpenableColumns.SIZE)
                        rows.getString(rows.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to
                            if (length >= 0 && !rows.isNull(length)) rows.getLong(length).takeIf { it >= 0 } else null
                    } ?: error("Missing document")
                }
                name = metadata.first; size = metadata.second; uri = selected.toString()
                key = UUID.randomUUID().toString(); error = false
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = true }
        }
    }
    OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = Dimens.MinTouchTarget), shape = CircleShape) {
        Icon(Icons.Outlined.UploadFile, null, Modifier.size(Dimens.ComponentIconMedium)); Spacer(Modifier.width(Dimens.SpaceS))
        Text(stringResource(R.string.records_upload))
    }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false },
        title = { Text(stringResource(R.string.record_upload_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                Text(stringResource(R.string.record_upload_hint, config.maxBytes / 1024 / 1024))
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*", "application/ogg")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(name.ifBlank { stringResource(R.string.record_upload_choose) })
                }
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.record_upload_advanced)) }
                if (advanced) {
                    OutlinedTextField(context, onValueChange = { context = it.take(400); key = UUID.randomUUID().toString() }, enabled = !busy,
                        label = { Text(stringResource(R.string.record_upload_context)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(hotwords, onValueChange = { hotwords = it.take(4000); key = UUID.randomUUID().toString() }, enabled = !busy,
                        label = { Text(stringResource(R.string.record_upload_hotwords)) }, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.record_upload_consent), style = MaterialTheme.typography.bodySmall)
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.record_upload_wait)) }
                if (error) Text(stringResource(R.string.record_upload_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = uri != null && !busy, onClick = {
                val document = Uri.parse(uri ?: return@TextButton)
                busy = true; error = false
                jobs.launch {
                    try {
                        val result = repository.upload(viewer, key, name, size, config, context, hotwords) {
                            resolver.openInputStream(document) ?: error("Document unavailable")
                        }
                        if (result.isSuccess) { open = false; uri = null; name = ""; onRecord(result.getOrThrow().recordId) }
                        else error = true
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.record_upload_submit)) }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = { open = false }) { Text(stringResource(R.string.records_close)) }
        })
}

@Composable
internal fun RecordingUploadStatus(repository: RecordingUploadRepository, viewer: String, recordId: String) {
    var refresh by remember(viewer, recordId) { mutableIntStateOf(0) }
    var busy by remember(viewer, recordId) { mutableStateOf(false) }
    var error by remember(viewer, recordId) { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    val state = visibleRead(repository, viewer, recordId, refresh, intervalMs = 5_000,
        stopWhen = { it.status == "succeeded" || it.status == "failed" }) { repository.state(viewer, recordId) }
    val result = state?.getOrNull()
    if (result?.status == "succeeded") return
    Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        when {
            state == null -> WeMeetInlineLoading()
            state.isFailure -> Text(stringResource(R.string.records_unavailable))
            else -> Text(stringResource(when (result?.status) {
                "failed" -> R.string.record_upload_failed
                "queued" -> R.string.record_upload_queued
                else -> R.string.record_upload_transcribing
            }))
        }
        if (result?.status == "failed" && result.retryable) TextButton(enabled = !busy, onClick = {
            busy = true; error = false
            jobs.launch { try { error = repository.retry(viewer, recordId, result.attempt).isFailure; refresh++ } finally { busy = false } }
        }) { Text(stringResource(R.string.record_upload_retry)) }
        if (state?.isFailure == true) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
        if (error) Text(stringResource(R.string.record_upload_error), color = MaterialTheme.colorScheme.error)
    }
}
