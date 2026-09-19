package com.we.meet.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import com.we.meet.R
import com.we.meet.data.api.dto.RecordAttributionCandidateDto
import com.we.meet.data.api.dto.RecordSpeakerDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

import kotlinx.coroutines.launch

/** Matches the server's bound on a directory search, checked before sending. */
private const val MAX_SEARCH_LENGTH = 80

/**
 * One diarised track, with the control to say who it really was.
 *
 * "Speaker 1" is not attribution, so a reader who knows the room needs a way to
 * record that judgement. The server keeps the recogniser's label and resolves
 * one name for every reader-facing artifact, so this only ever chooses a person
 * — it never lets anyone rewrite what was heard.
 *
 * The control is absent, not disabled, when [RecordSpeakerDto.canAttribute] is
 * false: a button whose only outcome is a refusal is worse than no button.
 */
@Composable
internal fun AttributableSpeakerRow(
    repository: MeetingRecordRepository,
    viewer: String,
    recordId: String,
    revision: Int,
    speaker: RecordSpeakerDto,
    onAttributed: () -> Unit,
) {
    key(speaker.id, speaker.displayName, speaker.attributedUserId) {
        var picking by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (speaker.displayName ?: speaker.label).ifBlank {
                        stringResource(R.string.records_unknown_speaker)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (speaker.canAttribute) {
                    TextButton(onClick = { picking = !picking }) {
                        Text(
                            stringResource(
                                if (picking) R.string.records_correction_cancel
                                else R.string.records_attribution_change,
                            )
                        )
                    }
                }
            }
            if (picking) {
                AttributionPicker(
                    repository = repository,
                    viewer = viewer,
                    recordId = recordId,
                    revision = revision,
                    speakerId = speaker.id,
                    onDone = { picking = false; onAttributed() },
                )
            }
        }
    }
}

@Composable
private fun AttributionPicker(
    repository: MeetingRecordRepository,
    viewer: String,
    recordId: String,
    revision: Int,
    speakerId: String,
    onDone: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<RecordAttributionCandidateDto>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Read when the picker opens and again on each search, rather than with the
    // speaker list: most readers never attribute anyone. Searching is a form
    // submission, so typing must not fire a request per keystroke.
    LaunchedEffect(speakerId, submitted) {
        loadFailed = false
        candidates = null
        val result = repository.attributionCandidates(
            viewer = viewer,
            recordId = recordId,
            query = submitted.ifBlank { null },
        )
        if (result.isSuccess) candidates = result.getOrThrow() else loadFailed = true
    }

    fun bind(userId: String?) {
        if (busy) return
        busy = true
        saveFailed = false
        scope.launch {
            val result = repository.attributeSpeaker(
                viewer = viewer,
                recordId = recordId,
                revision = revision,
                speakerId = speakerId,
                userId = userId,
            )
            busy = false
            if (result.isSuccess) onDone() else saveFailed = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(Dimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(MAX_SEARCH_LENGTH) },
                enabled = !busy,
                label = { Text(stringResource(R.string.records_attribution_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !busy, onClick = { submitted = query.trim() }) {
                Text(stringResource(R.string.records_attribution_find))
            }
        }
        when {
            loadFailed -> Text(
                stringResource(R.string.records_source_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            candidates == null -> WeMeetInlineLoading()
            candidates.orEmpty().isEmpty() -> Text(
                stringResource(R.string.records_attribution_nobody),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(
                // A nested scrollable inside the tab's own list would fight it,
                // so the candidates scroll in place instead.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = Dimens.SheetContentMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                candidates.orEmpty().forEach { person ->
                    TextButton(enabled = !busy, onClick = { bind(person.id) }) {
                        Text(person.name.ifBlank { stringResource(R.string.records_owner_unknown) })
                    }
                }
            }
        }
        // Clearing is a real operation, not a refusal: attributing the wrong
        // colleague has to be undoable, and the label is still underneath.
        TextButton(enabled = !busy, onClick = { bind(null) }) {
            Text(stringResource(R.string.records_attribution_clear))
        }
        if (busy) WeMeetInlineLoading()
        if (saveFailed) {
            Text(
                stringResource(R.string.records_attribution_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
