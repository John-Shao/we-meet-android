@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.SpeakerIdentificationRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Closed by default; even the directory is not loaded before an explicit open. */
@Composable
internal fun RecordSpeakerIdentification(repository: SpeakerIdentificationRepository, viewer: String, record: RecordDto,
    onChanged: () -> Unit, onPreview: (Long, Long) -> Unit, onPreviewStop: () -> Unit) {
    if (record.sourceType != "upload" || record.upload?.status != "succeeded" || !record.capabilities.edit || !record.capabilities.readTranscript || !record.capabilities.playMedia) return
    val client = remember(repository, viewer, record.id) { runCatching { repository.open(viewer, record.id) }.getOrNull() } ?: return
    var opened by remember(client) { mutableStateOf(false) }
    var allowed by remember(client) { mutableStateOf(client.allowed()) }
    val stop by rememberUpdatedState(onPreviewStop)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (client.allowed()) { delay(250) }
                allowed = false; opened = false; stop()
            } finally { opened = false; stop() }
        }
    }
    if (!allowed) return
    val config = visibleRead(client) { client.enabled() }
    LaunchedEffect(config) { if (config?.getOrNull() != true) opened = false }
    if (config?.getOrNull() != true) return
    TextButton(onClick = { if (client.allowed()) opened = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.identity_open))
    }
    if (opened) {
        val changed by rememberUpdatedState(onChanged)
        val operations = remember(client) { SessionIdentificationOperations(client, { changed() }, { stop() }) }
        val controller = remember(client) { SpeakerIdentificationController(operations, record.revision) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        IdentificationSheet(controller, { start, end -> if (client.allowed()) onPreview(start, end) else opened = false }, onClose = { opened = false })
    }
}

@Composable
internal fun IdentificationSheet(controller: SpeakerIdentificationController, onPreview: (Long, Long) -> Unit, onClose: () -> Unit) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val sheetHeight = configuration.screenHeightDp.dp * 0.85f
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.refresh()
            while (true) { delay(5000); controller.poll() }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // The dialog creates a new Android owner; retain the caller's app language
        // and font scale rather than falling back to its window's system resources.
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density) {
            Column(Modifier.fillMaxWidth().heightIn(max = sheetHeight).verticalScroll(rememberScrollState())
                .padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(R.string.identity_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.identity_hint))
                Text(stringResource(R.string.speaker_identity_manual_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    TextButton(enabled = !state.busy, onClick = { scope.launch { controller.refresh() } }) { Text(stringResource(R.string.records_refresh)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.records_close)) }
                    if (controller.canCancel) TextButton(onClick = { scope.launch { controller.cancel() } }) { Text(stringResource(R.string.identity_cancel)) }
                }
                if (state.busy || state.loadingPeople) WeMeetInlineLoading()
                state.failure?.let {
                    Text(stringResource(when (it) {
                        IdentificationFailure.CONFLICT -> R.string.identity_conflict
                        IdentificationFailure.ACCESS -> R.string.speaker_identity_access
                        IdentificationFailure.REQUEST -> R.string.identity_request_error
                    }), color = MaterialTheme.colorScheme.error)
                }
                state.retry?.let {
                    Text(stringResource(R.string.identity_uncertain))
                    TextButton(enabled = !state.busy && state.failure != IdentificationFailure.ACCESS,
                        onClick = { scope.launch { controller.submit(retry = true) } }) { Text(stringResource(R.string.identity_retry_same)) }
                }
                if (state.failure == null) {
                    state.options?.let { options ->
                        Text(stringResource(R.string.identity_bank), style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                            if (options.personalAllowed) FilterChip(selected = state.scope == "personal", enabled = !state.busy && state.retry == null,
                                onClick = { scope.launch { controller.scope("personal") } }, label = { Text(stringResource(R.string.identity_personal)) })
                            options.scopes.results.forEach { bank ->
                                FilterChip(selected = state.scope == bank.id, enabled = bank.enabled && !state.busy && state.retry == null,
                                    onClick = { scope.launch { controller.scope(bank.id) } }, label = { Text(bank.name + if (bank.enabled) "" else " · " + stringResource(R.string.identity_bank_disabled)) })
                            }
                        }
                        if (options.scopes.nextOffset != null) TextButton(enabled = !state.busy, onClick = { scope.launch { controller.moreScopes() } }) { Text(stringResource(R.string.identity_more_banks)) }
                        Text(stringResource(R.string.identity_targets), style = MaterialTheme.typography.titleMedium)
                        if (options.targets.isEmpty()) Text(stringResource(R.string.identity_no_targets))
                        options.targets.forEach { target ->
                            IdentityCheck(target.name, target.id in state.targets, state.editable) { controller.target(target.id, it) }
                        }
                        Text(stringResource(R.string.identity_people, state.selected.size), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.identity_people_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(state.query, controller::query, enabled = state.editable, singleLine = true,
                            label = { Text(stringResource(R.string.identity_find)) }, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = state.editable && state.scope != null, onClick = { scope.launch { controller.search() } }) { Text(stringResource(R.string.identity_search)) }
                        state.people?.results?.forEach { person ->
                            IdentityCheck(person.name, person.id in state.selected, state.editable && (state.selected.size < 50 || person.id in state.selected)) { controller.person(person, it) }
                        }
                        if (state.people?.results?.isEmpty() == true) Text(stringResource(R.string.identity_no_people))
                        FlowRow {
                            if (state.offset > 0) TextButton(enabled = state.editable, onClick = { scope.launch { controller.page(false) } }) { Text(stringResource(R.string.records_previous)) }
                            if (state.people?.nextOffset != null) TextButton(enabled = state.editable, onClick = { scope.launch { controller.page(true) } }) { Text(stringResource(R.string.records_next)) }
                        }
                        state.selected.forEach { (id, name) -> TextButton(enabled = state.editable, onClick = { controller.remove(id) }) { Text(stringResource(R.string.identity_remove, name)) } }
                        TextButton(enabled = state.canSubmit, onClick = { scope.launch { controller.submit() } }) { Text(stringResource(R.string.identity_submit)) }
                    }
                    state.response?.request?.let { batch ->
                        if (batch.processing) Text(stringResource(R.string.identity_processing))
                        batch.jobs.forEach { job ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                    Text(state.labels[job.speakerId] ?: stringResource(R.string.records_speakers), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(identityJobText(job.status)))
                                    job.suggestion?.let { suggestion ->
                                        Text(stringResource(identitySuggestionText(suggestion)))
                                        if (suggestion.verificationUnavailable) Text(stringResource(R.string.identity_unverified))
                                        suggestion.candidate?.let { Text(stringResource(R.string.identity_suggested, it.name)) }
                                        if (suggestion.state == "pending") {
                                            FlowRow {
                                                suggestion.queryIntervals.forEachIndexed { index, interval ->
                                                    TextButton(enabled = !state.busy, onClick = { onPreview(interval.startMs, interval.endMs) }) { Text(stringResource(R.string.identity_preview, index + 1)) }
                                                }
                                            }
                                            FlowRow {
                                                TextButton(enabled = !state.busy && !batch.processing && suggestion.canConfirm, onClick = { scope.launch { controller.decide(job, true) } }) { Text(stringResource(R.string.identity_confirm)) }
                                                TextButton(enabled = !state.busy && !batch.processing, onClick = { scope.launch { controller.decide(job, false) } }) { Text(stringResource(R.string.identity_reject)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentityCheck(name: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(name, modifier = Modifier.weight(1f))
    }
}
private fun identityJobText(status: String) = when (status) {
    "queued" -> R.string.identity_queued; "running" -> R.string.identity_running; "succeeded" -> R.string.identity_succeeded
    "failed" -> R.string.identity_failed; "canceled" -> R.string.identity_canceled; else -> R.string.identity_expired
}
private fun identitySuggestionText(value: IdentitySuggestionDto) = when (value.state) {
    "confirmed" -> R.string.identity_confirmed; "rejected" -> R.string.identity_rejected; "invalidated" -> R.string.identity_invalidated
    else -> when (value.result) {
        "suggested" -> R.string.identity_found; "unknown" -> R.string.identity_unknown; "mixed_speaker" -> R.string.identity_mixed
        "ambiguous" -> R.string.identity_ambiguous; "insufficient_audio" -> R.string.identity_short; else -> R.string.identity_unavailable
    }
}
