package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.OnlineCaptureRepository
import com.we.meet.data.repository.OnlineCaptureNoticeRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.*

@Composable
internal fun OnlineCapturePanel(viewer: String, roomId: String, sid: String, repository: OnlineCaptureRepository,
    currentViewer: () -> String?, isCurrentSource: () -> Boolean, onRecord: (String) -> Unit) {
    if (viewer.isBlank()) { Text(stringResource(R.string.online_capture_guest)); return }
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentSource by rememberUpdatedState(isCurrentSource)
    var refresh by remember(viewer, roomId, sid) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, roomId, sid) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, roomId, sid) { mutableStateOf<OnlineCaptureCoordinator?>(null) }
    var pending by remember(viewer, roomId, sid) { mutableStateOf<MeetingIntent?>(null) }
    var storageError by remember(viewer, roomId, sid) { mutableStateOf(false) }
    var busy by remember(viewer, roomId, sid) { mutableStateOf(false) }
    var error by remember(viewer, roomId, sid) { mutableStateOf(false) }
    var confirmation by remember(viewer, roomId, sid) { mutableStateOf<OnlineCaptureRequestDto?>(null) }
    var action by remember(viewer, roomId, sid) { mutableStateOf<Job?>(null) }
    val read = visibleRead(viewer, roomId, sid, refresh, intervalMs = 5000) {
        if (!currentSource()) Result.failure(IllegalStateException("Meeting changed")) else repository.state(viewer, roomId, sid).map { check(currentSource()); it }
    }
    val state = read?.getOrNull()
    LaunchedEffect(viewer, roomId, sid, lifecycle, storageRetry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = OnlineCaptureCoordinator(viewer, requireNotNull(store), repository)
                pending = controller.pending(roomId, sid); storageError = false; coordinator = controller; awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                coordinator = null
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; busy = false; pending = null; confirmation = null
            }
        }
    }
    fun submit(reconcile: Boolean) {
        val controller = coordinator ?: return
        if (busy || storageError || state?.canControl != true || currentViewer() != viewer || !currentSource() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = if (reconcile) runCatching { OnlineCaptureRepository.requestAdapter.fromJson(requireNotNull(pending).body) }.getOrNull() else confirmation
        if (request == null || !reconcile && (request.expectedRunId != state.current?.id || request.operation == "start" && !state.available)) { error = true; return }
        busy = true; error = false
        action = scope.launch {
            try { controller.control(request); confirmation = null }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { error = true }
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
    val current = state?.current
    val active = current?.state in setOf("starting", "recording", "stopping")
    val enabled = coordinator != null && !busy && !storageError && currentSource()
    Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.online_capture_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.online_capture_description), style = MaterialTheme.typography.bodySmall)
        when {
            read == null -> WeMeetInlineLoading()
            read.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.online_capture_unavailable))
            state != null -> {
                Text(stringResource(onlineCaptureStatus(current?.state ?: "off")), style = MaterialTheme.typography.labelLarge)
                if (current != null) Text(stringResource(R.string.online_capture_coverage), style = MaterialTheme.typography.bodySmall)
                current?.recordId?.let { id -> TextButton(onClick = { if (currentSource()) onRecord(id) }, enabled = !busy) { Text(stringResource(R.string.online_capture_open)) } }
                if (state.canControl) {
                    if (storageError) WeMeetInlineErrorState(onRetry = { storageRetry++ }, message = stringResource(R.string.summary_controls_storage_error))
                    if (pending != null) {
                        Text(stringResource(R.string.online_capture_unknown))
                        Button(onClick = { submit(true) }, enabled = enabled) { Text(stringResource(R.string.summary_controls_reconcile)) }
                    } else if (active || state.available) Button(onClick = {
                        confirmation = OnlineCaptureRequestDto(roomId, sid, if (active) "stop" else "start", current?.id); error = false
                    }, enabled = enabled && current?.state != "stopping") { Text(stringResource(if (active) R.string.online_capture_stop else R.string.online_capture_start)) }
                } else Text(stringResource(R.string.online_capture_manager))
                if (error) Text(stringResource(R.string.online_capture_error), color = MaterialTheme.colorScheme.error)
                if (busy) WeMeetInlineLoading()
            }
        }
    }
    if (state?.canControl == true && coordinator != null && pending == null) confirmation?.let { request ->
        val matches = request.expectedRunId == current?.id && (request.operation == "stop" && active && current?.state != "stopping" || request.operation == "start" && !active && state.available)
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text(stringResource(if (request.operation == "start") R.string.online_capture_start else R.string.online_capture_stop)) },
            text = { Text(stringResource(if (request.operation == "start") R.string.online_capture_start_effect else R.string.online_capture_stop_effect)) },
            confirmButton = { TextButton(onClick = { submit(false) }, enabled = enabled && matches) { Text(stringResource(R.string.online_capture_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.records_close)) } })
    }
}

@Composable
internal fun OnlineCaptureNotice(roomId: String, sid: String, token: String, repository: OnlineCaptureNoticeRepository, isCurrentSource: () -> Boolean) {
    var refresh by remember(roomId, sid) { mutableIntStateOf(0) }
    val credentialEpoch = remember(token) { Any() }
    val currentSource by rememberUpdatedState(isCurrentSource)
    val read = visibleRead(roomId, sid, credentialEpoch, refresh, intervalMs = 5000) { repository.notice(roomId, sid, token) { currentSource() } }
    if (read?.getOrNull()?.state == "off") return
    Surface(color = WeMeetTheme.extras.status.warningContainer, contentColor = WeMeetTheme.extras.status.onWarningContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.online_capture_title) + " · " + stringResource(read?.getOrNull()?.state?.let(::onlineCaptureStatus) ?: R.string.online_capture_unknown_state), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (read?.isFailure == true) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
        }
    }
}

private fun onlineCaptureStatus(state: String) = when (state) {
    "starting" -> R.string.online_capture_starting
    "recording" -> R.string.online_capture_recording
    "stopping" -> R.string.online_capture_stopping
    "stopped" -> R.string.online_capture_stopped
    "incomplete" -> R.string.online_capture_incomplete
    else -> R.string.online_capture_off
}
