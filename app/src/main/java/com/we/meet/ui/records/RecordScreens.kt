@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import com.we.meet.ui.components.WeMeetInlineEmptyState
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
fun RecordDetailScreen(repository: MeetingRecordRepository, viewer: String, recordId: String, onBack: () -> Unit, summaryVersionId: String? = null, onTask: ((String) -> Unit)? = null, onDocument: ((String) -> Unit)? = null, initialSummary: Boolean = false, initialReview: Boolean = false, onRemoved: () -> Unit = onBack) {
    val app = LocalContext.current.applicationContext as? WeMeetApp
    var audioSeek by remember(viewer, recordId) { mutableStateOf<CaptureAudioSeek?>(null) }
    /**
     * Playback position, lifted here because the player and the transcript are
     * separate regions: the player owns the clock, the transcript owns the text,
     * and this is the one place that connects them. Null until a player reports.
     */
    var playbackPositionMs by remember(viewer, recordId) { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedHuman by remember(viewer, recordId) { mutableStateOf<String?>(null) }
    var selectedVersion by remember(viewer, recordId, summaryVersionId) { mutableStateOf(summaryVersionId) }
    var cursors by remember(viewer, recordId, selectedVersion) { mutableStateOf(listOf<String?>(null)) }
    var citation by remember(viewer, recordId, summaryVersionId) { mutableStateOf<Pair<String, RecordReferenceDto>?>(null) }
    var tool by remember(viewer, recordId, summaryVersionId, initialReview) { mutableStateOf<String?>(if (initialReview) "manage" else null) }
    var history by remember(viewer, recordId, summaryVersionId) { mutableStateOf(false) }
    var detailTab by remember(viewer, recordId, summaryVersionId, initialSummary) { mutableStateOf(if (summaryVersionId != null || initialSummary) "summary" else "text") }
    val initialDocument = summaryVersionId != null || initialSummary || initialReview
    var document by remember(viewer, recordId, summaryVersionId, initialSummary, initialReview) { mutableStateOf(initialDocument) }
    val navigateBack: () -> Unit = {
        if (document != initialDocument) {
            document = initialDocument
            detailTab = if (initialDocument) "summary" else "overview"
            selectedHuman = null; selectedVersion = summaryVersionId
            tool = null; history = false; citation = null; cursors = listOf(null)
        } else onBack()
    }
    BackHandler(enabled = document != initialDocument, onBack = navigateBack)
    val exportTranscript = rememberTranscriptExporter(repository, viewer, recordId)
    val exportTranslation = app?.let { rememberTranslationExporter(it.uploadTranslationRepository, viewer, recordId) }
    val detail = visibleRead(viewer, recordId, refresh) { repository.record(viewer, recordId) }
    val record = detail?.getOrNull()
    val canPlay = !document && app != null && record?.sourceType == "audio_recording" && record.capabilities.readTranscript && record.capabilities.playMedia
    /**
     * An import is replayed from its sealed object, not from a capture playlist,
     * so it needs its own read. Fetched lazily against the record revision: the
     * signed URL expires, and re-reading on a revision bump keeps a stale link
     * from outliving the source it points at.
     */
    var playerDuration by remember(viewer, recordId) { mutableStateOf<Long?>(null) }
    val fullDuration = record?.let { mediaDuration(it, playerDuration) }
    val canPlayImport = !document && record?.sourceType == "upload" && record.capabilities.readTranscript && record.capabilities.playMedia
    val media = visibleRead(viewer, recordId, record?.revision, canPlayImport) {
        if (record == null || !canPlayImport) return@visibleRead Result.failure(IllegalStateException("no media"))
        repository.media(viewer, recordId, record.revision)
    }
    // 二级页的页面层级规范(docs/page-backgrounds.md §1):白色固定头 + 浅灰滚动区。
    // 容器从 `surface` 改成 `background`;标题 / 元信息 / Tab 行那一块自己铺白,
    // 于是「白顶栏 + 白头下固定区 + 浅灰正文」三段成立 —— 此前整页 `surface`,
    // 顶栏、标题、Tab 与正文全是同一个白,浅色下看不出分界、深色下彻底糊成一片。
    Scaffold(topBar = { WeMeetTopBar(stringResource(if (document) R.string.records_minutes else R.string.records_title), onBack = navigateBack,
        actions = { if (!document) record?.let { RecordRenameAction(repository, viewer, it) { refresh++ } } }) },
        containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail == null -> WeMeetInlineLoading()
                detail.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                record == null -> WeMeetEmptyState(stringResource(R.string.records_unavailable))
                else -> {
                    // 固定头(白):标题 + 元信息。Tab 行自己也是一块白面(见下),
                    // 两者之间没有缝,合起来就是那条白色固定头;滚动区留在它下面,铺浅灰。
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                            Text(if (document) stringResource(R.string.record_minutes_document_title, record.title) else record.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${recordTime(record.originAt)} · ${stringResource(recordSourceLabel(record))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (app != null && (if (document) record.capabilities.readSummary else record.capabilities.readTranscript)) {
                        androidx.compose.runtime.key(viewer, recordId, document) {
                            MaterialActions(app, viewer, record, if (document) "minutes" else "record") { refresh++ }
                        }
                    }
                    val canReadTranslations = record.capabilities.readTranscript && app != null && (record.sourceType == "meeting" || record.sourceType == "upload" || record.sourceType == "audio_recording" && record.captureId != null && record.capabilities.controlCapture)
                    val tabs = buildList {
                        if (record.capabilities.readTranscript) add("text" to R.string.records_originals)
                        if (record.capabilities.readTranscript) add("overview" to R.string.record_overview_title)
                        if (record.capabilities.readTranscript) add("chapters" to R.string.records_chapters)
                        if (record.capabilities.readTranscript && record.sourceType in listOf("audio_recording", "upload")) add("speakers" to R.string.records_speakers)
                        add("info" to R.string.records_info)
                        if (canReadTranslations) add("translations" to R.string.archives_title)
                    }
                    val selectedTab = if (document) "summary" else detailTab.takeIf { tab -> tabs.any { it.first == tab } } ?: tabs.first().first
                    val showTranslations = selectedTab == "translations"
                    val showOriginals = selectedTab == "text"
                    val chaptersOnly = selectedTab == "chapters"
                    val overviewOnly = selectedTab == "overview"
                    if (!document && record.sourceType == "upload" && record.upload?.canControl == true && app != null) {
                        RecordingUploadStatus(app.recordingUploadRepository, viewer, recordId)
                    }
                    if (!document) {
                        // 六个标签在 360dp 屏上放不下,所以还是可滚动的一版;但右缘补一层
                        // 渐隐当「右边还有」的提示 —— 全应用只有这一处是 ScrollableTabRow,
                        // 默认的滚动指示几乎看不见,末尾的「译文」就那样被截在边上。
                        // 渐隐是纯装饰、不拦点击,只要标签多到可能溢出就一直留着。
                        // (试过定宽 TabRow:六个标签等分后「Smart minutes」会被截成省略号。)
                        Box(Modifier.fillMaxWidth()) {
                            ScrollableTabRow(
                                selectedTabIndex = tabs.indexOfFirst { it.first == selectedTab },
                                edgePadding = Dimens.SpaceS,
                                containerColor = MaterialTheme.colorScheme.surface,
                            ) {
                                tabs.forEach { (value, label) -> Tab(selected = value == selectedTab, onClick = { detailTab = value }, text = { Text(stringResource(label)) }) }
                            }
                            if (tabs.size >= 5) Box(Modifier.matchParentSize()) {
                                EdgeFade(Alignment.CenterEnd, listOf(Color.Transparent, MaterialTheme.colorScheme.surface))
                            }
                        }
                    }
                    // 白色固定头与浅灰正文的分界(与「通讯录」二级名单页同款)。
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = Dimens.DividerThin,
                    )
                    if (document && record.capabilities.readTranscript) TextButton(onClick = {
                        document = false; detailTab = "overview"; selectedVersion = null; selectedHuman = null
                        cursors = listOf(null); tool = null; history = false; citation = null
                    }) { Text(stringResource(R.string.record_overview_back)) }
                    if (overviewOnly && record.capabilities.readSummary) Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                        Text(stringResource(R.string.record_overview_hint), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { document = true; selectedVersion = null; selectedHuman = null; cursors = listOf(null); tool = null; citation = null; history = false }) {
                            Text(stringResource(R.string.record_overview_open_minutes))
                        }
                    }
                    if (selectedTab == "info") {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            RecordTrashControl(viewer, record, repository, onRemoved)
                            RecordMediaDownload(repository, viewer, record)
                            RecordInfo(record, app?.captureRepository, viewer, fullDuration = fullDuration)
                            if (app != null && onDocument != null) RecordDocuments(viewer, record, app.meetingDeliveryRepository, onDocument, onHumanSource = {
                                document = true; selectedHuman = it; detailTab = "summary"; tool = null; history = false; citation = null
                            }) {
                                document = true; selectedHuman = null; selectedVersion = it; detailTab = "summary"; tool = null; history = false; citation = null
                            }
                        }
                    } else if (selectedTab == "speakers") {
                        RecordSpeakers(repository, viewer, record, Modifier.weight(1f),
                            fullDuration = fullDuration, onSource = if (canPlay || canPlayImport) ({ audioSeek = CaptureAudioSeek(it) }) else null)
                    } else if (showTranslations) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            if (record.sourceType == "upload") UploadTranslationPanel(viewer, recordId, requireNotNull(app).uploadTranslationRepository, requireNotNull(exportTranslation), onSource = if (canPlayImport) ({ audioSeek = CaptureAudioSeek(it) }) else null)
                            else if (record.sourceType == "audio_recording") CaptureTranslationArchives(viewer, requireNotNull(record.captureId), recordId, requireNotNull(app).captureTranslationRepository)
                            else RecordTranslationArchives(viewer, recordId, requireNotNull(app).translationArchiveRepository)
                        }
                    } else if (showOriginals) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            if (app != null && record.sourceType == "audio_recording") RecordCaptureTools(
                                viewer, record, app.captureRepository, app.captureTranscriptionRepository,
                                { app.captureAccount }, onRefresh = { refresh++ })
                            RecordOriginals(repository, viewer, record, onRefresh = { refresh++ }, onExport = exportTranscript, onSource = if (canPlay || canPlayImport) ({ audioSeek = CaptureAudioSeek(it) }) else null, positionMs = playbackPositionMs.takeIf { canPlay || canPlayImport })
                        }
                    } else if (document && !record.capabilities.readSummary) {
                        WeMeetEmptyState(stringResource(R.string.records_no_summary_access))
                    } else if (overviewOnly || chaptersOnly) {
                        if (app == null) WeMeetEmptyState(stringResource(R.string.records_unavailable))
                        else RecordOverview(viewer, record, app.meetingSummaryRepository, { app.captureAccount }, Modifier.weight(1f), chaptersOnly = chaptersOnly) { snapshot, ref -> citation = snapshot to ref }
                    } else if (selectedHuman != null && document && app != null) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                            TextButton(onClick = { selectedHuman = null; selectedVersion = null; citation = null }) { Text(stringResource(R.string.records_all_versions)) }
                            HumanSummaryRevision(viewer, record, app.meetingReviewRepository, selectedHuman!!) { snapshot, reference -> citation = snapshot to reference }
                        }
                    } else {
                        val cursor = if (overviewOnly) null else cursors.last()
                        val versionId = if (overviewOnly) null else selectedVersion
                        val summaries = visibleRead(viewer, recordId, cursor, versionId, refresh) {
                            repository.summaries(viewer, recordId, if (versionId == null) cursor else null, versionId)
                        }
                        if (!overviewOnly && selectedVersion != null) {
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
                                        SummaryCard(primary, record.capabilities.readTranscript, chaptersOnly) { ref -> citation = primary.inputSnapshotId to ref }
                                    }
                                    if (versions.isEmpty()) item {
                                        WeMeetEmptyState(
                                            stringResource(if (overviewOnly) R.string.record_overview_empty else if (selectedVersion == null) R.string.records_no_versions else R.string.records_linked_version_unavailable),
                                            description = if (overviewOnly) null else if (selectedVersion == null) stringResource(if (chaptersOnly) R.string.records_chapters_no_version else R.string.records_no_versions_hint) else null,
                                            action = { TextButton(onClick = { cursors = listOf(null); refresh++ }) { Text(stringResource(R.string.records_refresh)) } },
                                        )
                                    }
                                    if (!overviewOnly && (older.isNotEmpty() || summaries.getOrThrow().nextCursor != null || cursors.size > 1)) item {
                                        TextButton(onClick = { history = !history }, modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)) {
                                            Text(stringResource(R.string.minutes_history), Modifier.weight(1f))
                                            Icon(if (history) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                        }
                                    }
                                    if (history && !overviewOnly) {
                                        items(older, key = { it.id }) { version ->
                                            SummaryCard(version, record.capabilities.readTranscript, chaptersOnly) { ref -> citation = version.inputSnapshotId to ref }
                                        }
                                        item {
                                            RecordPager(
                                                hasPrevious = cursors.size > 1,
                                                onPrevious = { cursors = cursors.dropLast(1) },
                                                // 钉住某个历史版本时不翻页：那一屏读的是指定快照。
                                                hasNext = selectedVersion == null && summaries.getOrThrow().nextCursor != null,
                                                onNext = { summaries.getOrThrow().nextCursor?.let { next -> cursors = cursors + next } },
                                            )
                                        }
                                    }
                                }
                                if (app != null) Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.SpaceBetween) {
                                    if (document && record.capabilities.readTranscript) TextButton(onClick = { tool = "ask" }) { Text(stringResource(R.string.minutes_ask)) }
                                    if (document) TextButton(onClick = { tool = "manage" }) { Text(stringResource(R.string.minutes_manage)) }
                                    // 还没有纪要时空态自己就带一个「刷新」,底栏再放一个就是同一屏两个同名动作;
                                    // 有内容时页面每 15 秒也会自动重读,这里只留一份手动刷新。
                                    if (versions.isNotEmpty()) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                                }
                                if (document && app != null && tool != null) ModalBottomSheet(onDismissRequest = { tool = null }) {
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
                UploadMediaPlayer(read, playbackPositionMs, audioSeek, onDuration = { playerDuration = it }, sourceId = recordId, onSeekConsumed = { audioSeek = null }, onPosition = { playbackPositionMs = it })
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
                        if ((canPlay || canPlayImport) && original?.isSuccess == true) TextButton(onClick = { audioSeek = CaptureAudioSeek(reference.startMs); citation = null }) {
                            Text(stringResource(R.string.capture_playback_source, sourceTime(reference.startMs)))
                        }
                    }, confirmButton = { TextButton(onClick = { citation = null }) { Text(stringResource(R.string.records_close)) } })
            }
        }
    }
}

/**
 * Tab 行两端那层渐隐。
 *
 * 与 [WeMeetChipRow] 同款做法(那里注释写明动机:装不下的标签没有任何提示,
 * 末尾那个正好卡在屏幕右缘被截断)。梯子很窄,只够柔化边界、不遮标签本身;
 * 纯装饰 Box,不处理指针事件,底下的 Tab 该点还是点得到。
 */
@Composable
private fun BoxScope.EdgeFade(alignment: Alignment, colors: List<Color>) {
    Box(
        Modifier.align(alignment)
            .width(Dimens.SpaceXl)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(colors)),
    )
}

@Composable
internal fun SummaryCard(version: RecordSummaryVersionDto, originals: Boolean, chaptersOnly: Boolean = false, onSource: (RecordReferenceDto) -> Unit) {
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
        if (!chaptersOnly) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                Text(stringResource(R.string.minutes_overview), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(version.content.overview, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (chaptersOnly) Text(stringResource(R.string.records_chapters_ai_version), style = MaterialTheme.typography.bodySmall)
        if (version.content.chapters.isEmpty()) WeMeetInlineEmptyState(stringResource(R.string.records_chapters_empty))
        val sections = if (chaptersOnly) listOf(R.string.records_chapters to version.content.chapters) else
            listOf(R.string.records_decisions to version.content.decisions, R.string.records_actions to version.content.actionItems,
                R.string.records_chapters to version.content.chapters, R.string.records_questions to version.content.openQuestions)
        sections.forEach { (label, points) ->
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
