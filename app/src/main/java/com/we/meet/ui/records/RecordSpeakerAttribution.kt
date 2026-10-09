package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordSpeakerDto
import com.we.meet.data.api.dto.RecordIdentityDecisionRequest
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Manual identity uses the record-scoped directory, never global account search. */
@Composable
internal fun AttributableSpeakerRow(
    repository: MeetingRecordRepository,
    viewer: String,
    recordId: String,
    revision: Int,
    speaker: RecordSpeakerDto,
    onAttributed: () -> Unit,
    onSource: ((Long) -> Unit)? = null,
) {
    key(viewer, recordId, speaker.id) {
        var editor by remember { mutableStateOf<SpeakerIdentityController?>(null) }
        val current = editor
        val idle = remember { MutableStateFlow(SpeakerIdentityState()) }
        // Always collect at one composition slot, including when the editor is closed.
        val panel by (current?.state ?: idle).collectAsState()
        val canEdit = speaker.canAttribute && speaker.identityType == "diarized"
        LaunchedEffect(current, revision, canEdit) {
            if (!canEdit) { current?.close(); editor = null } else current?.revisionChanged(revision)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            Text((speaker.displayName ?: speaker.label).ifBlank { stringResource(R.string.records_unknown_speaker) },
                style = MaterialTheme.typography.titleMedium)
            if (canEdit) TextButton(enabled = !panel.saving, onClick = {
                if (current != null) { current.close(); editor = null } else {
                    val operations = object : SpeakerIdentityOperations {
                        override suspend fun contacts(query: String?, kind: String, departmentId: String?, offset: Int) =
                            repository.speakerContacts(viewer, recordId, query, kind, departmentId, offset)
                        override suspend fun decide(request: RecordIdentityDecisionRequest) =
                            repository.speakerIdentityDecision(viewer, recordId, speaker.id, request)
                    }
                    editor = SpeakerIdentityController(operations, revision,
                        speaker.manualLabel.takeIf { speaker.attributionKind == "custom" }.orEmpty())
                }
            }) { Text(stringResource(if (current == null) R.string.speaker_identity_mark else R.string.records_correction_cancel)) }
            if (current != null && canEdit) SpeakerIdentityEditor(current, speaker, onSource) {
                if (editor === current) { current.close(); editor = null; onAttributed() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerIdentityEditor(
    controller: SpeakerIdentityController,
    speaker: RecordSpeakerDto,
    onSource: ((Long) -> Unit)? = null,
    onRefresh: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.reload() }
    fun save(clear: Boolean = false) { scope.launch { if (controller.save(clear)) onRefresh() } }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.speaker_identity_current, speaker.displayName ?: speaker.label),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.speaker_identity_manual_hint), style = MaterialTheme.typography.bodySmall)
            val interval = speaker.activity?.timeline?.takeIf { it.isUsable() }?.intervals?.firstOrNull()
            if (interval != null && onSource != null) TextButton(onClick = { onSource(interval.startMs) }) {
                Text(stringResource(R.string.speaker_identity_listen, sourceTime(interval.startMs)))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                listOf(SpeakerIdentityMode.CONTACTS to R.string.speaker_identity_contacts, SpeakerIdentityMode.LABEL to R.string.speaker_identity_label).forEach { (mode, title) ->
                    FilterChip(selected = state.mode == mode, enabled = state.editable,
                        onClick = { scope.launch { controller.mode(mode) } }, label = { Text(stringResource(title)) })
                }
            }
            if (state.mode == SpeakerIdentityMode.LABEL) {
                OutlinedTextField(value = state.label, onValueChange = controller::editLabel,
                    enabled = !state.saving, readOnly = state.blocked,
                    label = { Text(stringResource(R.string.speaker_identity_label)) }, singleLine = true,
                    isError = state.label.isNotEmpty() && cleanSpeakerLabel(state.label) == null,
                    supportingText = { Text(stringResource(R.string.speaker_identity_label_hint)) }, modifier = Modifier.fillMaxWidth())
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    listOf("all" to R.string.speaker_identity_all, "member" to R.string.speaker_identity_members,
                        "external" to R.string.speaker_identity_external, "departments" to R.string.speaker_identity_departments).forEach { (kind, title) ->
                        FilterChip(selected = state.kind == kind && state.department == null, enabled = state.editable,
                            onClick = { scope.launch { controller.filter(kind) } }, label = { Text(stringResource(title)) })
                    }
                }
                state.department?.let { department ->
                    Text(stringResource(R.string.speaker_identity_department, department.name))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = state.query, onValueChange = controller::editQuery, enabled = state.editable,
                        label = { Text(stringResource(R.string.records_attribution_search)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = state.editable && !state.loading, onClick = { scope.launch { controller.search() } }) {
                        Text(stringResource(R.string.records_attribution_find))
                    }
                }
                when {
                    state.loading -> WeMeetInlineLoading()
                    state.failure == SpeakerIdentityFailure.LOAD -> {
                        Text(stringResource(R.string.records_source_unavailable), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { scope.launch { controller.reload() } }) { Text(stringResource(R.string.speaker_identity_retry)) }
                    }
                    !state.blocked && state.page.results.isEmpty() -> Text(stringResource(R.string.records_attribution_nobody))
                    !state.blocked -> {
                        Column(Modifier.fillMaxWidth().heightIn(max = Dimens.SheetContentMaxHeight).verticalScroll(rememberScrollState())) {
                            state.page.results.forEach { person ->
                                FilterChip(selected = state.selected?.ref == person.ref, enabled = state.editable,
                                    onClick = { scope.launch { controller.choose(person) } }, label = {
                                        Column {
                                            Text(person.name.ifBlank { stringResource(R.string.records_owner_unknown) })
                                            val detail = listOf(person.organizationName, person.departmentName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                                        }
                                    })
                            }
                        }
                        Row {
                            if (state.offset > 0) TextButton(enabled = state.editable, onClick = { scope.launch { controller.page(false) } }) { Text(stringResource(R.string.records_previous)) }
                            if (state.page.nextOffset != null) TextButton(enabled = state.editable, onClick = { scope.launch { controller.page(true) } }) { Text(stringResource(R.string.records_next)) }
                        }
                    }
                }
                state.selected?.let { Text(stringResource(R.string.speaker_identity_selected, it.name)) }
                if (state.kind == "external" || state.selected?.kind == "external") Text(
                    stringResource(R.string.speaker_identity_external_hint), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                TextButton(enabled = state.canSave, onClick = { save() }) { Text(stringResource(R.string.records_correction_save)) }
                TextButton(enabled = state.editable, onClick = { save(clear = true) }) { Text(stringResource(R.string.records_attribution_clear)) }
            }
            if (state.saving) WeMeetInlineLoading()
            when (state.failure) {
                SpeakerIdentityFailure.SAVE -> Text(stringResource(R.string.records_attribution_failed), color = MaterialTheme.colorScheme.error)
                SpeakerIdentityFailure.CONFLICT, SpeakerIdentityFailure.ACCESS -> {
                    Text(stringResource(if (state.failure == SpeakerIdentityFailure.CONFLICT) R.string.speaker_identity_conflict else R.string.speaker_identity_access), color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !state.saving, onClick = onRefresh) { Text(stringResource(R.string.speaker_identity_reload)) }
                }
                else -> Unit
            }
        }
    }
}
