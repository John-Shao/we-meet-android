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
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingQuestionRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*

@Composable
internal fun RecordQuestions(viewer: String, record: RecordDto, versions: List<RecordSummaryVersionDto>, repository: MeetingQuestionRepository,
    currentViewer: () -> String?, onSource: (String, RecordReferenceDto) -> Unit) {
    if (!record.capabilities.readTranscript || record.isOngoing) return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var refresh by remember(viewer, record.id) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, record.id) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, record.id) { mutableStateOf<MeetingQuestionCoordinator?>(null) }
    var pending by remember(viewer, record.id) { mutableStateOf<MeetingIntent?>(null) }
    var storageError by remember(viewer, record.id) { mutableStateOf(false) }
    var error by remember(viewer, record.id) { mutableStateOf(false) }
    var busy by remember(viewer, record.id) { mutableStateOf(false) }
    var action by remember(viewer, record.id) { mutableStateOf<Job?>(null) }
    var snapshot by remember(viewer, record.id) { mutableStateOf<String?>(null) }
    var input by remember(viewer, record.id) { mutableStateOf("") }
    var selected by remember(viewer, record.id) { mutableStateOf<Pair<String, String>?>(null) }
    var chooseSource by remember(viewer, record.id) { mutableStateOf(false) }
    val availability = visibleRead(viewer, record.id, refresh) { repository.recent(viewer, record.id) }
    val state = availability?.getOrNull()
    val answer = selected?.let { (id, source) -> visibleRead(viewer, record.id, id, source, refresh, intervalMs = 5000) { repository.question(viewer, record.id, id, source) } }
    val running = answer?.getOrNull()?.status == "running"
    val waiting = selected != null && answer == null
    val sources = versions.distinctBy { it.inputSnapshotId }
    LaunchedEffect(viewer, record.id, lifecycle, storageRetry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = MeetingQuestionCoordinator(viewer, requireNotNull(store), repository)
                pending = controller.pending(record.id)
                storageError = false; coordinator = controller; awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                coordinator = null
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; busy = false; pending = null; chooseSource = false
            }
        }
    }
    fun ask() {
        val controller = coordinator ?: return
        if (busy || running || waiting || storageError || state == null || (!state.available && pending == null) ||
            currentViewer() != viewer || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = if (pending != null) runCatching { MeetingQuestionRepository.requestAdapter.fromJson(requireNotNull(pending).body) }.getOrNull()
            else snapshot?.let { RecordQuestionRequestDto(it, input.trim()) }
        if (request == null) { error = true; return }
        busy = true; error = false; selected = null
        action = scope.launch {
            try {
                val result = controller.ask(record.id, request)
                selected = result.id to result.snapshotId; snapshot = result.snapshotId; input = result.question
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { error = true }
            finally {
                if (coordinator === controller && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { pending = controller.pending(record.id) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { storageError = true }
                    busy = false; refresh++
                }
            }
        }
    }
    val locked = busy || running || waiting || pending != null
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.record_question_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.record_question_scope), style = MaterialTheme.typography.bodySmall)
            when {
                availability == null -> WeMeetInlineLoading()
                availability.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.record_question_read_error))
                state != null -> {
                    if (storageError) WeMeetInlineErrorState(onRetry = { storageRetry++ }, message = stringResource(R.string.summary_controls_storage_error))
                    if (pending != null) {
                        Text(stringResource(R.string.record_question_unknown))
                        Button(onClick = { ask() }, enabled = !busy && !storageError && coordinator != null && !running && !waiting) { Text(stringResource(R.string.summary_controls_reconcile)) }
                    } else {
                        TextButton(onClick = { chooseSource = true }, enabled = !locked && state.available && sources.isNotEmpty()) {
                            Text(if (snapshot == null) stringResource(R.string.record_question_choose) else sources.find { it.inputSnapshotId == snapshot }?.let {
                                stringResource(R.string.record_question_source, it.inputRevision, recordTime(it.createdAt))
                            } ?: stringResource(R.string.record_question_historical_source))
                        }
                        OutlinedTextField(input, { input = it.take(2000) }, label = { Text(stringResource(R.string.record_question_input)) }, enabled = !locked && state.available)
                        Button(onClick = { ask() }, enabled = !locked && coordinator != null && !storageError && state.available && snapshot != null && input.isNotBlank()) { Text(stringResource(R.string.record_question_ask)) }
                        if (!state.available) Text(stringResource(R.string.record_question_disabled))
                    }
                    if (busy) WeMeetInlineLoading()
                    if (error) Text(stringResource(R.string.record_question_error), color = MaterialTheme.colorScheme.error)
                    if (state.recent.isNotEmpty()) {
                        Text(stringResource(R.string.record_question_recent), style = MaterialTheme.typography.titleSmall)
                        state.recent.forEach { item -> TextButton(onClick = { selected = item.id to item.snapshotId; snapshot = item.snapshotId; input = item.question; error = false }, enabled = !locked) { Text(item.question) } }
                    }
                    if (selected != null) when {
                        answer == null -> WeMeetInlineLoading()
                        answer.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.record_question_read_error))
                        else -> {
                            val result = answer.getOrThrow()
                            Text(result.question, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(questionStatus(result.status)), style = MaterialTheme.typography.labelLarge)
                            result.content?.let { content ->
                                if (!content.answerable) Text(stringResource(R.string.record_question_no_answer))
                                else {
                                    Text(content.answer)
                                    content.sourceRefs.forEach { ref -> TextButton(onClick = { onSource(result.snapshotId, ref) }) {
                                        Text(stringResource(R.string.records_source) + " · " + sourceTime(ref.startMs))
                                    } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (chooseSource && state != null && coordinator != null) AlertDialog(onDismissRequest = { chooseSource = false },
        title = { Text(stringResource(R.string.record_question_choose)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sources.forEach { source -> TextButton(onClick = { snapshot = source.inputSnapshotId; selected = null; chooseSource = false }) {
                    Text(stringResource(R.string.record_question_source, source.inputRevision, recordTime(source.createdAt)))
                } }
            }
        }, confirmButton = { TextButton(onClick = { chooseSource = false }) { Text(stringResource(R.string.records_close)) } })
}

private fun questionStatus(status: String) = when (status) {
    "running" -> R.string.record_question_running
    "succeeded" -> R.string.record_question_succeeded
    "failed" -> R.string.record_question_failed
    "incomplete" -> R.string.record_question_incomplete
    else -> R.string.record_question_canceled
}
