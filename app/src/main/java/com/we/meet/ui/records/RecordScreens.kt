@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.dto.RecordReferenceDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordScope
import com.we.meet.data.repository.RecordSource
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Read only while visible. Errors and backgrounding remove the last private body. */
@Composable
private fun <T> visibleRead(vararg keys: Any?, read: suspend () -> Result<T>): Result<T>? {
    var result by remember(*keys) { mutableStateOf<Result<T>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                do {
                    result = read()
                    if (result?.isFailure == true) break
                    delay(15_000)
                } while (true)
            } finally {
                // Leave a failed result visible for explicit retry; clear on pause/disposal.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) result = null
            }
        }
    }
    return result
}

@Composable
fun RecordLibraryScreen(
    repository: MeetingRecordRepository,
    viewer: String,
    summariesOnly: Boolean,
    onRecord: (String) -> Unit,
    onBack: () -> Unit,
) {
    var scope by remember(viewer) { mutableStateOf(RecordScope.RECENT) }
    var source by remember(viewer) { mutableStateOf<RecordSource?>(null) }
    var input by remember(viewer) { mutableStateOf("") }
    var query by remember(viewer) { mutableStateOf("") }
    var cursors by remember(viewer, scope, source, query) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember { mutableIntStateOf(0) }
    val cursor = cursors.last()
    val result = visibleRead(viewer, scope, source, query, cursor, summariesOnly, refresh) {
        repository.records(viewer, scope, source, summariesOnly, query.ifBlank { null }, cursor)
    }
    Scaffold(
        topBar = { WeMeetTopBar(stringResource(if (summariesOnly) R.string.records_minutes else R.string.records_title), onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(bottom = Dimens.SpaceS)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    RecordScope.entries.forEach { value ->
                        FilterChip(selected = scope == value, onClick = { scope = value }, label = { Text(stringResource(scopeLabel(value))) })
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    listOf(null, RecordSource.MEETING, RecordSource.AUDIO).forEach { value ->
                        FilterChip(selected = source == value, onClick = { source = value }, label = { Text(stringResource(sourceLabel(value?.wire))) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    OutlinedTextField(value = input, onValueChange = { if (it.length <= 200) input = it }, singleLine = true,
                        label = { Text(stringResource(R.string.records_search)) }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { query = input.trim(); refresh++ }) { Text(stringResource(R.string.records_search_action)) }
                }
            }
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                result.getOrThrow().results.isEmpty() -> WeMeetEmptyState(
                    stringResource(R.string.records_empty),
                    description = stringResource(R.string.records_empty_hint),
                    action = { TextButton(onClick = { cursors = listOf(null); refresh++ }) { Text(stringResource(R.string.records_refresh)) } },
                )
                else -> {
                    val page = result.getOrThrow()
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        items(page.results, key = { it.id }) { record ->
                            Card(onClick = { onRecord(record.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                    Text(record.title, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(sourceLabel(record.sourceType)), style = MaterialTheme.typography.labelMedium)
                                    Text(recordTime(record.originAt), style = MaterialTheme.typography.bodySmall)
                                    if (record.isOngoing) Text(stringResource(R.string.records_ongoing), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                                page.nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordDetailScreen(repository: MeetingRecordRepository, viewer: String, recordId: String, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var cursors by remember(viewer, recordId) { mutableStateOf(listOf<String?>(null)) }
    var citation by remember(viewer, recordId) { mutableStateOf<Pair<String, RecordReferenceDto>?>(null) }
    val detail = visibleRead(viewer, recordId, refresh) { repository.record(viewer, recordId) }
    val record = detail?.getOrNull()
    Scaffold(topBar = { WeMeetTopBar(record?.title ?: stringResource(R.string.records_minutes), onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail == null -> WeMeetInlineLoading()
                detail.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                record?.capabilities?.readSummary != true -> WeMeetEmptyState(stringResource(R.string.records_no_summary_access))
                else -> {
                    val cursor = cursors.last()
                    val summaries = visibleRead(viewer, recordId, cursor, refresh) { repository.summaries(viewer, recordId, cursor) }
                    Text(recordTime(record.originAt), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(Dimens.ScreenPadding), style = MaterialTheme.typography.bodySmall)
                    when {
                        summaries == null -> WeMeetInlineLoading()
                        summaries.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                        summaries.getOrThrow().results.isEmpty() -> WeMeetEmptyState(
                            stringResource(R.string.records_no_versions),
                            description = stringResource(R.string.records_no_versions_hint),
                            action = { TextButton(onClick = { cursors = listOf(null); refresh++ }) { Text(stringResource(R.string.records_refresh)) } },
                        )
                        else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                            items(summaries.getOrThrow().results, key = { it.id }) { version ->
                                SummaryCard(version, record.capabilities.readTranscript) { ref -> citation = version.inputSnapshotId to ref }
                            }
                            item {
                                Row(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), horizontalArrangement = Arrangement.SpaceBetween) {
                                    if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                                    summaries.getOrThrow().nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                }
            }
            if (detail?.isSuccess == true && record?.capabilities?.readTranscript == true) citation?.let { (snapshot, reference) ->
                val original = visibleRead(viewer, recordId, snapshot, reference, refresh) { repository.citation(viewer, recordId, snapshot, reference) }
                AlertDialog(onDismissRequest = { citation = null }, title = { Text(stringResource(R.string.records_source)) },
                    text = {
                        when {
                            original == null -> WeMeetInlineLoading()
                            original.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_source_unavailable))
                            else -> LazyColumn { item { Text(original.getOrThrow().text) } }
                        }
                    }, confirmButton = { TextButton(onClick = { citation = null }) { Text(stringResource(R.string.records_close)) } })
            }
        }
    }
}

@Composable
private fun SummaryCard(version: RecordSummaryVersionDto, originals: Boolean, onSource: (RecordReferenceDto) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(when (version.stage) {
                "realtime" -> R.string.records_live
                "quick" -> R.string.records_quick
                "final" -> R.string.records_final
                else -> R.string.records_minutes
            }), style = MaterialTheme.typography.titleMedium)
            Text(recordTime(version.createdAt), style = MaterialTheme.typography.bodySmall)
            if (!version.isCurrent) Text(stringResource(R.string.records_historical), style = MaterialTheme.typography.labelMedium)
            if (version.stage != "final") Text(stringResource(R.string.records_provisional), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(when (version.asrStatus) {
                "in_progress" -> R.string.records_recognizing
                "finished" -> R.string.records_finished
                "incomplete" -> R.string.records_incomplete
                else -> R.string.records_coverage_unknown
            }), style = MaterialTheme.typography.bodySmall)
            Text(version.content.overview)
            listOf(R.string.records_decisions to version.content.decisions, R.string.records_chapters to version.content.chapters,
                R.string.records_actions to version.content.actionItems, R.string.records_questions to version.content.openQuestions).forEach { (label, points) ->
                if (points.isNotEmpty()) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                    points.forEach { point ->
                        Text(point.text)
                        listOfNotNull(point.ownerText, point.dueText).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (originals) point.sourceRefs.forEach { ref ->
                            TextButton(onClick = { onSource(ref) }) { Text(stringResource(R.string.records_source_at, sourceTime(ref.startMs))) }
                        }
                    }
                }
            }
        }
    }
}

private fun scopeLabel(scope: RecordScope): Int = when (scope) {
    RecordScope.RECENT -> R.string.records_recent
    RecordScope.OWNED -> R.string.records_owned
    RecordScope.PARTICIPATED -> R.string.records_participated
    RecordScope.SHARED -> R.string.records_shared
}
private fun sourceLabel(source: String?): Int = when (source) {
    "meeting" -> R.string.records_online
    "audio_recording" -> R.string.records_audio
    "upload" -> R.string.records_uploaded
    else -> R.string.records_all
}
private fun recordTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
}.getOrDefault("")
private fun sourceTime(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
