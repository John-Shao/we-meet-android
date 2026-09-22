package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordReferenceDto
import com.we.meet.data.api.dto.SummaryRequestDto
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.capture.MeetingIntentStore
import com.we.meet.data.capture.MeetingSummaryCoordinator
import com.we.meet.data.repository.MeetingSummaryRepository
import com.we.meet.ui.components.WeMeetInlineEmptyState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*
import retrofit2.HttpException

/** Own endpoint and durable intent; no minutes content or minutes generation. */
@Composable
internal fun RecordOverview(viewer: String, record: RecordDto, repository: MeetingSummaryRepository, currentViewer: () -> String?, modifier: Modifier = Modifier, chaptersOnly: Boolean = false, onSource: (String, RecordReferenceDto) -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var refresh by remember(viewer, record.id) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, record.id) { mutableIntStateOf(0) }
    var controller by remember(viewer, record.id) { mutableStateOf<MeetingSummaryCoordinator?>(null) }
    var pending by remember(viewer, record.id) { mutableStateOf(false) }
    var storageError by remember(viewer, record.id) { mutableStateOf(false) }
    var busy by remember(viewer, record.id) { mutableStateOf(false) }
    var message by remember(viewer, record.id) { mutableStateOf<Int?>(null) }
    var action by remember(viewer, record.id) { mutableStateOf<Job?>(null) }
    val read = visibleRead(viewer, record.id, refresh, intervalMs = 3000) { repository.overview(viewer, record.id) }
    val state = read?.getOrNull()
    val job = state?.job
    val active = job?.status in setOf("queued", "running")
    LaunchedEffect(viewer, record.id, lifecycle, storageRetry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val coordinator = MeetingSummaryCoordinator(viewer, requireNotNull(store), repository)
                pending = coordinator.pending(record.id, MeetingIntentKind.OVERVIEW_REQUEST) != null
                storageError = false
                controller = coordinator
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                controller = null
                withContext(NonCancellable) {
                    action?.cancelAndJoin()
                    withContext(Dispatchers.IO) { store?.close() }
                }
                action = null; busy = false; pending = false
            }
        }
    }
    fun submit(operation: String) {
        val coordinator = controller ?: return
        val current = state ?: return
        if (!current.canGenerate || busy || storageError || currentViewer() != viewer || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!pending && (active || !current.generationReady)) return
        busy = true; message = null
        action = scope.launch {
            try {
                coordinator.submitOverview(record.id, SummaryRequestDto(operation, "final", current.revision, job?.id, job?.attempt))
                message = R.string.record_overview_accepted
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: HttpException) {
                message = when (error.code()) {
                    400, 409, 422 -> R.string.record_overview_conflict
                    429 -> R.string.record_overview_rate_limited
                    else -> R.string.record_overview_uncertain
                }
            } catch (_: Exception) { message = R.string.record_overview_uncertain }
            finally {
                if (controller === coordinator && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { pending = coordinator.pending(record.id, MeetingIntentKind.OVERVIEW_REQUEST) != null }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { storageError = true }
                    busy = false; refresh++
                }
            }
        }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        when {
            read == null -> WeMeetInlineLoading()
            state == null -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
            else -> {
                if (state.canGenerate) {
                    val canClick = controller != null && !storageError && !busy
                    if (pending) Button(onClick = { submit("generate") }, enabled = canClick) { Text(stringResource(R.string.record_overview_resubmit)) }
                    else {
                        Button(onClick = { submit(if (job == null) "generate" else "regenerate") }, enabled = canClick && state.generationReady && !active) {
                            Text(stringResource(if (job == null) R.string.record_overview_generate else R.string.record_overview_regenerate))
                        }
                        if (job?.retryable == true && !active) TextButton(onClick = { submit("retry") }, enabled = canClick && state.generationReady) { Text(stringResource(R.string.record_overview_retry)) }
                    }
                    if (!state.generationReady) Text(stringResource(R.string.record_overview_wait_source), style = MaterialTheme.typography.bodySmall)
                    if (storageError) WeMeetInlineErrorState(onRetry = { storageRetry++ }, message = stringResource(R.string.record_overview_storage_error))
                }
                if (active) Text(stringResource(R.string.record_overview_generating))
                if (job?.status in setOf("failed", "canceled")) Text(stringResource(R.string.record_overview_failed))
                message?.let { Text(stringResource(it)) }
                if (!state.available) Text(stringResource(R.string.record_overview_unavailable))
                val version = state.version
                if (version == null) WeMeetInlineEmptyState(stringResource(R.string.record_overview_empty))
                else {
                    Text(stringResource(R.string.minutes_generated_at, recordTime(version.createdAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!version.isCurrent) Text(stringResource(R.string.record_overview_source_changed), style = MaterialTheme.typography.bodySmall)
                    if (version.asrStatus == "incomplete") Text(stringResource(R.string.records_incomplete), style = MaterialTheme.typography.bodySmall)
                    if (!chaptersOnly) Text(version.content.synopsis, style = MaterialTheme.typography.bodyLarge)
                    version.content.topics.forEach { topic ->
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                            Text(topic.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(topic.text, style = MaterialTheme.typography.bodyLarge)
                            if (record.capabilities.readTranscript) topic.sourceRefs.forEach { ref ->
                                TextButton(onClick = { onSource(version.inputSnapshotId, ref) }) { Text(stringResource(R.string.records_source_at, sourceTime(ref.startMs))) }
                            }
                        }
                    }
                }
                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
            }
        }
    }
}
