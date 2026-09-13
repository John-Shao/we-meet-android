package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingSharingRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*

@Composable
internal fun RecordSharing(viewer: String, record: RecordDto, repository: MeetingSharingRepository, currentViewer: () -> String?) {
    if (!record.capabilities.readSummary) return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var refresh by remember(viewer, record.id) { mutableIntStateOf(0) }
    var storageRetry by remember(viewer, record.id) { mutableIntStateOf(0) }
    var coordinator by remember(viewer, record.id) { mutableStateOf<MeetingSharingCoordinator?>(null) }
    var pending by remember(viewer, record.id) { mutableStateOf<MeetingIntent?>(null) }
    var storageError by remember(viewer, record.id) { mutableStateOf(false) }
    var busy by remember(viewer, record.id) { mutableStateOf(false) }
    var error by remember(viewer, record.id) { mutableStateOf(false) }
    var accepted by remember(viewer, record.id) { mutableStateOf(false) }
    var action by remember(viewer, record.id) { mutableStateOf<Job?>(null) }
    var pages by remember(viewer, record.id) { mutableStateOf(listOf<String?>(null)) }
    var choose by remember(viewer, record.id) { mutableStateOf(false) }
    var selection by remember(viewer, record.id) { mutableStateOf<SummaryShareSelectionDto?>(null) }
    val access = visibleRead(viewer, record.id, pages.last(), refresh) { repository.access(viewer, record.id, pages.last()) }
    val state = access?.getOrNull()
    val preview = selection?.let { chosen -> visibleRead(viewer, record.id, chosen, refresh) { repository.preview(viewer, record.id, chosen) } }
    LaunchedEffect(viewer, record.id, lifecycle, storageRetry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = MeetingSharingCoordinator(viewer, requireNotNull(store), repository)
                pending = controller.pending(record.id); storageError = false; coordinator = controller; awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { storageError = true }
            finally {
                coordinator = null
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; busy = false; pending = null; choose = false; selection = null
            }
        }
    }
    fun apply(reconcile: Boolean) {
        val controller = coordinator ?: return
        if (busy || storageError || state?.canManage != true || currentViewer() != viewer || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = if (reconcile) runCatching { MeetingSharingRepository.requestAdapter.fromJson(requireNotNull(pending).body) }.getOrNull()
        else preview?.getOrNull()?.takeIf { state.available }?.let { SummaryShareRequestDto(it.recipients.map { person -> person.id }, it.operation, it.previewHash) }
        if (request == null) { error = true; return }
        busy = true; error = false; accepted = false
        action = scope.launch {
            try { controller.apply(record.id, request); accepted = true; selection = null; pages = listOf(null) }
            catch (canceled: CancellationException) { throw canceled }
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
    if (access?.isSuccess == true && state?.canManage != true) return
    val enabled = coordinator != null && !busy && !storageError
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.record_share_title), style = MaterialTheme.typography.titleMedium)
            when {
                access == null -> WeMeetInlineLoading()
                access.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.record_share_read_error))
                state != null -> {
                    Text(stringResource(R.string.record_share_scope), style = MaterialTheme.typography.bodySmall)
                    if (!state.available) Text(stringResource(R.string.record_share_unavailable))
                    if (storageError) WeMeetInlineErrorState(onRetry = { storageRetry++ }, message = stringResource(R.string.summary_controls_storage_error))
                    if (pending != null) {
                        Text(stringResource(R.string.record_share_unknown))
                        Button(onClick = { apply(true) }, enabled = enabled) { Text(stringResource(R.string.summary_controls_reconcile)) }
                    } else if (state.available) TextButton(onClick = { choose = true; error = false; accepted = false }, enabled = enabled) { Text(stringResource(R.string.record_share_choose)) }
                    if (accepted) Text(stringResource(R.string.record_share_accepted))
                    if (state.results.isEmpty()) Text(stringResource(R.string.record_share_empty))
                    state.results.forEach { person ->
                        HorizontalDivider()
                        Text(person.name.ifBlank { person.id })
                        Text(stringResource(if (person.readSummary) R.string.record_share_explicit_yes else R.string.record_share_explicit_no), style = MaterialTheme.typography.bodySmall)
                        if (!person.active) Text(stringResource(R.string.record_share_inactive))
                        if (state.available && person.readSummary && pending == null) TextButton(onClick = { selection = SummaryShareSelectionDto(listOf(person.id), "revoke"); error = false; accepted = false }, enabled = enabled) {
                            Text(stringResource(R.string.record_share_revoke))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        if (pages.size > 1) TextButton(onClick = { pages = pages.dropLast(1) }, enabled = !busy) { Text(stringResource(R.string.records_previous)) }
                        state.nextCursor?.let { next -> TextButton(onClick = { pages = pages + next }, enabled = !busy) { Text(stringResource(R.string.records_next)) } }
                    }
                    if (error) Text(stringResource(R.string.record_share_error), color = MaterialTheme.colorScheme.error)
                    if (busy) WeMeetInlineLoading()
                }
            }
        }
    }
    if (choose && state?.available == true && state.canManage && coordinator != null && pending == null) ShareCandidates(viewer, record.id,
        record.sourceType == "meeting", repository, onSelection = { selection = it; choose = false }, onClose = { choose = false })
    if (selection != null && state?.canManage == true && coordinator != null && pending == null) {
        val value = preview?.getOrNull()
        AlertDialog(onDismissRequest = { if (!busy) selection = null }, title = { Text(stringResource(R.string.record_share_preview)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(R.string.record_share_scope))
                when {
                    preview == null -> WeMeetInlineLoading()
                    preview.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.record_share_read_error))
                    value != null -> {
                        Text(value.title, style = MaterialTheme.typography.titleSmall)
                        value.recipients.forEach { person ->
                            Text(person.name.ifBlank { person.id }, style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(if (person.afterEffectiveSummary) R.string.record_share_after_yes else R.string.record_share_after_no))
                            if (value.operation == "revoke" && person.inheritedSummary) Text(stringResource(R.string.record_share_inherited))
                            if (person.effectiveTranscript) Text(stringResource(R.string.record_share_original_unchanged))
                        }
                        if (error) Text(stringResource(R.string.record_share_error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { apply(false) }, enabled = enabled && state.available && value != null) {
            Text(stringResource(if (selection?.operation == "revoke") R.string.record_share_confirm_revoke else R.string.record_share_confirm))
        } }, dismissButton = { TextButton(onClick = { selection = null }, enabled = !busy) { Text(stringResource(R.string.records_close)) } })
    }
}

@Composable
private fun ShareCandidates(viewer: String, recordId: String, online: Boolean, repository: MeetingSharingRepository,
    onSelection: (SummaryShareSelectionDto) -> Unit, onClose: () -> Unit) {
    var candidateScope by remember { mutableStateOf("directory") }
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf(listOf<String?>(null)) }
    var selected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var refresh by remember { mutableIntStateOf(0) }
    val read = visibleRead(viewer, recordId, candidateScope, query, pages.last(), refresh) { repository.candidates(viewer, recordId, candidateScope, query, pages.last()) }
    fun reset(value: String, search: String) { candidateScope = value; query = search; pages = listOf(null); selected = emptyMap() }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.record_share_choose)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            if (online) Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                listOf("directory" to R.string.record_share_directory, "participants" to R.string.record_share_participants).forEach { (value, label) ->
                    FilterChip(selected = candidateScope == value, onClick = { reset(value, query) }, label = { Text(stringResource(label)) })
                }
            }
            OutlinedTextField(text, { text = it.take(80) }, label = { Text(stringResource(R.string.record_share_search)) })
            TextButton(onClick = { reset(candidateScope, text.trim()) }) { Text(stringResource(R.string.records_search_action)) }
            Text(stringResource(R.string.record_share_selected, selected.size))
            if (selected.isNotEmpty()) {
                Text(selected.values.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { selected = emptyMap() }) { Text(stringResource(R.string.record_share_clear)) }
            }
            when {
                read == null -> WeMeetInlineLoading()
                read.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.record_share_read_error))
                else -> {
                    val page = read.getOrThrow()
                    if (page.results.isEmpty()) Text(stringResource(R.string.record_share_no_candidates))
                    page.results.forEach { person -> Row(Modifier.fillMaxWidth().toggleable(value = selected.containsKey(person.id), role = Role.Checkbox,
                        enabled = selected.size < 50 || selected.containsKey(person.id), onValueChange = { checked ->
                            selected = if (checked) selected + (person.id to person.name.ifBlank { person.id }) else selected - person.id
                        })) {
                        Checkbox(checked = selected.containsKey(person.id), onCheckedChange = null)
                        Text(person.name.ifBlank { person.id }, Modifier.weight(1f).padding(vertical = Dimens.SpaceS))
                    } }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        if (pages.size > 1) TextButton(onClick = { pages = pages.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                        page.nextCursor?.let { next -> TextButton(onClick = { pages = pages + next }) { Text(stringResource(R.string.records_next)) } }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSelection(SummaryShareSelectionDto(selected.keys.sorted(), "grant")) }, enabled = selected.isNotEmpty() && read?.isSuccess == true) { Text(stringResource(R.string.record_share_review_selection)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.records_close)) } })
}
