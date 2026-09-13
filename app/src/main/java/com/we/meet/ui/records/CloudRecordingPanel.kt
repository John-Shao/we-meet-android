package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.dto.CloudRecordingRequestDto
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CloudRecordingRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*

@Composable
internal fun CloudRecordingPanel(viewer: String, roomId: String, sid: String,
    repository: CloudRecordingRepository, currentViewer: () -> String?, isCurrentSource: () -> Boolean) {
    if (viewer.isBlank()) {
        Text(stringResource(R.string.cloud_recording_guest), Modifier.padding(Dimens.ScreenPadding))
        return
    }
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentSource by rememberUpdatedState(isCurrentSource)
    val getViewer by rememberUpdatedState(currentViewer)
    var refresh by remember(viewer, roomId, sid) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, roomId, sid) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, roomId, sid) { mutableStateOf<CloudRecordingCoordinator?>(null) }
    var pending by remember(viewer, roomId, sid) { mutableStateOf<MeetingIntent?>(null) }
    var storageError by remember(viewer, roomId, sid) { mutableStateOf(false) }
    var busy by remember(viewer, roomId, sid) { mutableStateOf(false) }
    var error by remember(viewer, roomId, sid) { mutableStateOf(false) }
    var confirmation by remember(viewer, roomId, sid) { mutableStateOf<CloudRecordingRequestDto?>(null) }
    var action by remember(viewer, roomId, sid) { mutableStateOf<Job?>(null) }
    val read = visibleRead(viewer, roomId, sid, repository, refresh, intervalMs = 5000) {
        if (!currentSource()) Result.failure(IllegalStateException("Meeting changed"))
        else repository.state(viewer, roomId, sid).map { check(currentSource()); it }
    }
    val state = read?.getOrNull()
    LaunchedEffect(viewer, roomId, sid, repository, lifecycle, storageRetry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer) { getViewer() } }
                val controller = CloudRecordingCoordinator(viewer, requireNotNull(store), repository)
                pending = controller.pending(roomId, sid); storageError = false; coordinator = controller
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                coordinator = null
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; busy = false; pending = null; confirmation = null
            }
        }
    }
    fun matches(request: CloudRecordingRequestDto): Boolean = state != null &&
        request.expectedRecordingId == state.current?.id &&
        (if (request.operation == "start") state.canStart else state.canStop)

    fun submit(reconcile: Boolean) {
        val controller = coordinator ?: return
        if (busy || storageError || state == null || getViewer() != viewer || !currentSource() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = if (reconcile) runCatching { CloudRecordingRepository.requestAdapter.fromJson(requireNotNull(pending).body) }.getOrNull() else confirmation
        if (request == null || !reconcile && !matches(request)) { error = true; return }
        busy = true; error = false
        action = scope.launch {
            try { controller.control(request); confirmation = null }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { error = true; confirmation = null }
            finally {
                if (coordinator === controller && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { pending = controller.pending(roomId, sid) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { storageError = true }
                    busy = false; refresh++
                }
            }
        }
    }
    val enabled = coordinator != null && !busy && !storageError && currentSource() && getViewer() == viewer
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.cloud_recording_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cloud_recording_description), style = MaterialTheme.typography.bodySmall)
        when {
            read == null -> WeMeetInlineLoading()
            read.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.cloud_recording_unavailable))
            state != null -> {
                Text(stringResource(if (pending != null) R.string.cloud_recording_status_unknown else cloudRecordingStatus(state.current?.status)), style = MaterialTheme.typography.labelLarge)
                if (storageError) WeMeetInlineErrorState(onRetry = { storageRetry++ }, message = stringResource(R.string.cloud_recording_storage_error))
                when {
                    pending != null -> {
                        Text(stringResource(R.string.cloud_recording_unknown))
                        Button(onClick = { submit(true) }, enabled = enabled) { Text(stringResource(R.string.cloud_recording_reconcile)) }
                    }
                    state.pendingOperation != null -> Text(stringResource(if (state.pendingOperation.state == "unknown") R.string.cloud_recording_worker_unknown else R.string.cloud_recording_processing))
                    state.canStop -> Button(onClick = { confirmation = CloudRecordingRequestDto(roomId, sid, "stop", state.current?.id) }, enabled = enabled) { Text(stringResource(R.string.cloud_recording_stop)) }
                    state.canStart -> Button(onClick = { confirmation = CloudRecordingRequestDto(roomId, sid, "start", state.current?.id) }, enabled = enabled) { Text(stringResource(R.string.cloud_recording_start)) }
                    state.blocked -> Text(stringResource(R.string.cloud_recording_blocked))
                    state.needsAttention -> Text(stringResource(R.string.cloud_recording_worker_unknown))
                    !state.available -> Text(stringResource(R.string.cloud_recording_disabled))
                }
                if (error) Text(stringResource(R.string.cloud_recording_error), color = MaterialTheme.colorScheme.error)
                if (busy) WeMeetInlineLoading()
            }
        }
    }
    confirmation?.let { request ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(stringResource(if (request.operation == "start") R.string.cloud_recording_start else R.string.cloud_recording_stop)) },
            text = { Text(stringResource(if (request.operation == "start") R.string.cloud_recording_start_effect else R.string.cloud_recording_stop_effect)) },
            confirmButton = { TextButton(onClick = { submit(false) }, enabled = enabled && pending == null && matches(request)) { Text(stringResource(R.string.cloud_recording_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.records_close)) } })
    }
}

private fun cloudRecordingStatus(status: String?) = when (status) {
    "initiated" -> R.string.cloud_recording_starting
    "active" -> R.string.cloud_recording_active
    "stopped" -> R.string.cloud_recording_saving
    "saved", "notification_succeeded" -> R.string.cloud_recording_saved
    "aborted", "failed_to_start" -> R.string.cloud_recording_failed
    "failed_to_stop" -> R.string.cloud_recording_worker_unknown
    else -> R.string.cloud_recording_off
}
