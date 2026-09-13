@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import com.we.meet.data.repository.MeetingReviewRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*

@Composable
internal fun RecordSummaryTasks(viewer: String, recordId: String, review: HumanReviewDto, repository: MeetingReviewRepository,
    currentViewer: () -> String?, onTask: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var query by remember(viewer, recordId) { mutableStateOf("") }
    var refresh by remember(viewer, recordId) { mutableIntStateOf(0) }
    var retry by remember(viewer, recordId) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, recordId) { mutableStateOf<MeetingReviewCoordinator?>(null) }
    var pending by remember(viewer, recordId) { mutableStateOf<MeetingIntent?>(null) }
    var storageError by remember(viewer, recordId) { mutableStateOf(false) }
    var error by remember(viewer, recordId) { mutableStateOf(false) }
    var busy by remember(viewer, recordId) { mutableStateOf(false) }
    var action by remember(viewer, recordId) { mutableStateOf<Job?>(null) }
    var draft by remember(viewer, recordId, review.id) { mutableStateOf<SummaryTaskRequestDto?>(null) }
    var assignee by remember(viewer, recordId, review.id) { mutableStateOf<SummaryAssigneeDto?>(null) }
    val read = visibleRead(viewer, recordId, review.id, query, refresh) { repository.tasks(viewer, recordId, query.ifBlank { null }) }
    val state = read?.getOrNull()
    val matches = state?.reviewId == review.id && state.actions.size == review.content.actionItems.size
    LaunchedEffect(viewer, recordId, lifecycle, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = MeetingReviewCoordinator(viewer, requireNotNull(store), repository)
                pending = controller.pending(recordId, MeetingIntentKind.SUMMARY_TASK)
                storageError = false; coordinator = controller; awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                coordinator = null
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; busy = false; pending = null
            }
        }
    }
    fun submit(reconcile: Boolean) {
        val controller = coordinator ?: return
        if (busy || storageError || state?.canConvert != true || currentViewer() != viewer || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = if (reconcile) runCatching { MeetingReviewRepository.taskAdapter.fromJson(requireNotNull(pending).body) }.getOrNull() else draft
        if (request == null || (!reconcile && (!matches || assignee?.id != request.assigneeId))) { error = true; return }
        busy = true; error = false
        action = scope.launch {
            try { controller.convert(recordId, request); draft = null; assignee = null }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { error = true }
            finally {
                if (coordinator === controller && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { pending = controller.pending(recordId, MeetingIntentKind.SUMMARY_TASK) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { storageError = true }
                    busy = false; refresh++
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.summary_tasks_title), style = MaterialTheme.typography.titleMedium)
        when {
            read == null -> WeMeetInlineLoading()
            read.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.summary_tasks_read_error))
            state != null -> {
                if (state.canConvert && storageError) WeMeetInlineErrorState(onRetry = { retry++ }, message = stringResource(R.string.summary_controls_storage_error))
                if (state.canConvert && pending != null) {
                    Text(stringResource(R.string.summary_tasks_unknown))
                    Button(onClick = { submit(true) }, enabled = coordinator != null && !busy && !storageError) { Text(stringResource(R.string.summary_controls_reconcile)) }
                }
                if (!matches) WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.summary_tasks_conflict))
                else review.content.actionItems.forEachIndexed { index, point ->
                    Text(point.text)
                    val link = state.actions[index]
                    if (link != null) {
                        Text(stringResource(when { link.deleted -> R.string.summary_tasks_deleted; link.status == "completed" -> R.string.summary_tasks_completed; link.status == "todo" -> R.string.summary_tasks_todo; else -> R.string.summary_tasks_converted }))
                        link.taskId?.let { id -> TextButton(onClick = { onTask(id) }) { Text(stringResource(R.string.summary_tasks_open)) } }
                    } else if (state.canConvert && pending == null) TextButton(onClick = {
                        draft = SummaryTaskRequestDto(review.id, index, point.text, "", null); assignee = null; query = ""; error = false
                    }, enabled = !busy && coordinator != null && !storageError) { Text(stringResource(R.string.summary_tasks_convert)) }
                }
                if (error) Text(stringResource(R.string.summary_tasks_error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (state?.canConvert == true && coordinator != null && pending == null) draft?.let { form ->
        TaskConfirmationDialog(form, assignee, state.assignees, review.content.actionItems.getOrNull(form.actionIndex), query, busy, matches, error,
            onChange = { draft = it }, onAssignee = { assignee = it; draft = form.copy(assigneeId = it.id) },
            onSearch = { query = it; assignee = null; draft = form.copy(assigneeId = "") }, onRefresh = { refresh++ },
            onSave = { submit(false) }, onClose = { if (!busy) { draft = null; assignee = null } })
    }
}

@Composable
private fun TaskConfirmationDialog(form: SummaryTaskRequestDto, assignee: SummaryAssigneeDto?, candidates: List<SummaryAssigneeDto>, hints: HumanActionDto?,
    initialQuery: String, busy: Boolean, matches: Boolean, error: Boolean, onChange: (SummaryTaskRequestDto) -> Unit, onAssignee: (SummaryAssigneeDto) -> Unit,
    onSearch: (String) -> Unit, onRefresh: () -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    var search by remember { mutableStateOf(initialQuery) }
    var chooseDate by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(R.string.summary_tasks_confirm)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            OutlinedTextField(form.title, { onChange(form.copy(title = it.take(4000))) }, label = { Text(stringResource(R.string.summary_tasks_task_title)) }, enabled = !busy)
            hints?.let { Text(stringResource(R.string.summary_tasks_hints, it.ownerText.ifBlank { "—" }, it.dueText.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(search, { search = it.take(100) }, label = { Text(stringResource(R.string.summary_tasks_search)) }, enabled = !busy)
            TextButton(onClick = { onSearch(search.trim()) }, enabled = !busy) { Text(stringResource(R.string.records_search_action)) }
            Text(stringResource(R.string.summary_tasks_assignee, assignee?.name ?: stringResource(R.string.summary_tasks_choose)), style = MaterialTheme.typography.labelLarge)
            if (candidates.isEmpty()) Text(stringResource(R.string.summary_tasks_no_assignees))
            candidates.forEach { candidate -> TextButton(onClick = { onAssignee(candidate) }, enabled = !busy) { Text(candidate.name) } }
            TextButton(onClick = { chooseDate = true }, enabled = !busy) { Text(stringResource(R.string.summary_tasks_date, form.dueDate ?: stringResource(R.string.summary_tasks_no_date))) }
            if (form.dueDate != null) TextButton(onClick = { onChange(form.copy(dueDate = null)) }, enabled = !busy) { Text(stringResource(R.string.summary_tasks_clear_date)) }
            Text(stringResource(R.string.summary_tasks_notice), style = MaterialTheme.typography.bodySmall)
            if (!matches) Text(stringResource(R.string.summary_tasks_conflict))
            if (error) WeMeetInlineErrorState(onRetry = onRefresh, message = stringResource(R.string.summary_tasks_error))
        }
    }, confirmButton = { TextButton(onClick = onSave, enabled = !busy && matches && form.title.isNotBlank() && assignee?.id == form.assigneeId) { Text(stringResource(R.string.summary_tasks_create)) } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.records_close)) } })
    if (chooseDate && !busy) {
        val date = rememberDatePickerState(initialSelectedDateMillis = form.dueDate?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() })
        DatePickerDialog(onDismissRequest = { chooseDate = false }, confirmButton = { TextButton(onClick = {
            date.selectedDateMillis?.let { onChange(form.copy(dueDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())) }; chooseDate = false
        }, enabled = date.selectedDateMillis != null) { Text(stringResource(R.string.summary_tasks_set_date)) } },
            dismissButton = { TextButton(onClick = { chooseDate = false }) { Text(stringResource(R.string.records_close)) } }) { DatePicker(date) }
    }
}
