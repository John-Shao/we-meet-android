@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordSourceChangedException
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import retrofit2.HttpException

@Composable
internal fun RecordOriginals(
    repository: MeetingRecordRepository,
    viewer: String,
    record: RecordDto,
    onRefresh: () -> Unit,
) {
    var input by remember(viewer, record.id) { mutableStateOf("") }
    var query by remember(viewer, record.id) { mutableStateOf("") }
    var speakerId by remember(viewer, record.id, record.revision) { mutableStateOf<String?>(null) }
    var selectSpeaker by remember(viewer, record.id, record.revision) { mutableStateOf(false) }
    var cursors by remember(viewer, record.id, record.revision, query, speakerId) { mutableStateOf(listOf<String?>(null)) }
    val keyboard = LocalSoftwareKeyboardController.current
    val page = visibleRead(viewer, record.id, record.revision, query, speakerId, cursors.last()) {
        repository.originals(viewer, record.id, record.revision, query.ifBlank { null }, speakerId, cursors.last())
    }
    val search = { query = input.trim(); cursors = listOf(null); keyboard?.hide(); Unit }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.ScreenPadding)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(200) },
                    label = { Text(stringResource(R.string.records_search_originals)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                TextButton(onClick = search) { Text(stringResource(R.string.records_search_action)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                if (record.sourceType == "audio_recording") {
                    TextButton(onClick = { selectSpeaker = true }) {
                        Text(stringResource(if (speakerId == null) R.string.records_speakers else R.string.records_speaker_filtered))
                    }
                }
                if (query.isNotBlank() || speakerId != null) {
                    TextButton(onClick = { input = ""; query = ""; speakerId = null }) { Text(stringResource(R.string.records_clear_filters)) }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when {
                page == null -> WeMeetInlineLoading()
                page.isFailure -> WeMeetErrorState(onRetry = onRefresh, message = stringResource(originalError(page.exceptionOrNull())))
                page.getOrThrow().results.isEmpty() -> WeMeetEmptyState(
                    stringResource(R.string.records_no_originals),
                    description = stringResource(R.string.records_no_originals_hint),
                    action = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.records_refresh)) } },
                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    items(page.getOrThrow().results, key = { it.id }) { original ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                Text(original.speakerLabel.ifBlank { stringResource(R.string.records_unknown_speaker) }, style = MaterialTheme.typography.titleSmall)
                                Text(original.startedAt?.let(::recordTime) ?: original.startMs?.let(::sourceTime).orEmpty(), style = MaterialTheme.typography.bodySmall)
                                Text(original.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
        if (page?.isSuccess == true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.SpaceBetween) {
                if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                page.getOrThrow().nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                if (page.getOrThrow().results.isNotEmpty()) TextButton(onClick = onRefresh) { Text(stringResource(R.string.records_refresh)) }
            }
        }
    }
    if (selectSpeaker) {
        SpeakerPicker(repository, viewer, record, onRefresh, onClose = { selectSpeaker = false }) {
            speakerId = it
            selectSpeaker = false
        }
    }
}

@Composable
private fun SpeakerPicker(
    repository: MeetingRecordRepository,
    viewer: String,
    record: RecordDto,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var cursors by remember(viewer, record.id, record.revision) { mutableStateOf(listOf<String?>(null)) }
    val page = visibleRead(viewer, record.id, record.revision, cursors.last()) {
        repository.speakers(viewer, record.id, record.revision, cursors.last())
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.records_speakers)) },
        text = {
            Column {
                TextButton(onClick = { onSelect(null) }) { Text(stringResource(R.string.records_all_speakers)) }
                when {
                    page == null -> WeMeetInlineLoading()
                    page.isFailure -> WeMeetInlineErrorState(onRetry = onRefresh, message = stringResource(originalError(page.exceptionOrNull())))
                    else -> {
                        if (page.getOrThrow().results.isEmpty()) Text(stringResource(R.string.records_no_speakers))
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(page.getOrThrow().results, key = { it.id }) { speaker ->
                                TextButton(onClick = { onSelect(speaker.id) }) {
                                    Text(if (speaker.identityType == "unknown" || speaker.label.isBlank()) stringResource(R.string.records_unknown_speaker) else speaker.label)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween) {
                            if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                            page.getOrThrow().nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.records_close)) } },
    )
}

private fun originalError(error: Throwable?): Int =
    if (error is RecordSourceChangedException || (error is HttpException && error.code() == 409)) R.string.records_source_changed
    else R.string.records_source_unavailable
