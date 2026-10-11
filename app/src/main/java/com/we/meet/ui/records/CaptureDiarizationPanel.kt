package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CaptureDiarizationRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*

@Composable
internal fun CaptureDiarizationPanel(repository: CaptureDiarizationRepository, viewer: String, capture: String,
    editing: Boolean = false, onChanged: () -> Unit) {
    val client = remember(repository, viewer, capture) { runCatching { repository.open(viewer, capture) }.getOrNull() } ?: return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val changed by rememberUpdatedState(onChanged)
    val currentlyEditing by rememberUpdatedState(editing)
    var retry by remember(client) { mutableIntStateOf(0) }
    var controller by remember(client) { mutableStateOf<CaptureDiarizationController?>(null) }
    var storageError by remember(client) { mutableStateOf(false) }
    var allowed by remember(client) { mutableStateOf(client.allowed()) }
    var operation by remember(client) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client, lifecycle, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            var current: CaptureDiarizationController? = null
            try {
                if (!client.allowed()) return@repeatOnLifecycle
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer) { viewer.takeIf { client.allowed() } } }
                current = CaptureDiarizationController(SessionDiarizationOperations(client,
                    CaptureDiarizationCoordinator(client, StoredDiarizationIntent(requireNotNull(store), capture)), { changed() }))
                current.editing(currentlyEditing)
                controller = current; storageError = false
                var first = true
                while (client.allowed()) {
                    if (first || current.state.value.shouldPoll) current.refresh()
                    first = false
                    repeat(20) { delay(250); if (!client.allowed()) throw com.we.meet.data.repository.IdentityLoginChangedException() }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (client.allowed()) storageError = true }
            finally {
                allowed = client.allowed(); current?.close(); controller = null
                withContext(NonCancellable) { operation?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                operation = null
            }
        }
    }
    LaunchedEffect(client, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (client.allowed()) delay(250)
            allowed = false; controller?.close(); operation?.cancel()
        }
    }
    LaunchedEffect(controller, editing) { controller?.editing(editing) }
    if (!allowed) return
    val current = controller
    if (storageError) WeMeetInlineErrorState(onRetry = { retry++ }, message = stringResource(R.string.capture_diarization_storage))
    else if (current == null) WeMeetInlineLoading()
    else {
        val state by current.state.collectAsState()
        CaptureDiarizationContent(state, current::accept,
            onSubmit = { operation = scope.launch { current.submit() } },
            onCancel = { operation = scope.launch { current.cancel() } },
            onRefresh = { operation = scope.launch { current.refresh() } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptureDiarizationContent(state: DiarizationUiState, onAccepted: (Boolean) -> Unit,
    onSubmit: () -> Unit, onCancel: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Text(stringResource(R.string.capture_diarization_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.capture_diarization_hint), style = MaterialTheme.typography.bodyMedium)
        if (state.editing) Text(stringResource(R.string.capture_diarization_editing))
        val latest = state.data?.results?.firstOrNull()
        latest?.let { Text(stringResource(R.string.capture_diarization_version, it.generation) + " · " + stringResource(diarizationStatus(it.status))) }
        if (state.pending != null && state.failure != DiarizationFailure.UNKNOWN) Text(stringResource(R.string.capture_diarization_unknown))
        state.failure?.let { Text(stringResource(when (it) {
            DiarizationFailure.STORAGE -> R.string.capture_diarization_storage
            DiarizationFailure.UNKNOWN -> R.string.capture_diarization_unknown
            DiarizationFailure.CONFLICT -> R.string.capture_diarization_conflict
            DiarizationFailure.CANCEL -> R.string.capture_diarization_cancel_unknown
            else -> R.string.capture_diarization_denied
        }), color = MaterialTheme.colorScheme.error) }
        if (state.pending == null) Row(Modifier.fillMaxWidth().toggleable(state.accepted,
            enabled = !state.busy && !state.editing && state.ready && state.data?.canStart == true,
            role = Role.Checkbox, onValueChange = onAccepted), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(state.accepted, onCheckedChange = null, enabled = !state.busy && !state.editing && state.data?.canStart == true)
            Text(stringResource(R.string.capture_diarization_accept), style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Button(onClick = onSubmit, enabled = state.canSubmit) { Text(stringResource(if (state.pending != null) R.string.capture_diarization_recover else if (latest != null) R.string.capture_diarization_retry else R.string.capture_diarization_start)) }
            if (latest?.status in setOf("queued", "running")) TextButton(onClick = onCancel, enabled = state.canCancel) { Text(stringResource(R.string.capture_diarization_cancel)) }
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.capture_diarization_refresh)) }
        }
        if (state.busy) WeMeetInlineLoading()
        if (!state.data?.results.isNullOrEmpty()) {
            var history by remember { mutableStateOf(false) }
            TextButton(onClick = { history = !history }) { Text(stringResource(R.string.capture_diarization_history)) }
            if (history) state.data?.results?.forEach { Text(stringResource(R.string.capture_diarization_version, it.generation) + " · " + stringResource(diarizationStatus(it.status))) }
        }
    }
}
private fun diarizationStatus(value: String) = when (value) {
    "queued" -> R.string.capture_diarization_queued
    "running" -> R.string.capture_diarization_running
    "succeeded" -> R.string.capture_diarization_succeeded
    "canceled" -> R.string.capture_diarization_canceled
    else -> R.string.capture_diarization_failed
}
