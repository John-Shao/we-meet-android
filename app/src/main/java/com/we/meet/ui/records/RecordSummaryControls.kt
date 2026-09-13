package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.capture.MeetingIntentStore
import com.we.meet.data.capture.MeetingSummaryCoordinator
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.MeetingSummaryRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*

/** Only generation controls live here; permissions, current readiness and durable intent remain separate. */
@Composable
internal fun RecordSummaryControls(viewer: String, record: RecordDto, repository: MeetingSummaryRepository, currentViewer: () -> String?) {
    if (!record.capabilities.generateSummary || !record.capabilities.readSummary) return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var refresh by remember(viewer, record.id) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, record.id) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, record.id) { mutableStateOf<MeetingSummaryCoordinator?>(null) }
    var pendingSummary by remember(viewer, record.id) { mutableStateOf(false) }
    var pendingAutomation by remember(viewer, record.id) { mutableStateOf(false) }
    var storageError by remember(viewer, record.id) { mutableStateOf(false) }
    var operationError by remember(viewer, record.id) { mutableStateOf(false) }
    var busy by remember(viewer, record.id) { mutableStateOf(false) }
    var action by remember(viewer, record.id) { mutableStateOf<Job?>(null) }
    suspend fun loadPending(controller: MeetingSummaryCoordinator) {
        pendingSummary = controller.pending(record.id, MeetingIntentKind.SUMMARY_REQUEST) != null
        pendingAutomation = controller.pending(record.id, MeetingIntentKind.SUMMARY_AUTOMATION) != null
    }
    LaunchedEffect(viewer, record.id, storageRetry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = MeetingSummaryCoordinator(viewer, requireNotNull(store), repository)
                loadPending(controller)
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
                busy = false
                pendingSummary = false
                pendingAutomation = false
            }
        }
    }
    val progress = visibleRead(viewer, record.id, refresh, intervalMs = 5000) { repository.progress(viewer, record.id) }
    val automation = visibleRead(viewer, record.id, refresh, intervalMs = 10000) { repository.automation(viewer, record.id) }
    val state = progress?.getOrNull()
    val auto = automation?.getOrNull()
    val job = state?.job
    val active = job?.status in setOf("queued", "running")
    val ready = if (state?.stagedEnabled == true) state.readyStages else if (state?.generationReady == true) listOf("final") else emptyList()
    fun operate(automationChange: Boolean, stage: String = "final", retryJob: Boolean = false) {
        val controller = coordinator ?: return
        if (busy || storageError || currentViewer() != viewer || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (automationChange) {
            if (auto == null || !auto.canControl || (!pendingAutomation && !auto.available && !auto.enabled)) return
        } else if (state == null || (!pendingSummary && (active || if (retryJob) job?.retryable != true else stage !in ready))) return
        busy = true
        operationError = false
        action = scope.launch {
            try {
                if (automationChange) controller.control(record.id, SummaryAutomationRequestDto(!requireNotNull(auto).enabled, auto.revision))
                else controller.submit(record.id, SummaryRequestDto(if (retryJob) "retry" else if (job == null) "generate" else "regenerate",
                    stage, requireNotNull(state).revision, job?.id, job?.attempt))
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { operationError = true }
            finally {
                if (coordinator === controller && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { loadPending(controller) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { storageError = true }
                    busy = false
                    refresh++
                }
            }
        }
    }
    val canClick = coordinator != null && !storageError && !busy
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.summary_controls_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.summary_controls_usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (coordinator == null && !storageError) WeMeetInlineLoading()
            if (storageError) WeMeetInlineErrorState(onRetry = { storageRetry++ }, message = stringResource(R.string.summary_controls_storage_error))
            if (progress?.isFailure == true) WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.summary_controls_read_error))
            else if (state == null) WeMeetInlineLoading()
            else {
                job?.let { Text(stringResource(summaryJobLabel(it.status)), style = MaterialTheme.typography.labelLarge) }
                if (job?.dispatchPending == true) Text(stringResource(R.string.summary_controls_dispatch), style = MaterialTheme.typography.bodySmall)
                job?.chunkProgress?.takeIf { active }?.let { Text(stringResource(R.string.summary_controls_chunks, it.completed, it.total)) }
                if (pendingSummary) {
                    Text(stringResource(R.string.summary_controls_unknown))
                    Button(onClick = { operate(false) }, enabled = canClick) { Text(stringResource(R.string.summary_controls_reconcile)) }
                } else {
                    ready.forEach { stage -> Button(onClick = { operate(false, stage) }, enabled = canClick && !active) { Text(stringResource(summaryStageAction(stage))) } }
                    if (job?.retryable == true && !active) TextButton(onClick = { operate(false, job.stage, true) }, enabled = canClick) { Text(stringResource(R.string.summary_controls_retry)) }
                    if (!active && ready.isEmpty()) Text(stringResource(if (state.blockedReason != null) R.string.summary_controls_blocked else R.string.summary_controls_waiting))
                    state.nextUpdateAt?.let { Text(stringResource(R.string.summary_controls_next, recordTime(it)), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (automation?.isFailure == true) WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.summary_automation_read_error))
            else if (auto?.canControl == true && (auto.available || auto.enabled || pendingAutomation)) {
                Text(stringResource(R.string.summary_automation_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(automationLabel(auto.state)), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.summary_automation_usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pendingAutomation) Text(stringResource(R.string.summary_automation_unknown))
                TextButton(onClick = { operate(true) }, enabled = canClick) {
                    Text(stringResource(if (pendingAutomation) R.string.summary_automation_reconcile else if (auto.enabled) R.string.summary_automation_stop else R.string.summary_automation_start))
                }
            }
            if (busy) WeMeetInlineLoading()
            if (operationError) Text(stringResource(R.string.summary_controls_operation_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Capture preview uses canonical immutable versions and exact citations, including quick drafts. */
@Composable
internal fun CaptureSummaryWorkspace(viewer: String, capture: CaptureDto, records: MeetingRecordRepository, summaries: MeetingSummaryRepository, currentViewer: () -> String?, onRecord: (() -> Unit)?, onSource: ((Long) -> Unit)? = null) {
    var refresh by remember(viewer, capture.recordId) { mutableIntStateOf(0) }
    var citation by remember(viewer, capture.recordId) { mutableStateOf<Pair<String, RecordReferenceDto>?>(null) }
    val detail = visibleRead(viewer, capture.recordId, refresh) { records.record(viewer, capture.recordId) }
    val record = detail?.getOrNull()
    if (detail?.isFailure == true || record != null && (record.sourceType != "audio_recording" || record.captureId != capture.id)) {
        WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.summary_controls_read_error))
        return
    }
    if (record == null || !record.capabilities.readSummary) return
    RecordSummaryControls(viewer, record, summaries, currentViewer)
    val versions = visibleRead(viewer, record.id, refresh) { records.summaries(viewer, record.id) }
    if (versions?.isFailure == true) WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.summary_controls_read_error))
    else versions?.getOrNull()?.let { page ->
        if (page.results.isEmpty()) Text(stringResource(R.string.records_no_versions), style = MaterialTheme.typography.bodyMedium)
        page.results.take(3).forEach { version -> key(version.id) { SummaryCard(version, record.capabilities.readTranscript) { citation = version.inputSnapshotId to it } } }
        if (onRecord != null && page.results.isNotEmpty()) TextButton(onClick = onRecord) { Text(stringResource(R.string.summary_controls_all_versions)) }
    }
    if (record.capabilities.readTranscript && versions?.isSuccess == true) citation?.let { (snapshot, ref) ->
        val original = visibleRead(viewer, record.id, snapshot, ref, refresh) { records.citation(viewer, record.id, snapshot, ref) }
        AlertDialog(onDismissRequest = { citation = null }, title = { Text(stringResource(R.string.records_source)) }, text = {
            when {
                original == null -> WeMeetInlineLoading()
                original.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_source_unavailable))
                else -> androidx.compose.foundation.lazy.LazyColumn { item { Text(original.getOrThrow().text) } }
            }
        }, dismissButton = {
            if (onSource != null && original?.isSuccess == true && capture.status == "stopped") TextButton(onClick = { onSource(ref.startMs); citation = null }) {
                Text(stringResource(R.string.capture_playback_source, sourceTime(ref.startMs)))
            }
        }, confirmButton = { TextButton(onClick = { citation = null }) { Text(stringResource(R.string.records_close)) } })
    }
}

private fun summaryStageAction(stage: String) = when (stage) {
    "realtime" -> R.string.summary_controls_realtime
    "quick" -> R.string.summary_controls_quick
    else -> R.string.summary_controls_final
}
private fun summaryJobLabel(status: String) = when (status) {
    "queued" -> R.string.summary_job_queued
    "running" -> R.string.summary_job_running
    "succeeded" -> R.string.summary_job_succeeded
    "partial" -> R.string.summary_job_partial
    "canceled" -> R.string.summary_job_canceled
    else -> R.string.summary_job_failed
}
private fun automationLabel(state: String) = when (state) {
    "waiting" -> R.string.summary_automation_waiting
    "generating" -> R.string.summary_automation_generating
    "completed" -> R.string.summary_automation_completed
    "needs_attention" -> R.string.summary_automation_attention
    else -> R.string.summary_automation_off
}
