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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
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
import kotlinx.coroutines.awaitCancellation
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Read only while visible. Errors and backgrounding remove the last private body. */
@Composable
internal fun <T> visibleRead(vararg keys: Any?, intervalMs: Long = 15_000, stopWhen: (T) -> Boolean = { false }, read: suspend () -> Result<T>): Result<T>? {
    var result by remember(*keys) { mutableStateOf<Result<T>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRead by rememberUpdatedState(read)
    val latestStopWhen by rememberUpdatedState(stopWhen)
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                do {
                    result = latestRead()
                    if (result?.isFailure == true) break
                    if (result?.getOrNull()?.let(latestStopWhen) == true) awaitCancellation()
                    delay(intervalMs)
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
fun RecordDetailScreen(repository: MeetingRecordRepository, viewer: String, recordId: String, onBack: () -> Unit, summaryVersionId: String? = null, onTask: ((String) -> Unit)? = null, onDocument: ((String) -> Unit)? = null) {
    val app = LocalContext.current.applicationContext as? WeMeetApp
    var audioSeek by remember(viewer, recordId) { mutableStateOf<CaptureAudioSeek?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedVersion by remember(viewer, recordId, summaryVersionId) { mutableStateOf(summaryVersionId) }
    var cursors by remember(viewer, recordId, selectedVersion) { mutableStateOf(listOf<String?>(null)) }
    var citation by remember(viewer, recordId, summaryVersionId) { mutableStateOf<Pair<String, RecordReferenceDto>?>(null) }
    var detailTab by remember(viewer, recordId, summaryVersionId) { mutableStateOf(if (summaryVersionId != null) "summary" else "text") }
    val detail = visibleRead(viewer, recordId, refresh) { repository.record(viewer, recordId) }
    val record = detail?.getOrNull()
    val canPlay = app != null && record?.sourceType == "audio_recording" && record.capabilities.readTranscript && record.retentionMode == "media" && !record.isOngoing
    Scaffold(topBar = { WeMeetTopBar(stringResource(R.string.records_title), onBack = onBack,
        actions = { record?.let { RecordRenameAction(repository, viewer, it) { refresh++ } } }) }, containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail == null -> WeMeetInlineLoading()
                detail.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                record == null -> WeMeetEmptyState(stringResource(R.string.records_unavailable))
                else -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        Text(record.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${recordTime(record.originAt)} · ${stringResource(sourceLabel(record.sourceType))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val canReadTranslations = record.capabilities.readTranscript && app != null && (record.sourceType == "meeting" || record.sourceType == "audio_recording" && record.captureId != null)
                    val tabs = buildList {
                        if (record.capabilities.readTranscript) add("text" to R.string.records_originals)
                        if (record.capabilities.readSummary) add("summary" to R.string.records_minutes)
                        if (record.capabilities.readTranscript && record.sourceType in listOf("audio_recording", "upload")) add("speakers" to R.string.records_speakers)
                        add("info" to R.string.records_info)
                        if (canReadTranslations) add("translations" to R.string.archives_title)
                    }
                    val selectedTab = detailTab.takeIf { tab -> tabs.any { it.first == tab } } ?: tabs.first().first
                    val showTranslations = selectedTab == "translations"
                    val showOriginals = selectedTab == "text"
                    if (record.sourceType == "upload" && app != null) RecordingUploadStatus(app.recordingUploadRepository, viewer, recordId)
                    ScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == selectedTab }, edgePadding = Dimens.SpaceS, containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEach { (value, label) -> Tab(selected = value == selectedTab, onClick = { detailTab = value }, text = { Text(stringResource(label)) }) }
                    }
                    if (selectedTab == "info") {
                        RecordInfo(record, Modifier.weight(1f))
                    } else if (selectedTab == "speakers") {
                        RecordSpeakers(repository, viewer, record, Modifier.weight(1f))
                    } else if (showTranslations) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            if (record.sourceType == "audio_recording") CaptureTranslationArchives(viewer, requireNotNull(record.captureId), recordId, requireNotNull(app).captureTranslationRepository)
                            else RecordTranslationArchives(viewer, recordId, requireNotNull(app).translationArchiveRepository)
                        }
                    } else if (showOriginals) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            RecordOriginals(repository, viewer, record, onRefresh = { refresh++ }, onSource = if (canPlay) ({ audioSeek = CaptureAudioSeek(it) }) else null)
                        }
                    } else if (!record.capabilities.readSummary) {
                        WeMeetEmptyState(stringResource(R.string.records_no_summary_access))
                    } else {
                        val cursor = cursors.last()
                        val summaries = visibleRead(viewer, recordId, cursor, selectedVersion, refresh) {
                            repository.summaries(viewer, recordId, if (selectedVersion == null) cursor else null, selectedVersion)
                        }
                        Text(recordTime(record.originAt), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(Dimens.ScreenPadding), style = MaterialTheme.typography.bodySmall)
                        if (selectedVersion != null) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.ScreenPadding)) {
                                Text(stringResource(R.string.records_linked_version), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { selectedVersion = null }) { Text(stringResource(R.string.records_all_versions)) }
                            }
                        }
                        when {
                            summaries == null -> WeMeetInlineLoading()
                            summaries.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                                if (selectedVersion == null) item {
                                    if (app != null) Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                                        RecordSummaryControls(viewer, record, app.meetingSummaryRepository) { app.captureAccount }
                                        RecordHumanSummary(viewer, record, summaries.getOrThrow().results.firstOrNull(), app.meetingReviewRepository,
                                            { app.captureAccount }, onTask) { snapshot, reference -> citation = snapshot to reference }
                                        RecordQuestions(viewer, record, summaries.getOrThrow().results, app.meetingQuestionRepository,
                                            { app.captureAccount }) { snapshot, reference -> citation = snapshot to reference }
                                        RecordNotifications(viewer, record, app.meetingDeliveryRepository, { app.captureAccount }) { selectedVersion = it }
                                        RecordSharing(viewer, record, app.meetingSharingRepository) { app.captureAccount }
                                    }
                                }
                                if (summaries.getOrThrow().results.isEmpty()) item {
                                    WeMeetEmptyState(
                                        stringResource(if (selectedVersion == null) R.string.records_no_versions else R.string.records_linked_version_unavailable),
                                        description = if (selectedVersion == null) stringResource(R.string.records_no_versions_hint) else null,
                                        action = { TextButton(onClick = { cursors = listOf(null); refresh++ }) { Text(stringResource(R.string.records_refresh)) } },
                                    )
                                }
                                if (app != null && onDocument != null) item {
                                    RecordExports(viewer, record, summaries.getOrThrow().results, app.meetingDeliveryRepository,
                                        app.meetingReviewRepository, { app.captureAccount }, onDocument)
                                }
                                items(summaries.getOrThrow().results, key = { it.id }) { version ->
                                    SummaryCard(version, record.capabilities.readTranscript) { ref -> citation = version.inputSnapshotId to ref }
                                }
                                item {
                                    Row(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), horizontalArrangement = Arrangement.SpaceBetween) {
                                        if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                                        if (selectedVersion == null) summaries.getOrThrow().nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                    }
                }
            }
            if (canPlay) NativeCaptureAudioPlayer(viewer, recordId, requireNotNull(app).capturePlaybackRepository, { app.captureAccount }, audioSeek) { audioSeek = null }
            if (detail?.isSuccess == true && record?.capabilities?.readTranscript == true) citation?.let { (snapshot, reference) ->
                val original = visibleRead(viewer, recordId, snapshot, reference, refresh) { repository.citation(viewer, recordId, snapshot, reference) }
                AlertDialog(onDismissRequest = { citation = null }, title = { Text(stringResource(R.string.records_source)) },
                    text = {
                        when {
                            original == null -> WeMeetInlineLoading()
                            original.isFailure -> WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_source_unavailable))
                            else -> LazyColumn { item { Text(original.getOrThrow().text) } }
                        }
                    }, dismissButton = {
                        if (canPlay && original?.isSuccess == true) TextButton(onClick = { audioSeek = CaptureAudioSeek(reference.startMs); citation = null }) {
                            Text(stringResource(R.string.capture_playback_source, sourceTime(reference.startMs)))
                        }
                    }, confirmButton = { TextButton(onClick = { citation = null }) { Text(stringResource(R.string.records_close)) } })
            }
        }
    }
}

@Composable
internal fun SummaryCard(version: RecordSummaryVersionDto, originals: Boolean, onSource: (RecordReferenceDto) -> Unit) {
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

internal fun scopeLabel(scope: RecordScope): Int = when (scope) {
    RecordScope.RECENT -> R.string.records_recent
    RecordScope.OWNED -> R.string.records_owned
    RecordScope.PARTICIPATED -> R.string.records_participated
    RecordScope.SHARED -> R.string.records_shared
}
internal fun sourceLabel(source: String?): Int = when (source) {
    "meeting" -> R.string.records_online
    "audio_recording" -> R.string.records_audio
    "upload" -> R.string.records_uploaded
    else -> R.string.records_all
}
internal fun recordTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
}.getOrDefault("")
internal fun sourceTime(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
