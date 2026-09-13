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
import kotlinx.coroutines.*

private fun RecordSummaryContentDto.human() = HumanContentDto(overview,
    decisions.map { HumanPointDto(it.text, it.sourceRefs) }, chapters.map { HumanPointDto(it.text, it.sourceRefs) },
    actionItems.map { HumanActionDto(it.text, it.sourceRefs, it.ownerText.orEmpty(), it.dueText.orEmpty()) },
    openQuestions.map { HumanPointDto(it.text, it.sourceRefs) })

@Composable
internal fun RecordHumanSummary(viewer: String, record: RecordDto, base: RecordSummaryVersionDto?, repository: MeetingReviewRepository,
    currentViewer: () -> String?, onTask: ((String) -> Unit)? = null, onSource: (String, RecordReferenceDto) -> Unit) {
    if (!record.capabilities.readSummary) return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var refresh by remember(viewer, record.id) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, record.id) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, record.id) { mutableStateOf<MeetingReviewCoordinator?>(null) }
    var pending by remember(viewer, record.id) { mutableStateOf<MeetingIntent?>(null) }
    var storageError by remember(viewer, record.id) { mutableStateOf(false) }
    var error by remember(viewer, record.id) { mutableStateOf(false) }
    var busy by remember(viewer, record.id) { mutableStateOf(false) }
    var action by remember(viewer, record.id) { mutableStateOf<Job?>(null) }
    var draft by remember(viewer, record.id) { mutableStateOf<HumanReviewRequestDto?>(null) }
    var history by remember(viewer, record.id) { mutableStateOf(false) }
    var replace by remember(viewer, record.id) { mutableStateOf(false) }
    val read = visibleRead(viewer, record.id, refresh) { repository.current(viewer, record.id) }
    val state = read?.getOrNull()
    LaunchedEffect(viewer, record.id, lifecycle, storageRetry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = MeetingReviewCoordinator(viewer, requireNotNull(store), repository)
                pending = controller.pending(record.id, MeetingIntentKind.HUMAN_REVIEW)
                storageError = false; coordinator = controller
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                coordinator = null
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; busy = false; pending = null; history = false; replace = false
            }
        }
    }
    fun save(reconcile: Boolean) {
        val controller = coordinator ?: return
        if (busy || storageError || state?.canEdit != true || currentViewer() != viewer || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = if (reconcile) runCatching { MeetingReviewRepository.reviewAdapter.fromJson(requireNotNull(pending).body) }.getOrNull() else draft
        if (request == null) { error = true; return }
        busy = true; error = false
        action = scope.launch {
            try { controller.save(record.id, request); draft = null }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { error = true }
            finally {
                if (coordinator === controller && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { pending = controller.pending(record.id, MeetingIntentKind.HUMAN_REVIEW) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { storageError = true }
                    busy = false; refresh++
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.human_summary_title), style = MaterialTheme.typography.titleMedium)
            when {
                read == null -> WeMeetInlineLoading()
                read.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.human_summary_read_error))
                state != null -> {
                    val current = state.current
                    if (current != null) {
                        HumanSummaryBody(current, record.capabilities.readTranscript, onSource)
                        TextButton(onClick = { history = true }) { Text(stringResource(R.string.human_summary_history)) }
                        if (onTask != null) RecordSummaryTasks(viewer, record.id, current, repository, currentViewer, onTask)
                    } else Text(stringResource(R.string.human_summary_empty))
                    if (state.canEdit) {
                        if (storageError) WeMeetInlineErrorState(onRetry = { storageRetry++ }, message = stringResource(R.string.summary_controls_storage_error))
                        if (pending != null) {
                            Text(stringResource(R.string.human_summary_unknown))
                            Button(onClick = { save(true) }, enabled = coordinator != null && !busy && !storageError) { Text(stringResource(R.string.summary_controls_reconcile)) }
                        } else if (current != null || base != null) {
                            TextButton(onClick = { error = false; draft = HumanReviewRequestDto(current?.baseSummaryId ?: requireNotNull(base).id,
                                current?.revision ?: 0, false, current?.content ?: requireNotNull(base).content.human()) }, enabled = !busy && coordinator != null && !storageError) {
                                Text(stringResource(R.string.human_summary_edit))
                            }
                            if (current != null && base != null && current.baseSummaryId != base.id) TextButton(onClick = { replace = true }, enabled = !busy) {
                                Text(stringResource(R.string.human_summary_replace))
                            }
                        }
                    }
                    if (error) Text(stringResource(R.string.human_summary_save_error), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (replace && state?.canEdit == true && coordinator != null && base != null) AlertDialog(onDismissRequest = { replace = false },
        title = { Text(stringResource(R.string.human_summary_replace)) }, text = { Text(stringResource(R.string.human_summary_replace_notice)) },
        confirmButton = { TextButton(onClick = { replace = false; draft = HumanReviewRequestDto(base.id, state.current?.revision ?: 0, true, base.content.human()) }) { Text(stringResource(R.string.human_summary_continue)) } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text(stringResource(R.string.records_close)) } })
    if (state?.canEdit == true && coordinator != null && pending == null) draft?.let { form ->
        val conflict = form.expectedRevision != (state.current?.revision ?: 0)
        HumanSummaryEditor(form.content, busy, conflict, error, onChange = { draft = form.copy(content = it) },
            onSave = { save(false) }, onClose = { if (!busy) draft = null })
    }
    if (history && state?.current != null) HumanSummaryHistory(viewer, record, repository, onSource) { history = false }
}

@Composable
private fun HumanSummaryBody(review: HumanReviewDto, originals: Boolean, onSource: (String, RecordReferenceDto) -> Unit) {
    Text(stringResource(R.string.human_summary_version, review.revision), style = MaterialTheme.typography.labelLarge)
    Text(recordTime(review.createdAt), style = MaterialTheme.typography.bodySmall)
    Text(review.content.overview)
    val groups = listOf(R.string.human_summary_decisions to review.content.decisions, R.string.human_summary_chapters to review.content.chapters,
        R.string.human_summary_actions to review.content.actionItems.map { HumanPointDto(it.text, it.sourceRefs) }, R.string.human_summary_questions to review.content.openQuestions)
    groups.filter { it.second.isNotEmpty() }.forEach { (label, points) ->
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
        points.forEach { point ->
            Text(point.text)
            if (originals) point.sourceRefs.forEach { reference -> TextButton(onClick = { onSource(review.inputSnapshotId, reference) }) {
                Text(stringResource(R.string.records_source) + " · " + sourceTime(reference.startMs))
            } }
        }
    }
}

@Composable
private fun HumanSummaryEditor(content: HumanContentDto, busy: Boolean, conflict: Boolean, error: Boolean, onChange: (HumanContentDto) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(R.string.human_summary_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.human_summary_edit_notice), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(content.overview, { onChange(content.copy(overview = it.take(8000))) }, label = { Text(stringResource(R.string.human_summary_overview)) }, enabled = !busy)
            listOf(R.string.human_summary_decisions to content.decisions, R.string.human_summary_chapters to content.chapters, R.string.human_summary_questions to content.openQuestions).forEach { (label, points) ->
                fun update(next: List<HumanPointDto>) { onChange(when (label) { R.string.human_summary_decisions -> content.copy(decisions = next); R.string.human_summary_chapters -> content.copy(chapters = next); else -> content.copy(openQuestions = next) }) }
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                points.forEachIndexed { index, point ->
                    OutlinedTextField(point.text, { text -> update(points.toMutableList().also { it[index] = point.copy(text = text.take(4000)) }) }, enabled = !busy)
                    TextButton(onClick = { update(points.filterIndexed { i, _ -> i != index }) }, enabled = !busy) { Text(stringResource(R.string.human_summary_remove)) }
                }
                TextButton(onClick = { update(points + HumanPointDto("", emptyList())) }, enabled = !busy && points.size < 100) { Text(stringResource(R.string.human_summary_add)) }
            }
            Text(stringResource(R.string.human_summary_actions), style = MaterialTheme.typography.titleSmall)
            content.actionItems.forEachIndexed { index, point ->
                fun update(next: HumanActionDto) { onChange(content.copy(actionItems = content.actionItems.toMutableList().also { it[index] = next })) }
                OutlinedTextField(point.text, { update(point.copy(text = it.take(4000))) }, enabled = !busy)
                OutlinedTextField(point.ownerText, { update(point.copy(ownerText = it.take(200))) }, label = { Text(stringResource(R.string.human_summary_owner_hint)) }, enabled = !busy)
                OutlinedTextField(point.dueText, { update(point.copy(dueText = it.take(200))) }, label = { Text(stringResource(R.string.human_summary_due_hint)) }, enabled = !busy)
                TextButton(onClick = { onChange(content.copy(actionItems = content.actionItems.filterIndexed { i, _ -> i != index })) }, enabled = !busy) { Text(stringResource(R.string.human_summary_remove)) }
            }
            TextButton(onClick = { onChange(content.copy(actionItems = content.actionItems + HumanActionDto("", emptyList(), "", ""))) }, enabled = !busy && content.actionItems.size < 100) { Text(stringResource(R.string.human_summary_add)) }
            if (conflict) Text(stringResource(R.string.human_summary_conflict))
            if (error) Text(stringResource(R.string.human_summary_save_error), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = onSave, enabled = !busy && !conflict) { Text(stringResource(R.string.human_summary_save)) } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.records_close)) } })
}

@Composable
private fun HumanSummaryHistory(viewer: String, record: RecordDto, repository: MeetingReviewRepository, onSource: (String, RecordReferenceDto) -> Unit, onClose: () -> Unit) {
    var cursors by remember { mutableStateOf(listOf<Int?>(null)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val page = visibleRead(viewer, record.id, cursors.last(), refresh) { repository.history(viewer, record.id, cursors.last()) }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.human_summary_history)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            when {
                page == null -> WeMeetInlineLoading()
                page.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.human_summary_read_error))
                else -> {
                    page.getOrThrow().results.forEach { row -> TextButton(onClick = { selected = row.id }) { Text(stringResource(R.string.human_summary_version, row.revision) + " · " + recordTime(row.createdAt)) } }
                    if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1); selected = null }) { Text(stringResource(R.string.records_previous)) }
                    page.getOrThrow().nextBefore?.let { next -> TextButton(onClick = { cursors = cursors + next; selected = null }) { Text(stringResource(R.string.records_next)) } }
                }
            }
            selected?.let { id ->
                val detail = visibleRead(viewer, record.id, id, refresh) { repository.version(viewer, record.id, id) }
                when { detail == null -> WeMeetInlineLoading(); detail.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.human_summary_read_error))
                    else -> HumanSummaryBody(detail.getOrThrow(), record.capabilities.readTranscript) { snapshot, reference -> onClose(); onSource(snapshot, reference) } }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.records_close)) } })
}
