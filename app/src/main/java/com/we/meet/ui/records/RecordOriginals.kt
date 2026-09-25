@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.we.meet.ui.records

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
    onWordSource: ((Long) -> Unit)? = null,
    positionMs: Long? = null,
    /**
     * 「转写管理」的菜单项标签 —— 有值就说明这条记录能管转写(只对 AI 录音、且当前
     * 用户能控制采集的记录有值)。**只传文本**,不要在这里套按钮:菜单项是「整行
     * 可点的纯文本」,嵌一颗 TextButton 会让那一项带上主色和按钮内边距,和旁边三项
     * 既不同色也不同缩进(踩过)。真正的开合由 [onManageTranscription] 负责。
     */
    transcriptionLabel: (@Composable () -> Unit)? = null,
    onManageTranscription: (() -> Unit)? = null,
    /** 「批量查找替换」的菜单项标签,同样是纯文本(理由见上)。 */
    replacementLabel: (@Composable () -> Unit)? = null,
    onManageReplacement: (() -> Unit)? = null,
    followState: TranscriptFollowState = remember(viewer, record.id) { TranscriptFollowState() },
) {
    var input by rememberSaveable(viewer, record.id) { mutableStateOf("") }
    var query by rememberSaveable(viewer, record.id) { mutableStateOf("") }
    var searchVisible by rememberSaveable(viewer, record.id) { mutableStateOf(false) }
    var actionsVisible by remember(viewer, record.id) { mutableStateOf(false) }
    var speakerId by rememberSaveable(viewer, record.id, record.revision) { mutableStateOf<String?>(null) }
    var selectSpeaker by remember(viewer, record.id, record.revision) { mutableStateOf(false) }
    var anchorMs by rememberSaveable(viewer, record.id, record.revision) { mutableStateOf(0L) }
    val following = followState.following
    LaunchedEffect(followState.resumeToken) {
        if (followState.resumeToken > 0) {
            input = ""; query = ""; speakerId = null
            // Resume within the current page. Re-anchoring at the playhead
            // removes all preceding rows from the server's forward-only window.
            // Filter changes reset the read scope; the window effect below loads
            // another page only when the playhead actually leaves this one.
        }
    }
    val scope = rememberCoroutineScope()
    val correctionDrafts = remember(viewer, record.id) { OriginalCorrectionDrafts() }
    val editing = correctionDrafts.isEditing()
    DisposableEffect(correctionDrafts, followState) {
        followState.canResume = { !correctionDrafts.isEditing() }
        onDispose { followState.canResume = { true }; correctionDrafts.clear() }
    }
    var exportVisible by remember(viewer, record.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val filtered = query.isNotBlank() || speakerId != null
    LaunchedEffect(filtered) { if (filtered) followState.following = false }
    val atMs = if (record.sourceType == "meeting") null else if (filtered) 0L else anchorMs
    val continuous = rememberRecordContinuousRead(viewer, record.id, record.revision, query, speakerId, atMs,
        initial = null as String?, next = { it.nextCursor },
        intervalMs = if (record.isOngoing && !editing) 15_000L else null,
        merge = { pages -> pages.last().copy(results = pages.flatMap { it.results }.distinctBy { it.id }) },
        read = { cursor -> repository.originals(viewer, record.id, record.revision, query.ifBlank { null }, speakerId, cursor, atMs) })
    val page = continuous.result
    RecordAutoLoad(continuous, listState, disabled = editing)
    val refreshText: () -> Unit = { if (page?.exceptionOrNull() is RecordSourceChangedException) onRefresh() else continuous.refresh() }
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
    LaunchedEffect(dragging) { if (dragging) followState.following = false }
    LaunchedEffect(positionMs, timelineRows, following, filtered, dragging, editing) {
        if (!following || filtered || editing || dragging || positionMs == null) return@LaunchedEffect
        transcriptWindowTarget(timelineRows, positionMs, anchorMs, page?.getOrNull()?.nextCursor != null)?.let {
            anchorMs = it
        }
    }
    LaunchedEffect(followIndex, followId, following, filtered, editing, dragging, followState.resumeToken) {
        if (!following || filtered || editing || dragging || followIndex < 0) return@LaunchedEffect
        listState.animateScrollToItem(followIndex)
    }
    val search = { query = input.trim(); keyboard?.hide(); Unit }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.ScreenPadding)) {
            if (searchVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        followState.following = false
                        input = value.take(200)
                        // 清空即撤销:与 Web 端逐字稿搜索同一口径(收口记录 §3.13),
                        // 也省掉一颗只为「再问一次」而存在的按钮。
                        if (input.isEmpty() && query.isNotEmpty()) { query = "" }
                    },
                    label = { Text(stringResource(R.string.records_search_originals)) },
                    singleLine = true,
                    enabled = !editing,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // 提交只剩键盘上的「搜索」—— 参考稿与 Web 端都没有独立的搜索按钮。
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                IconButton(onClick = { input = ""; query = ""; searchVisible = false; keyboard?.hide() }, enabled = !editing) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.records_clear_search))
                }
            }
            // 动作收进溢出菜单:原先四个文字按钮在 FlowRow 里换行,「批量查找替换」
            // 单独掉到第二行 —— 一条只有一颗按钮的工具栏,白占 40dp 屏高。收进菜单后
            // 工具栏恒为一行,也不再依赖「四个中文标签刚好放得下」这种巧合。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { followState.following = false; searchVisible = !searchVisible; if (!searchVisible) { input = ""; query = ""; keyboard?.hide() } },
                    enabled = !editing,
                    modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget),
                ) {
                    Text(stringResource(if (searchVisible) R.string.records_clear_search else R.string.records_search_originals), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                IconButton(onClick = refreshText, enabled = !editing && !continuous.busy) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.records_refresh))
                }
                Box {
                    IconButton(
                        onClick = { actionsVisible = true },
                        modifier = Modifier.size(Dimens.MinTouchTarget),
                    ) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.records_transcript_actions)) }
                    DropdownMenu(expanded = actionsVisible, onDismissRequest = { actionsVisible = false }) {
                        if (anchorMs > 0) DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_read_start)) }, enabled = !editing,
                            onClick = { actionsVisible = false; followState.following = false; anchorMs = 0L },
                        )
                        if (positionMs != null) DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_back_to_playback)) },
                            enabled = !editing && !following,
                            onClick = { actionsVisible = false; followState.resume() },
                        )
                        // 转写管理排在最前:它是这条记录自己的转写状态(重试 / 清理),
                        // 比「按发言人筛选」这类浏览动作更该先被看到。
                        if (transcriptionLabel != null && onManageTranscription != null) {
                            DropdownMenuItem(text = transcriptionLabel, onClick = { actionsVisible = false; onManageTranscription() })
                        }
                        if (record.sourceType in listOf("audio_recording", "upload")) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (speakerId == null) R.string.records_filter_speaker else R.string.records_speaker_filtered)) },
                                onClick = { actionsVisible = false; selectSpeaker = true },
                            )
                        }
                        if (query.isNotBlank() || speakerId != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.records_clear_filters)) },
                                onClick = { actionsVisible = false; input = ""; query = ""; speakerId = null },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_export_transcript)) },
                            onClick = { actionsVisible = false; exportVisible = true },
                        )
                        if (replacementLabel != null && onManageReplacement != null) {
                            DropdownMenuItem(text = replacementLabel, onClick = { actionsVisible = false; onManageReplacement() })
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when {
                page == null -> WeMeetInlineLoading()
                page.isFailure -> WeMeetErrorState(onRetry = refreshText, message = stringResource(originalError(page.exceptionOrNull())))
                page.getOrThrow().results.isEmpty() -> WeMeetEmptyState(
                    stringResource(R.string.records_no_originals),
                    description = stringResource(R.string.records_no_originals_hint),

                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    items(page.getOrThrow().results, key = { it.id }) { original ->
                            val correctable = original.canCorrect && original.correctionRevision != null
                            val correctionState = correctionDrafts.get(original.id, original.text, original.correctionRevision ?: 0)
                            val isActive = activeId != null && original.id == activeId
                            val words = remember(original.text, original.playbackAlignment) {
                                validatedWords(original.text, original.playbackAlignment)
                            }
                            val wordMode = words.isNotEmpty() && onWordSource != null
                            val activeColor = MaterialTheme.colorScheme.primary
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (isActive && !wordMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                                        else MaterialTheme.colorScheme.surface,
                                    )
                                    .drawBehind {
                                        if (isActive) drawRect(activeColor,
                                            topLeft = Offset(0f, Dimens.ScreenPadding.toPx()),
                                            size = Size(Dimens.RecordPlayback.ActiveIndicatorWidth.toPx(), (size.height - Dimens.ScreenPadding.toPx() * 2).coerceAtLeast(0f)))
                                    }
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
                                        val sourceLabel = stringResource(R.string.capture_playback_source, sourceTime(original.startMs))
                                        TextButton(onClick = { onSource(original.startMs) },
                                            modifier = Modifier.semantics { contentDescription = sourceLabel }) {
                                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(Dimens.IconSmall))
                                            Text(playbackTime(original.startMs), modifier = Modifier.clearAndSetSemantics {},
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                    } else Text(original.startedAt?.let(::recordTime) ?: original.startMs?.let(::sourceTime).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (correctable && !correctionState.editing.value) {
                                        TextButton(enabled = !correctionState.busy.value, onClick = {
                                            correctionState.draft.value = original.text
                                            correctionState.editRevision.value = original.correctionRevision ?: 0
                                            correctionState.failed.value = false
                                            correctionState.conflict.value = false
                                            followState.following = false
                                            correctionState.editing.value = true
                                        }) {
                                            Text(stringResource(R.string.records_correction_edit))
                                        }
                                    }
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
                                    correctable = correctable,
                                    correctionRevision = original.correctionRevision ?: 0,
                                    onCorrected = { continuous.refresh() },
                                    onEditing = { followState.following = false },
                                    draftState = correctionState,
                                    writeScope = scope,
                                    // 命中处落在正文里(服务端只把不匹配的行过滤掉)。
                                    highlight = query,
                                    words = words,
                                    activeWord = if (isActive) activeWordIndex(words, positionMs) else -1,
                                    wordFollowing = following && !filtered && !editing && !dragging,
                                    onWordSeek = onWordSource,
                                )
                            }
                    }
                    item(key = "load-more") { RecordLoadMore(continuous, disabled = editing) }
                }
            }
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
