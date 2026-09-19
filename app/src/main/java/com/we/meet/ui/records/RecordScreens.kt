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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import com.we.meet.data.api.dto.RecordDto
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
fun RecordDetailScreen(repository: MeetingRecordRepository, viewer: String, recordId: String, onBack: () -> Unit, summaryVersionId: String? = null, onTask: ((String) -> Unit)? = null, onDocument: ((String) -> Unit)? = null, initialSummary: Boolean = false, initialReview: Boolean = false) {
    val app = LocalContext.current.applicationContext as? WeMeetApp
    var audioSeek by remember(viewer, recordId) { mutableStateOf<CaptureAudioSeek?>(null) }
    /**
     * Playback position, lifted here because the player and the transcript are
     * separate regions: the player owns the clock, the transcript owns the text,
     * and this is the one place that connects them. Null until a player reports.
     */
    var playbackPositionMs by remember(viewer, recordId) { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedVersion by remember(viewer, recordId, summaryVersionId) { mutableStateOf(summaryVersionId) }
    var cursors by remember(viewer, recordId, selectedVersion) { mutableStateOf(listOf<String?>(null)) }
    var citation by remember(viewer, recordId, summaryVersionId) { mutableStateOf<Pair<String, RecordReferenceDto>?>(null) }
    var tool by remember(viewer, recordId, summaryVersionId, initialReview) { mutableStateOf<String?>(if (initialReview) "manage" else null) }
    var history by remember(viewer, recordId, summaryVersionId) { mutableStateOf(false) }
    var detailTab by remember(viewer, recordId, summaryVersionId, initialSummary) { mutableStateOf(if (summaryVersionId != null || initialSummary) "summary" else "text") }
    val detail = visibleRead(viewer, recordId, refresh) { repository.record(viewer, recordId) }
    val record = detail?.getOrNull()
    val canPlay = app != null && record?.sourceType == "audio_recording" && record.capabilities.readTranscript && record.retentionMode == "media" && !record.isOngoing
    /**
     * An import is replayed from its sealed object, not from a capture playlist,
     * so it needs its own read. Fetched lazily against the record revision: the
     * signed URL expires, and re-reading on a revision bump keeps a stale link
     * from outliving the source it points at.
     */
    val canPlayImport = record?.sourceType == "upload" && record.capabilities.readTranscript && record.retentionMode == "media" && !record.isOngoing
    val media = visibleRead(viewer, recordId, record?.revision, canPlayImport) {
        if (record == null || !canPlayImport) return@visibleRead Result.failure(IllegalStateException("no media"))
        repository.media(viewer, recordId, record.revision)
    }
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
                        Text("${recordTime(record.originAt)} · ${stringResource(recordSourceLabel(record))}",
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
                        RecordInfo(record, app?.captureRepository, viewer, Modifier.weight(1f))
                    } else if (selectedTab == "speakers") {
                        RecordSpeakers(repository, viewer, record, Modifier.weight(1f))
                    } else if (showTranslations) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            if (record.sourceType == "audio_recording") CaptureTranslationArchives(viewer, requireNotNull(record.captureId), recordId, requireNotNull(app).captureTranslationRepository)
                            else RecordTranslationArchives(viewer, recordId, requireNotNull(app).translationArchiveRepository)
                        }
                    } else if (showOriginals) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            if (app != null && record.sourceType == "audio_recording") RecordCaptureTools(
                                viewer, record, app.captureRepository, app.captureTranscriptionRepository,
                                { app.captureAccount }, onRefresh = { refresh++ })
                            RecordOriginals(repository, viewer, record, onRefresh = { refresh++ }, onSource = if (canPlay || canPlayImport) ({ audioSeek = CaptureAudioSeek(it) }) else null, positionMs = playbackPositionMs.takeIf { canPlay || canPlayImport })
                        }
                    } else if (!record.capabilities.readSummary) {
                        WeMeetEmptyState(stringResource(R.string.records_no_summary_access))
                    } else {
                        val cursor = cursors.last()
                        val summaries = visibleRead(viewer, recordId, cursor, selectedVersion, refresh) {
                            repository.summaries(viewer, recordId, if (selectedVersion == null) cursor else null, selectedVersion)
                        }
                        if (selectedVersion != null) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.ScreenPadding)) {
                                Text(stringResource(R.string.records_linked_version), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { selectedVersion = null }) { Text(stringResource(R.string.records_all_versions)) }
                            }
                        }
                        when {
                            summaries == null -> WeMeetInlineLoading()
                            summaries.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                            else -> {
                                val versions = summaries.getOrThrow().results
                                val primary = versions.firstOrNull { it.isCurrent } ?: versions.firstOrNull()
                                val older = versions.filter { it.id != primary?.id }
                                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                                    if (primary != null) item(key = primary.id) {
                                        SummaryCard(primary, record.capabilities.readTranscript) { ref -> citation = primary.inputSnapshotId to ref }
                                    }
                                    if (versions.isEmpty()) item {
                                        WeMeetEmptyState(
                                            stringResource(if (selectedVersion == null) R.string.records_no_versions else R.string.records_linked_version_unavailable),
                                            description = if (selectedVersion == null) stringResource(R.string.records_no_versions_hint) else null,
                                            action = { TextButton(onClick = { cursors = listOf(null); refresh++ }) { Text(stringResource(R.string.records_refresh)) } },
                                        )
                                    }
                                    if (older.isNotEmpty() || summaries.getOrThrow().nextCursor != null || cursors.size > 1) item {
                                        TextButton(onClick = { history = !history }, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)) {
                                            Text(stringResource(R.string.minutes_history), Modifier.weight(1f))
                                            Icon(if (history) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                        }
                                    }
                                    if (history) {
                                        items(older, key = { it.id }) { version ->
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
                                if (app != null) Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.SpaceBetween) {
                                    if (record.capabilities.readTranscript) TextButton(onClick = { tool = "ask" }) { Text(stringResource(R.string.minutes_ask)) }
                                    TextButton(onClick = { tool = "manage" }) { Text(stringResource(R.string.minutes_manage)) }
                                    // 还没有纪要时空态自己就带一个「刷新」,底栏再放一个就是同一屏两个同名动作;
                                    // 有内容时页面每 15 秒也会自动重读,这里只留一份手动刷新。
                                    if (versions.isNotEmpty()) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                                }
                                if (app != null && tool != null) ModalBottomSheet(onDismissRequest = { tool = null }) {
                                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                                        Text(stringResource(if (tool == "ask") R.string.minutes_ask else R.string.minutes_manage), style = MaterialTheme.typography.titleLarge)
                                        if (tool == "ask" && record.capabilities.readTranscript) {
                                            RecordQuestions(viewer, record, versions, app.meetingQuestionRepository,
                                                { app.captureAccount }) { snapshot, reference -> citation = snapshot to reference }
                                        } else {
                                            if (selectedVersion == null) {
                                                RecordSummaryControls(viewer, record, app.meetingSummaryRepository) { app.captureAccount }
                                                RecordHumanSummary(viewer, record, primary, app.meetingReviewRepository,
                                                    { app.captureAccount }, onTask) { snapshot, reference -> citation = snapshot to reference }
                                            }
                                            RecordNotifications(viewer, record, app.meetingDeliveryRepository, { app.captureAccount }) { selectedVersion = it; tool = null }
                                            RecordSharing(viewer, record, app.meetingSharingRepository) { app.captureAccount }
                                            if (onDocument != null) RecordExports(viewer, record, versions, app.meetingDeliveryRepository,
                                                app.meetingReviewRepository, { app.captureAccount }, onDocument)
                                        }
                                        TextButton(onClick = { tool = null }) { Text(stringResource(R.string.minutes_close_tools)) }
                                    }
                                }
                            }
                        }
                        if (app == null || summaries?.isSuccess != true) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                    }
                }
            }
            if (canPlay) NativeCaptureAudioPlayer(viewer, recordId, requireNotNull(app).capturePlaybackRepository, { app.captureAccount }, audioSeek, onSeekConsumed = { audioSeek = null }, onPosition = { playbackPositionMs = it })
            // The import's signed read is fetched lazily; until it arrives there is
            // no player, and a refused read leaves the transcript readable alone.
            if (canPlayImport) media?.getOrNull()?.let { read ->
                UploadMediaPlayer(read, playbackPositionMs, audioSeek, onSeekConsumed = { audioSeek = null }, onPosition = { playbackPositionMs = it })
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
    var sourceInfo by remember(version.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL)) {
        // 版本头一行与 Web 的 RecordSummaryPanel 同构:`阶段 · 生成于 … · 历史版本`。
        // 原先只写「生成于 …」,阶段被藏在下面的「生成信息」折叠区 —— 折叠着就看不出这份
        // 是实时稿还是终稿,而「速记稿后续可能变」正是最该先看到的信息。
        Text(listOfNotNull(
            stringResource(versionStageLabel(version.stage)),
            stringResource(R.string.minutes_generated_at, recordTime(version.createdAt)),
            stringResource(R.string.records_historical).takeIf { !version.isCurrent },
        ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (version.stage != "final") Text(stringResource(R.string.records_provisional), style = MaterialTheme.typography.bodySmall)
        if (version.asrStatus == "incomplete") Text(stringResource(R.string.records_incomplete), style = MaterialTheme.typography.bodySmall)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                Text(stringResource(R.string.minutes_overview), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(version.content.overview, style = MaterialTheme.typography.bodyLarge)
            }
        }
        listOf(R.string.records_decisions to version.content.decisions, R.string.records_actions to version.content.actionItems,
            R.string.records_chapters to version.content.chapters, R.string.records_questions to version.content.openQuestions).forEach { (label, points) ->
            if (points.isNotEmpty()) {
                var expanded by remember(version.id, label) { mutableStateOf(true) }
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                        Text("${stringResource(label)} · ${points.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                    if (expanded) points.forEach { point ->
                        Column(Modifier.padding(start = Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                            Text(point.text, style = MaterialTheme.typography.bodyLarge)
                            listOfNotNull(point.ownerText, point.dueText).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (originals) point.sourceRefs.forEach { ref ->
                                TextButton(onClick = { onSource(ref) }) { Text(stringResource(R.string.records_source_at, sourceTime(ref.startMs))) }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { sourceInfo = !sourceInfo }) {
            Text(stringResource(R.string.minutes_source_info))
            Icon(if (sourceInfo) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (sourceInfo) {
            // 阶段不在这里重复:它已经在版本头那一行(与 Web 的 details 里只放
            // 覆盖信息、不放阶段是同一条规则)。
            // 与 Web 的「生成信息」逐条对齐(RecordSummaryPanel 的 Version):
            // 已观察到的时间点 → 文字送达 · 覆盖度 → 识别状态。少一条就等于对这份
            // 纪要的覆盖范围没有交代 —— 用户没法判断结论是不是只覆盖了半场。
            version.sourceThroughMs?.let {
                Text(stringResource(R.string.records_observed_through, sourceTime(it)), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${stringResource(deliveryLabel(version.deliveryStatus))} · ${stringResource(R.string.records_coverage_unverified)}",
                style = MaterialTheme.typography.bodySmall,
            )
            // unverified / incomplete 不在这里出:incomplete 已在上方单独提示,而
            // unverified 表示服务端就没给识别状态,再报一句等于什么都没说(与 Web 同一条规则)。
            if (version.asrStatus !in setOf("unverified", "incomplete")) Text(stringResource(when (version.asrStatus) {
                "in_progress" -> R.string.records_recognizing
                "finished" -> R.string.records_finished
                else -> R.string.records_audio_not_observed
            }), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 纪要版本阶段:realtime / quick / final。 */
private fun versionStageLabel(stage: String): Int = when (stage) {
    "realtime" -> R.string.records_live
    "quick" -> R.string.records_quick
    "final" -> R.string.records_final
    else -> R.string.records_minutes
}

/** 文字送达状态(delivery_status):Web 的 recordAi.delivery.* 四档。 */
private fun deliveryLabel(status: String): Int = when (status) {
    "open" -> R.string.records_delivery_open
    "complete" -> R.string.records_delivery_complete
    "incomplete" -> R.string.records_delivery_incomplete
    else -> R.string.records_delivery_unverified
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
    "recordings" -> R.string.record_import_all
    else -> R.string.records_all
}

/**
 * 行内展示用的来源标签:导入件再按媒体类型细分成「导入音频 / 导入视频」。
 *
 * 两条理由:①「AI 录音」页对同一条记录写的已经是「导入视频」,记录库退回笼统的
 * 「上传音视频」,同一条记录就有两个说法;②两个库的导入件共用一个通用图标(与 Web
 * 一致),音视频之分只能靠这行字。
 *
 * 筛选器那种拿不到媒体类型的场合仍用 [sourceLabel] 的通用标签。
 */
internal fun recordSourceLabel(record: RecordDto): Int = when {
    record.sourceType != "upload" -> sourceLabel(record.sourceType)
    record.upload?.mediaType == "video" -> R.string.record_import_video
    else -> R.string.record_import_audio
}

/**
 * 会议模块内唯一的时间格式。录制历史、记录库、智能纪要、详情页全部用它 —— 同一个
 * 会议在这几个子区里必须显示成同一串时间，不能一处 `yyyy/M/d HH:mm`、一处本地化
 * 短格式（英文环境下会变成 `9/18/26, 1:30 AM`）。
 */
internal fun recordTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))
}.getOrDefault("")
internal fun sourceTime(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
