package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.we.meet.data.api.dto.InterpretationChannelRequestDto
import com.we.meet.data.repository.MeetingInterpretationRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

@Composable
internal fun InterpretationPanel(state: InterpretationController, viewer: String, room: String, sid: String,
    speakerName: (String) -> String? = { null }, onRecord: (String) -> Unit = {}) {
    state.version
    if (viewer.isBlank()) { Text(stringResource(R.string.interpretation_guest)); return }
    val observed = state.read?.getOrNull()
    var confirmation by remember(state) { mutableStateOf<InterpretationChannelRequestDto?>(null) }
    LaunchedEffect(state.resumed) { if (!state.resumed) confirmation = null }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.interpretation_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.interpretation_description), style = MaterialTheme.typography.bodySmall)
        when {
            state.read == null -> WeMeetInlineLoading()
            state.read?.isFailure == true -> WeMeetInlineErrorState(state.refresh, message = stringResource(R.string.interpretation_unavailable))
            observed != null -> {
                if (state.storageError) WeMeetInlineErrorState(state.refresh, message = stringResource(R.string.summary_controls_storage_error))
                if (state.pending) Text(stringResource(R.string.interpretation_unknown))
                if (state.pendingChannel != null && observed.canControl) Button(state.retryChannel, enabled = state.enabled) { Text(stringResource(R.string.interpretation_retry_channel)) }
                if (state.pendingListen != null) Button(state.retryListen, enabled = state.enabled) { Text(stringResource(R.string.interpretation_retry_listen)) }
                for (language in observed.languages) {
                    val channel = observed.channels.singleOrNull { it.target == language }
                    val active = channel?.state in MeetingInterpretationRepository.activeStates
                    val listening = channel != null && state.session.desired?.channelId == channel.id
                    var save by remember(state, language, channel?.id) { mutableStateOf(false) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                            Text(stringResource(if (language == "zh") R.string.interpretation_zh else R.string.interpretation_en), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(interpretationStatus(channel?.state)), style = MaterialTheme.typography.bodySmall)
                            if (listening) {
                                Button(onClick = { state.choose(null) }, enabled = state.enabled && !state.pending) { Text(stringResource(R.string.interpretation_leave)) }
                            } else if (channel?.state in setOf("prepared", "starting", "translating")) {
                                Button(onClick = { state.choose(channel) }, enabled = state.enabled && !state.pending && state.fresh && observed.available && state.session.connection != null) { Text(stringResource(if (language == "zh") R.string.interpretation_listen_zh else R.string.interpretation_listen_en)) }
                            }
                            if (observed.canControl) {
                                if (active) {
                                    TextButton(onClick = { confirmation = InterpretationChannelRequestDto(room, sid, "stop", language, channel!!.id) }, enabled = state.enabled && !state.pending && channel?.state != "stopping") { Text(stringResource(if (language == "zh") R.string.interpretation_stop_zh else R.string.interpretation_stop_en)) }
                                } else if (observed.available) {
                                    if (observed.archiveAvailable) Row(Modifier.fillMaxWidth().toggleable(save, state.enabled && !state.pending, Role.Checkbox) { save = it }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                        Checkbox(save, null, enabled = state.enabled && !state.pending)
                                        Text(stringResource(R.string.translation_save), style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(onClick = { confirmation = InterpretationChannelRequestDto(room, sid, "start", language, channel?.id, save) }, enabled = state.enabled && !state.pending && state.fresh) { Text(stringResource(if (language == "zh") R.string.interpretation_start_zh else R.string.interpretation_start_en)) }
                                }
                            }
                            channel?.archiveRecordId?.let { id -> TextButton(onClick = { onRecord(id) }) { Text(stringResource(R.string.translation_open_archive)) } }
                        }
                    }
                }
                if (state.session.connection == null) Text(stringResource(R.string.interpretation_connection), style = MaterialTheme.typography.bodySmall)
                if (state.session.desired != null) {
                    if (!state.session.ready) Text(stringResource(R.string.interpretation_wait_ready))
                    TextButton(onClick = { state.sound(state.session.muted) }, enabled = state.resumed && (!state.session.muted || state.fresh && !state.pending && !state.busy)) { Text(stringResource(if (state.session.muted) R.string.translation_listen else R.string.translation_mute)) }
                }
                if (state.error) Text(stringResource(R.string.interpretation_error), color = MaterialTheme.colorScheme.error)
                if (state.busy) WeMeetInlineLoading()
                if (state.session.rows.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.translation_text), style = MaterialTheme.typography.titleSmall)
                    state.session.rows.forEach { row ->
                        Text((speakerName(row.sourceSid)?.take(100) ?: stringResource(R.string.interpretation_speaker)) + " · " + stringResource(if (row.final) R.string.translation_confirmed else R.string.translation_candidate), style = MaterialTheme.typography.labelSmall)
                        Text(row.text + row.stash, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (state.resumed && observed?.canControl == true && !state.pending) confirmation?.let { request ->
        val current = observed.channels.singleOrNull { it.target == request.target }
        val active = current?.state in MeetingInterpretationRepository.activeStates
        val matches = current?.id == request.expectedChannelId && (request.operation == "start" && !active && observed.available && state.fresh || request.operation == "stop" && active && current?.state != "stopping")
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(if (request.target == "zh") R.string.interpretation_zh else R.string.interpretation_en)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(if (request.operation == "start") R.string.interpretation_start_effect else R.string.interpretation_stop_effect))
                if (request.operation == "start") Text(stringResource(if (request.saveTranslations == true) R.string.translation_save else R.string.translation_no_save))
            } }, confirmButton = { TextButton(onClick = { state.control(request); confirmation = null }, enabled = state.enabled && matches) { Text(stringResource(R.string.online_capture_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.records_close)) } })
    }
}

@Composable internal fun InterpretationCaption(state: InterpretationController, onOpen: () -> Unit) {
    state.version
    if (!state.seenListening && state.session.desired == null) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.interpretation_title) + " · " + stringResource(if (state.session.desired == null) R.string.interpretation_paused else if (state.session.channel?.target == "zh") R.string.interpretation_zh else R.string.interpretation_en), style = MaterialTheme.typography.labelSmall)
                state.session.rows.lastOrNull()?.let { Text(it.text + it.stash, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onOpen) { Text(stringResource(R.string.translation_controls)) }
        }
    }
}
private fun interpretationStatus(value: String?) = when (value) {
    "prepared" -> R.string.interpretation_prepared
    "starting" -> R.string.translation_starting
    "translating" -> R.string.translation_running
    "stopping" -> R.string.translation_stopping
    "stopped" -> R.string.translation_stopped
    "incomplete" -> R.string.translation_incomplete
    else -> R.string.interpretation_off
}
