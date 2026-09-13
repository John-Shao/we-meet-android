package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.dto.CaptureAsrJobDto
import com.we.meet.data.api.dto.CaptureAsrRequestDto
import com.we.meet.data.api.dto.CaptureDto
import com.we.meet.data.capture.CaptureTranscriptionCoordinator
import com.we.meet.data.capture.CaptureRetention
import com.we.meet.data.capture.MeetingIntent
import com.we.meet.data.capture.MeetingIntentStore
import com.we.meet.data.repository.CaptureTranscriptionRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun CaptureAsrPanel(viewer: String, capture: CaptureDto, repository: CaptureTranscriptionRepository, currentViewer: () -> String?) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var retry by remember(viewer, capture.id) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, capture.id) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, capture.id) { mutableStateOf<CaptureTranscriptionCoordinator?>(null) }
    var pending by remember(viewer, capture.id) { mutableStateOf<MeetingIntent?>(null) }
    var storageError by remember(viewer, capture.id) { mutableStateOf(false) }
    var operationError by remember(viewer, capture.id) { mutableStateOf(false) }
    var busy by remember(viewer, capture.id) { mutableStateOf(false) }
    var allowIncomplete by remember(viewer, capture.id) { mutableStateOf(false) }
    var action by remember(viewer, capture.id) { mutableStateOf<Job?>(null) }
    LaunchedEffect(viewer, capture.id, storageRetry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = CaptureTranscriptionCoordinator(viewer, requireNotNull(store), repository)
                pending = controller.pending(capture.id)
                storageError = false
                coordinator = controller
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                coordinator = null
                withContext(NonCancellable) {
                    action?.cancelAndJoin()
                    withContext(Dispatchers.IO) { store?.close() }
                }
                action = null
                pending = null
                busy = false
            }
        }
    }
    val query = visibleRead(viewer, capture.id, retry, intervalMs = 5000) { repository.state(viewer, capture.id) }
    val state = query?.getOrNull()
    val latest = state?.results?.firstOrNull()
    val active = latest?.status in setOf("queued", "running")
    val live = capture.status != "stopped"
    val textMode = capture.audioRetention?.mode == "text"
    val available = state != null && (if (live) state.liveAvailable && capture.status in setOf("recording", "paused", "interrupted") else state.available) &&
        (!textMode || CaptureRetention.canStartTranscription(state.audioRetention))

    fun operate(cancel: Boolean) {
        val controller = coordinator ?: return
        if (busy || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (cancel && (!active || latest == null || pending != null)) return
        if (!cancel && pending == null && (!available || active)) return
        if (!cancel && pending == null && textMode && !CaptureRetention.canStartTranscription(state?.audioRetention)) return
        busy = true
        operationError = false
        action = scope.launch {
            try {
                if (cancel) repository.cancel(viewer, capture.id, requireNotNull(latest).id).getOrThrow()
                else controller.submit(capture.id, CaptureAsrRequestDto(latest?.id, allowIncomplete, live))
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { operationError = true }
            finally {
                if (coordinator === controller && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { pending = controller.pending(capture.id) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { storageError = true }
                    busy = false
                    retry++
                }
            }
        }
    }

    if (state != null && !state.available && !state.liveAvailable && state.results.isEmpty() && pending == null && !storageError) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.capture_asr_title), style = MaterialTheme.typography.titleMedium)
            if (query == null || (coordinator == null && !storageError)) WeMeetInlineLoading()
            if (query?.isFailure == true || storageError) {
                WeMeetInlineErrorState(onRetry = { retry++; if (storageError) storageRetry++ }, message = stringResource(R.string.capture_asr_read_error))
            } else if (state != null) {
                Text(stringResource(R.string.capture_asr_usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                latest?.let { Text(stringResource(asrStatus(it.status)), style = MaterialTheme.typography.labelLarge) }
                if (pending != null) Text(stringResource(R.string.capture_asr_unknown), style = MaterialTheme.typography.bodyMedium)
                if (operationError) Text(stringResource(R.string.capture_asr_operation_error), color = MaterialTheme.colorScheme.error)
                if (!active && pending == null && available) Row {
                    Checkbox(allowIncomplete, { allowIncomplete = it }, enabled = !busy)
                    Text(stringResource(R.string.capture_asr_allow_incomplete), Modifier.padding(top = Dimens.SpaceM), style = MaterialTheme.typography.bodyMedium)
                }
                if (pending != null || available && !active) Button(onClick = { operate(false) }, enabled = !busy && coordinator != null && !storageError) {
                    Text(stringResource(if (pending != null) R.string.capture_asr_reconcile else if (live) R.string.capture_asr_start_live else R.string.capture_asr_start_sealed))
                }
                if (active && pending == null) TextButton(onClick = { operate(true) }, enabled = !busy && coordinator != null) {
                    Text(stringResource(R.string.capture_asr_cancel))
                }
                if (busy) WeMeetInlineLoading()
                if (latest?.mode == "live") key(viewer, capture.id, latest.id) {
                    CaptureAsrPreview(viewer, capture.id, latest, repository)
                }
            }
        }
    }
}

private fun asrStatus(status: String) = when (status) {
    "queued" -> R.string.capture_asr_queued
    "running" -> R.string.capture_asr_running
    "succeeded" -> R.string.capture_asr_succeeded
    "incomplete" -> R.string.capture_asr_incomplete
    "canceled" -> R.string.capture_asr_canceled
    else -> R.string.capture_asr_read_error
}

@Composable
private fun CaptureAsrPreview(viewer: String, capture: String, job: CaptureAsrJobDto, repository: CaptureTranscriptionRepository) {
    var older by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val after = older ?: maxOf(0, job.finalCount - 50)
    val result = visibleRead(viewer, capture, job.id, after, retry, intervalMs = 3000) { repository.preview(viewer, capture, job.id, after) }
    val preview = result?.getOrNull()
    if (result?.isFailure == true) WeMeetInlineErrorState(onRetry = { retry++ }, message = stringResource(R.string.capture_asr_read_error))
    else if (preview == null) WeMeetInlineLoading()
    else {
        Text(stringResource(R.string.capture_asr_preview_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (preview.results.isEmpty()) Text(stringResource(R.string.capture_asr_waiting_text))
        preview.results.forEach { row ->
            val seconds = row.startMs / 1000
            Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(row.text, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            if (after > 0) TextButton(onClick = { older = maxOf(0, after - 50) }) { Text(stringResource(R.string.capture_asr_previous)) }
            if (preview.nextAfterSequence != null) TextButton(onClick = { older = preview.nextAfterSequence }) { Text(stringResource(R.string.capture_asr_next)) }
            if (older != null) TextButton(onClick = { older = null }) { Text(stringResource(R.string.capture_asr_latest)) }
        }
    }
}
