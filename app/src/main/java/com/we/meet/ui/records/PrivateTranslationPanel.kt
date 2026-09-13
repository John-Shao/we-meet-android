package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.data.api.dto.PrivateTranslationRequestDto
import com.we.meet.data.repository.MeetingTranslationRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

@Composable
internal fun PrivateTranslationPanel(state: PrivateTranslationController, viewer: String, room: String, sid: String,
    localSid: String, microphoneEnabled: Boolean, onArchive: (String) -> Unit = {}) {
    state.version
    if (viewer.isBlank()) { Text(stringResource(R.string.translation_guest)); return }
    val current = state.session.status?.current
    val active = current?.state in MeetingTranslationRepository.activeStates
    val observed = state.read?.getOrNull()
    val sourceConnection = observed?.sources?.singleOrNull { it.participantSid == localSid }
    var source by remember(state, current?.id) { mutableStateOf(current?.configuration?.source ?: "zh") }
    var mode by remember(state, current?.id) { mutableStateOf(current?.configuration?.mode ?: "simultaneous") }
    var audio by remember(state, current?.id) { mutableStateOf(current?.configuration?.audio ?: true) }
    var save by remember(state, current?.id) { mutableStateOf(current?.configuration?.archiveRecordId != null) }
    var confirmation by remember(state) { mutableStateOf<PrivateTranslationRequestDto?>(null) }
    val configurable = state.enabled && state.fresh && state.pending == null && !active && observed?.available == true
    LaunchedEffect(state.resumed) { if (!state.resumed) confirmation = null }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.translation_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.translation_description), style = MaterialTheme.typography.bodySmall)
        when {
            state.read == null -> WeMeetInlineLoading()
            state.read?.isFailure == true -> WeMeetInlineErrorState(state.refresh, message = stringResource(R.string.translation_unavailable))
            observed != null -> {
                Text(stringResource(translationStatus(current?.state)), style = MaterialTheme.typography.labelLarge)
                if (active) {
                    Text(stringResource(if (source == "zh") R.string.translation_zh_en else R.string.translation_en_zh))
                    Text(stringResource(if (mode == "simultaneous") R.string.translation_simultaneous else R.string.translation_manual))
                    Text(stringResource(if (audio) R.string.translation_audio else R.string.translation_text_only), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (save) R.string.translation_save else R.string.translation_no_save), style = MaterialTheme.typography.bodySmall)
                } else {
                    TranslationChoice(stringResource(R.string.translation_zh_en), source == "zh", configurable) { source = "zh" }
                    TranslationChoice(stringResource(R.string.translation_en_zh), source == "en", configurable) { source = "en" }
                    TranslationChoice(stringResource(R.string.translation_simultaneous), mode == "simultaneous", configurable) { mode = "simultaneous" }
                    TranslationChoice(stringResource(R.string.translation_manual), mode == "push_to_talk", configurable) { mode = "push_to_talk" }
                    TranslationCheck(stringResource(R.string.translation_audio), audio, configurable) { audio = it }
                    if (observed.archiveAvailable || save) TranslationCheck(stringResource(R.string.translation_save), save, configurable && observed.archiveAvailable) { save = it }
                }
                if (state.storageError) WeMeetInlineErrorState(state.refresh, message = stringResource(R.string.summary_controls_storage_error))
                if (state.pending != null) {
                    Text(stringResource(R.string.translation_unknown))
                    Button(onClick = { runCatching { MeetingTranslationRepository.requestAdapter.fromJson(state.pending!!.body) }.getOrNull()?.let(state.submit) }, enabled = state.enabled) { Text(stringResource(R.string.summary_controls_reconcile)) }
                } else if (active) {
                    Button(onClick = { confirmation = PrivateTranslationRequestDto(room, sid, "stop", current!!.id) }, enabled = state.enabled && current?.state != "stopping") { Text(stringResource(R.string.translation_stop)) }
                } else if (observed.available) {
                    Button(onClick = { confirmation = PrivateTranslationRequestDto(room, sid, "start", current?.id, sourceConnection!!.id, source, if (source == "zh") "en" else "zh", mode, audio, save) }, enabled = configurable && sourceConnection != null) { Text(stringResource(R.string.translation_start)) }
                }
                if ((active && !state.session.ownConnection) || sourceConnection == null) Text(stringResource(R.string.translation_other_connection), style = MaterialTheme.typography.bodySmall)
                if (active && state.session.ownConnection) {
                    if (!microphoneEnabled) Text(stringResource(R.string.translation_mic_off))
                    if (state.session.controlFailed) Text(stringResource(R.string.translation_command_failed), color = MaterialTheme.colorScheme.error)
                    else if (!state.session.ready || !state.fresh) Text(stringResource(R.string.translation_wait_ready))
                    if (current?.configuration?.audio == true) TextButton(onClick = { state.sound(state.session.muted) }, enabled = state.resumed && state.fresh && observed.available && !state.busy && state.pending == null && state.session.ready && !state.session.controlFailed && current.state == "translating") { Text(stringResource(if (state.session.muted) R.string.translation_listen else R.string.translation_mute)) }
                    if (current?.configuration?.mode == "push_to_talk") {
                        if (state.session.awaiting) Text(stringResource(R.string.translation_wait_turn))
                        for (direction in listOf("forward", "reverse")) {
                            val held = state.session.held == direction
                            val canBegin = state.session.held == null && !state.session.awaiting && microphoneEnabled && state.fresh
                            OutlinedButton(onClick = { state.turn(direction, !held, microphoneEnabled) }, enabled = state.resumed && state.session.ready && !state.session.controlFailed && !state.sending && !state.busy && state.pending == null && current.state == "translating" && (held || canBegin && observed.available)) {
                                Text(stringResource(if (held) R.string.translation_end_turn else if ((direction == "forward") == (current.configuration.source == "zh")) R.string.translation_speak_zh else R.string.translation_speak_en))
                            }
                        }
                    }
                }
                current?.configuration?.archiveRecordId?.let { id -> TextButton(onClick = { onArchive(id) }) { Text(stringResource(R.string.translation_open_archive)) } }
                if (state.error) Text(stringResource(R.string.translation_unknown), color = MaterialTheme.colorScheme.error)
                if (state.busy || state.sending) WeMeetInlineLoading()
                if (state.session.rows.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.translation_text), style = MaterialTheme.typography.titleSmall)
                    state.session.rows.forEach { row ->
                        Text(stringResource(if (row.final) R.string.translation_confirmed else R.string.translation_candidate), style = MaterialTheme.typography.labelSmall)
                        Text(row.text + row.stash, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (state.resumed && state.read?.isSuccess == true && state.pending == null) confirmation?.let { request ->
        val matches = request.expectedRunId == current?.id && (request.operation == "stop" && active && current?.state != "stopping" || request.operation == "start" && !active && observed?.available == true && observed.sources.any { it.id == request.sourceParticipationId && it.participantSid == localSid })
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(if (request.operation == "start") R.string.translation_start else R.string.translation_stop)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(if (request.operation == "start") R.string.translation_start_effect else R.string.translation_stop_effect))
                if (request.operation == "start") {
                    Text(stringResource(if (request.source == "zh") R.string.translation_zh_en else R.string.translation_en_zh))
                    Text(stringResource(if (request.mode == "simultaneous") R.string.translation_simultaneous else R.string.translation_manual))
                    Text(stringResource(if (request.audio == true) R.string.translation_audio else R.string.translation_text_only))
                    Text(stringResource(if (request.saveTranslations == true) R.string.translation_save else R.string.translation_no_save))
                }
            } },
            confirmButton = { TextButton(onClick = { state.submit(request); confirmation = null }, enabled = state.enabled && matches) { Text(stringResource(R.string.online_capture_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.records_close)) } })
    }
}

@Composable private fun TranslationChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, null, enabled = enabled); Text(label) }
}
@Composable private fun TranslationCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Checkbox, onChange), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, null, enabled = enabled); Text(label) }
}
@Composable internal fun PrivateTranslationCaption(state: PrivateTranslationController, onOpen: () -> Unit) {
    state.version
    val current = state.session.status?.current
    if (current?.state !in MeetingTranslationRepository.activeStates && !state.seenRun) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.translation_title) + " · " + stringResource(if (state.fresh) translationStatus(current?.state) else R.string.online_capture_unknown_state), style = MaterialTheme.typography.labelSmall)
                state.session.rows.lastOrNull()?.let { Text(it.text + it.stash, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.translation_controls)) }
        }
    }
}
private fun translationStatus(value: String?) = when (value) {
    "starting" -> R.string.translation_starting
    "translating" -> R.string.translation_running
    "stopping" -> R.string.translation_stopping
    "stopped" -> R.string.translation_stopped
    "incomplete" -> R.string.translation_incomplete
    else -> R.string.translation_off
}
