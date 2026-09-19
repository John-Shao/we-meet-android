@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import android.app.Activity
import android.content.Intent
import android.widget.Toast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
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

/** What the picker needs to name and later open the file. */
private val EXPORT_MIME_TYPES = mapOf(
    "txt" to "text/plain",
    "srt" to "application/x-subrip",
    "vtt" to "text/vtt",
)

/**
 * The picker only uses this as a starting name, and the reader can change it, so
 * a short record-id prefix is enough — and it avoids putting a user-supplied
 * title through filesystem sanitising for a value they are about to edit anyway.
 */
private fun exportFileName(recordId: String, format: String): String =
    "transcript-${recordId.take(8)}.$format"

@Composable
internal fun RecordOriginals(
    repository: MeetingRecordRepository,
    viewer: String,
    record: RecordDto,
    onRefresh: () -> Unit,
    onSource: ((Long) -> Unit)? = null,
    positionMs: Long? = null,
) {
    var input by remember(viewer, record.id) { mutableStateOf("") }
    var query by remember(viewer, record.id) { mutableStateOf("") }
    var searchVisible by remember(viewer, record.id) { mutableStateOf(false) }
    var speakerId by remember(viewer, record.id, record.revision) { mutableStateOf<String?>(null) }
    var selectSpeaker by remember(viewer, record.id, record.revision) { mutableStateOf(false) }
    var cursors by remember(viewer, record.id, record.revision, query, speakerId) { mutableStateOf(listOf<String?>(null)) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportVisible by remember(viewer, record.id) { mutableStateOf(false) }
    var exportBusy by remember(viewer, record.id) { mutableStateOf(false) }
    var exportFormat by remember(viewer, record.id) { mutableStateOf<String?>(null) }
    /**
     * The reader chooses where the file goes, so the app needs no storage
     * permission and never guesses a path. The format is remembered until the
     * picker returns, since that result is what tells us the destination.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val format = exportFormat
        exportFormat = null
        val destination = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || format == null || destination == null) return@rememberLauncherForActivityResult
        exportBusy = true
        scope.launch {
            // Streamed straight to the chosen document; deleting on failure keeps
            // a truncated transcript from looking like a complete one.
            val written = runCatching {
                repository.transcriptExport(viewer, record.id, format).getOrThrow().use { body ->
                    val output = checkNotNull(context.contentResolver.openOutputStream(destination, "w"))
                    output.use { stream -> body.byteStream().use { it.copyTo(stream) } }
                }
            }
            if (written.isFailure) runCatching { context.contentResolver.delete(destination, null, null) }
            exportBusy = false
            Toast.makeText(
                context,
                context.getString(if (written.isFailure) R.string.records_export_failed else R.string.records_export_saved),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val page = visibleRead(viewer, record.id, record.revision, query, speakerId, cursors.last()) {
        repository.originals(viewer, record.id, record.revision, query.ifBlank { null }, speakerId, cursors.last())
    }
    /**
     * The unfiltered first page, read only to map a playback position onto a row.
     * Deriving the active window from the *filtered* rows would move the clock
     * whenever a reader filtered by speaker or searched, so highlight and audio
     * would disagree about where "now" is.
     *
     * Keyed on whether playback is running, never on the position itself:
     * `visibleRead` owns a polling loop, so keying it on a value that changes
     * several times a second would tear that loop down and re-fetch on every tick.
     */
    val followingPlayback = positionMs != null
    val timeline = visibleRead(viewer, record.id, record.revision, followingPlayback) {
        if (followingPlayback) {
            repository.originals(viewer, record.id, record.revision, null, null, null)
                .map { page -> page.results.map { row -> TimedRow(row.id, row.startMs ?: 0L, row.endMs) } }
        } else Result.success(emptyList())
    }
    val timelineRows = timeline?.getOrNull().orEmpty()
    // Derived from the unfiltered timeline, then matched against the visible rows.
    val activeId = positionMs?.let { activeRowId(timelineRows, it) }
    val activeDescription = stringResource(R.string.records_now_playing)
    val rows = page?.getOrNull()?.results.orEmpty()
    val activeIndex = rows.indexOfFirst { it.id == activeId }
    /**
     * Follow playback unless the reader is holding the list. `isScrollInProgress`
     * is true during a fling or drag, which is exactly the gesture that should win.
     */
    LaunchedEffect(activeIndex, activeId, listState.isScrollInProgress) {
        if (activeIndex < 0 || listState.isScrollInProgress) return@LaunchedEffect
        listState.animateScrollToItem(activeIndex)
    }
    val search = { query = input.trim(); cursors = listOf(null); keyboard?.hide(); Unit }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.ScreenPadding)) {
            if (searchVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
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
                                    correctable = record.sourceType in listOf("audio_recording", "upload"),
                                    onCorrected = onRefresh,
                                )
                            }
                    }
                }
            }
        }
        val current = page?.getOrNull()
        // 单页时这一行只剩下一个孤立的「刷新」挂在底部,不如不显示;失败/空态各自带重试。
        if (current != null && (cursors.size > 1 || current.nextCursor != null)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.SpaceBetween) {
                if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                current.nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.records_refresh)) }
            }
        }
    }
    if (selectSpeaker) {
        SpeakerPicker(repository, viewer, record, onRefresh, onClose = { selectSpeaker = false }) {
            speakerId = it
            selectSpeaker = false
        }
    }
    if (exportBusy) {
        // A short modal, not a snackbar: saving can outlive the screen.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.records_export_running)) },
            text = { WeMeetInlineLoading() },
            confirmButton = {},
        )
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
                            exportFormat = id
                            exportPicker.launch(
                                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    // A concrete type, not */*: the picker uses it
                                    // to name and later open the file.
                                    type = EXPORT_MIME_TYPES.getValue(id)
                                    putExtra(Intent.EXTRA_TITLE, exportFileName(record.id, id))
                                },
                            )
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
