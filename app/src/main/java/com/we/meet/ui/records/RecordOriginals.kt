@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.we.meet.ui.records

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordSourceChangedException
import com.we.meet.ui.components.WeMeetInlineEmptyState
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
    onExport: (String) -> Unit,
    onSource: ((Long) -> Unit)? = null,
    positionMs: Long? = null,
) {
    var input by remember(viewer, record.id) { mutableStateOf("") }
    var query by remember(viewer, record.id) { mutableStateOf("") }
    var searchVisible by remember(viewer, record.id) { mutableStateOf(false) }
    var speakerId by remember(viewer, record.id, record.revision) { mutableStateOf<String?>(null) }
    var selectSpeaker by remember(viewer, record.id, record.revision) { mutableStateOf(false) }
    var cursors by remember(viewer, record.id, record.revision, query, speakerId) { mutableStateOf(listOf<String?>(null)) }
    var anchorMs by remember(viewer, record.id, record.revision) { mutableStateOf(0L) }
    var following by remember(viewer, record.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val correctionDrafts = remember(viewer, record.id) { OriginalCorrectionDrafts() }
    DisposableEffect(correctionDrafts) { onDispose { correctionDrafts.clear() } }
    var exportVisible by remember(viewer, record.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val filtered = query.isNotBlank() || speakerId != null
    val atMs = if (record.sourceType == "meeting") null else if (filtered) 0L else anchorMs
    val page = visibleRead(viewer, record.id, record.revision, query, speakerId, cursors.last(), atMs) {
        repository.originals(viewer, record.id, record.revision, query.ifBlank { null }, speakerId, cursors.last(), atMs)
    }
    val rows = page?.getOrNull()?.results.orEmpty()
    val readError = page?.exceptionOrNull()
    LaunchedEffect(readError) {
        if (readError is IllegalArgumentException || readError is HttpException && readError.code() in listOf(401, 403, 404)) correctionDrafts.clear()
    }
    val timelineRows = rows.mapNotNull { row -> row.startMs?.let { TimedRow(row.id, it, row.endMs) } }
    val activeId = positionMs?.let { activeRowId(timelineRows, it) }
    val followId = activeId ?: positionMs?.let { nearestStartedRowId(timelineRows, it) }
    val activeDescription = stringResource(R.string.records_now_playing)
    val followIndex = rows.indexOfFirst { it.id == followId }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) following = false }
    LaunchedEffect(positionMs, timelineRows, following, filtered, dragging) {
        if (!following || filtered || dragging || positionMs == null) return@LaunchedEffect
        transcriptWindowTarget(timelineRows, positionMs, anchorMs, page?.getOrNull()?.nextCursor != null)?.let {
            anchorMs = it
            cursors = listOf(null)
        }
    }
    LaunchedEffect(followIndex, followId, following, filtered) {
        if (!following || filtered || followIndex < 0 || listState.isScrollInProgress) return@LaunchedEffect
        listState.animateScrollToItem(followIndex)
    }
    val search = { query = input.trim(); cursors = listOf(null); keyboard?.hide(); Unit }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.ScreenPadding)) {
            if (searchVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value.take(200)
                        // 清空即撤销:与 Web 端逐字稿搜索同一口径(收口记录 §3.13),
                        // 也省掉一颗只为「再问一次」而存在的按钮。
                        if (input.isEmpty() && query.isNotEmpty()) { query = ""; cursors = listOf(null) }
                    },
                    label = { Text(stringResource(R.string.records_search_originals)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // 提交只剩键盘上的「搜索」—— 参考稿与 Web 端都没有独立的搜索按钮。
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                TextButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) { input = ""; query = ""; keyboard?.hide() } }) {
                    Text(stringResource(if (searchVisible) R.string.records_clear_search else R.string.records_search_originals))
                }
                if (record.sourceType in listOf("audio_recording", "upload")) {
                    TextButton(onClick = { selectSpeaker = true }) {
                        Text(stringResource(if (speakerId == null) R.string.records_filter_speaker else R.string.records_speaker_filtered))
                    }
                }
                if (query.isNotBlank() || speakerId != null) {
                    TextButton(onClick = { input = ""; query = ""; speakerId = null }) { Text(stringResource(R.string.records_clear_filters)) }
                }
                TextButton(onClick = { exportVisible = true }) { Text(stringResource(R.string.records_export_transcript)) }
                if (record.capabilities.batchCorrect) {
                    TranscriptReplacementControl(repository, viewer, record.id, onRefresh)
                }
            }
        }
        if (positionMs != null && (!following || filtered)) TextButton(onClick = {
            input = ""; query = ""; speakerId = null
            anchorMs = positionMs; cursors = listOf(null); following = true
        }) { Text(stringResource(R.string.records_back_to_playback)) }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when {
                page == null -> WeMeetInlineLoading()
                page.isFailure -> WeMeetErrorState(onRetry = onRefresh, message = stringResource(originalError(page.exceptionOrNull())))
                page.getOrThrow().results.isEmpty() -> WeMeetEmptyState(
                    stringResource(R.string.records_no_originals),
                    description = stringResource(R.string.records_no_originals_hint),
                    action = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.records_refresh)) } },
                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    items(page.getOrThrow().results, key = { it.id }) { original ->
                            val isActive = activeId != null && original.id == activeId
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (isActive) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                    )
                                    // The accessible signal is a state description, not just colour.
                                    .semantics {
                                        if (isActive) stateDescription = activeDescription
                                    }
                                    .padding(Dimens.ScreenPadding),
                                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                        Icon(Icons.Outlined.Person, null, Modifier.padding(Dimens.SpaceS).size(Dimens.IconSmall), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(original.speakerLabel.ifBlank { stringResource(R.string.records_unknown_speaker) }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (onSource != null && original.startMs != null) {
                                        TextButton(onClick = { onSource(original.startMs) }) { Text(stringResource(R.string.capture_playback_source, sourceTime(original.startMs))) }
                                    } else Text(original.startedAt?.let(::recordTime) ?: original.startMs?.let(::sourceTime).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                CorrectableOriginalText(
                                    repository = repository,
                                    viewer = viewer,
                                    recordId = record.id,
                                    revision = record.revision,
                                    segmentId = original.id,
                                    text = original.text,
                                    originalText = original.originalText,
                                    isCorrected = original.isCorrected,
                                    // An online transcript has no revision model,
                                    // so that source gets no edit control at all.
                                    correctable = original.canCorrect && original.correctionRevision != null,
                                    correctionRevision = original.correctionRevision ?: 0,
                                    onCorrected = onRefresh,
                                    onEditing = { following = false },
                                    draftState = correctionDrafts.get(original.id, original.text, original.correctionRevision ?: 0),
                                    writeScope = scope,
                                    // 命中处落在正文里(服务端只把不匹配的行过滤掉)。
                                    highlight = query,
                                )
                            }
                    }
                }
            }
        }
        val current = page?.getOrNull()
        if (current != null) {
            // 单页时这一行只会剩一个孤立的「刷新」—— 那条规则现在收在 RecordPager 里。
            RecordPager(
                hasPrevious = cursors.size > 1,
                onPrevious = { following = false; cursors = cursors.dropLast(1) },
                hasNext = current.nextCursor != null,
                onNext = { current.nextCursor?.let { next -> following = false; cursors = cursors + next } },
                onRefresh = onRefresh,
            )
        }
    }
    if (selectSpeaker) {
        SpeakerPicker(repository, viewer, record, onRefresh, onClose = { selectSpeaker = false }) {
            speakerId = it
            selectSpeaker = false
        }
    }
    if (exportVisible) {
        val formats = listOf("txt" to "TXT", "srt" to "SRT", "vtt" to "VTT")
        AlertDialog(
            onDismissRequest = { exportVisible = false },
            title = { Text(stringResource(R.string.records_export_choose_format)) },
            text = {
                Column {
                    formats.forEach { (id, label) ->
                        TextButton(onClick = {
                            exportVisible = false
                            onExport(id)
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exportVisible = false }) { Text(stringResource(R.string.records_close)) } },
        )
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
                        if (page.getOrThrow().results.isEmpty()) WeMeetInlineEmptyState(stringResource(R.string.records_no_speakers))
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
