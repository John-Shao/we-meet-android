package com.we.meet.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.data.api.dto.ActionItemDto
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.api.dto.SummaryDto
import com.we.meet.data.api.dto.TranscriptDto
import com.we.meet.data.history.HistoryEntry
import com.we.meet.ui.home.HistoryTimeFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Meeting-detail screen. Mirrors the Web frontend's MeetingDetail page —
 * 4 tabs (Info / Summary / Action items / Transcript) loaded from the
 * same backend endpoints. Kept the file/function name to avoid touching
 * nav (the previous "history detail" was a subset of this).
 *
 * The Info tab augments the server response with the device's local
 * HistoryEntry (join/leave times, observed participants) — that data
 * never reaches the backend, so the Web page can't show it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    roomId: String,
    onBack: () -> Unit,
    /** P8 操作收进详情:进入会议(房间仍可重进)。 */
    onJoinSlug: (slug: String) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val viewModel: MeetingDetailViewModel =
        viewModel(factory = MeetingDetailViewModel.Factory(app))
    val historyEntries by app.historyStore.entries.collectAsStateWithLifecycle()
    val localEntry = remember(historyEntries, roomId) {
        historyEntries.firstOrNull { it.roomId == roomId }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(roomId) { viewModel.load(roomId) }

    val roomState by viewModel.room.collectAsStateWithLifecycle()
    val summaryState by viewModel.summary.collectAsStateWithLifecycle()
    val actionItemsState by viewModel.actionItems.collectAsStateWithLifecycle()
    val transcriptsState by viewModel.transcripts.collectAsStateWithLifecycle()
    val regenerating by viewModel.regenerating.collectAsStateWithLifecycle()

    // Guard against a double-tap on the back arrow popping two entries
    // off the back stack — the second pop empties the stack, which
    // shows a blank screen until the user backgrounds the app.
    var backPressed by remember { mutableStateOf(false) }
    val handleBack: () -> Unit = {
        if (!backPressed) {
            backPressed = true
            onBack()
        }
    }

    val room = (roomState as? MeetingDetailViewModel.LoadState.Success)?.value
    // 房间详情未加载出来前不显示删除(宁可少给,不给出会 403 的入口)。
    val isRoomOwner = room?.is_owner == true
    val deleteName = room?.name?.takeIf { it.isNotBlank() }
        ?: localEntry?.name?.takeIf { it.isNotBlank() }
        ?: room?.slug.orEmpty()

    // P8 操作收进详情(对标飞书/Web):列表长按删除已移除,这里是唯一入口。
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.history_delete_confirm_text, deleteName))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    if (!deleting) {
                        deleting = true
                        scope.launch {
                            val id = room?.slug?.takeIf { it.isNotBlank() } ?: roomId
                            app.roomRepository.deleteRoom(id)
                            app.historyStore.remove(id)
                            handleBack()
                        }
                    }
                }) {
                    Text(
                        stringResource(R.string.history_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { confirmDelete = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.meeting_detail_title),
                onBack = handleBack,
                actions = {
                    // 删除仅房主可见:历史列表含「我只是参会」的会议,参会者
                    // 对别人的会没有删除权(后端 DELETE → is_owner),不收敛
                    // 的话点下去只会吃 403。
                    if (isRoomOwner) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.history_action_delete),
                            )
                        }
                    }
                },
                transparent = true,
            )
        },
    ) { padding ->
        // Flat single-scroll layout — earlier 4-tab design was awkward on a
        // phone; each section is now a labelled block separated by a
        // horizontal divider so users only have to scroll.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
        ) {
            // P8:进入会议(房间仍在,可重进)——操作收进详情页。
            room?.slug?.takeIf { it.isNotBlank() }?.let { slug ->
                Button(
                    onClick = { onJoinSlug(slug) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.event_join_meeting))
                }
                Spacer(Modifier.height(Dimens.SpaceS))
            }

            SectionHeader(stringResource(R.string.meeting_detail_tab_info))
            InfoTab(
                roomState = roomState,
                transcriptsState = transcriptsState,
                localEntry = localEntry,
            )
            SectionSpacer()

            SectionHeader(
                title = stringResource(R.string.meeting_detail_tab_summary),
                trailing = {
                    Button(
                        onClick = { viewModel.regenerateSummary(roomId) },
                        enabled = !regenerating,
                    ) {
                        Text(
                            if (regenerating)
                                stringResource(R.string.meeting_detail_summary_regenerating)
                            else stringResource(R.string.meeting_detail_summary_regenerate)
                        )
                    }
                },
            )
            SummaryTab(state = summaryState)
            SectionSpacer()

            SectionHeader(stringResource(R.string.meeting_detail_tab_action_items))
            ActionItemsTab(state = actionItemsState)
            SectionSpacer()

            // 纪要闭环 M3:智能章节区块(与 Web 三板块对齐,App 只读)。
            SectionHeader(stringResource(R.string.meeting_detail_tab_chapters))
            ChaptersTab(state = summaryState)
            SectionSpacer()

            SectionHeader(stringResource(R.string.meeting_detail_tab_transcript))
            TranscriptTab(state = transcriptsState)
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.SpaceL, bottom = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
    HorizontalDivider(
        thickness = Dimens.BorderThin,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Spacer(Modifier.height(Dimens.SpaceS))
}

@Composable
private fun SectionSpacer() {
    Spacer(Modifier.height(Dimens.SpaceL))
}

// ---------------------------------------------------------------------------
// Info tab
// ---------------------------------------------------------------------------

@Composable
private fun InfoTab(
    roomState: MeetingDetailViewModel.LoadState<RoomDto>,
    transcriptsState: MeetingDetailViewModel.LoadState<List<TranscriptDto>>,
    localEntry: HistoryEntry?,
) {
    when (roomState) {
        is MeetingDetailViewModel.LoadState.Loading -> CenteredText(stringResource(R.string.meeting_detail_loading))
        is MeetingDetailViewModel.LoadState.Failure -> CenteredText(stringResource(R.string.meeting_detail_load_failed))
        is MeetingDetailViewModel.LoadState.Success -> {
            val room = roomState.value
            val emptyMark = stringResource(R.string.meeting_detail_info_empty)
            val ongoing = stringResource(R.string.meeting_detail_info_ongoing)

            // Server-side participant list: transcript speakers when
            // available (catches guests who never got an access row),
            // fall back to `accesses` members.
            val speakerNames: List<String> =
                (transcriptsState as? MeetingDetailViewModel.LoadState.Success)
                    ?.value
                    ?.let { rows ->
                        val seen = linkedSetOf<String>()
                        rows.forEach { row ->
                            val name = row.speaker_name.takeIf { it.isNotBlank() }
                                ?: row.speaker_identity.take(12)
                            if (name.isNotBlank()) seen += name
                        }
                        seen.toList()
                    }
                    ?: emptyList()
            val memberNames: List<String> = (room.accesses ?: emptyList()).map {
                it.user.full_name?.takeIf { n -> n.isNotBlank() }
                    ?: it.user.short_name?.takeIf { n -> n.isNotBlank() }
                    ?: it.user.email
                    ?: emptyMark
            }
            val participantNames = speakerNames.ifEmpty { memberNames }

            val timeText = buildTimeText(
                context = LocalContext.current,
                createdAtIso = room.created_at,
                closedAtIso = room.closed_at,
                ongoingLabel = ongoing,
            )

            InfoRow(
                label = stringResource(R.string.meeting_detail_info_name),
                value = room.name?.takeIf { it.isNotBlank() } ?: emptyMark,
            )
            InfoRow(
                label = stringResource(R.string.meeting_detail_info_time),
                value = timeText,
            )
            InfoRow(
                label = stringResource(R.string.meeting_detail_info_code),
                value = formatSlug(room.slug ?: emptyMark),
            )
            InfoRow(
                label = stringResource(R.string.meeting_detail_info_owner),
                value = room.owner?.takeIf { it.isNotBlank() } ?: emptyMark,
            )
            InfoRowMultiLine(
                label = stringResource(R.string.meeting_detail_info_participants),
                values = participantNames.ifEmpty { listOf(emptyMark) },
            )

            // Device-local timeline — only present if the user has
            // actually joined this room from this device.
            if (localEntry != null && localEntry.firstJoinedAtMs > 0) {
                Spacer(Modifier.height(Dimens.SpaceXl))
                Text(
                    text = stringResource(R.string.meeting_detail_info_timeline),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.SpaceS))
                TimelineRow(
                    time = HistoryTimeFormatter.time(localEntry.firstJoinedAtMs),
                    label = stringResource(R.string.history_detail_joined),
                )
                localEntry.lastLeftAtMs?.let { leftMs ->
                    TimelineRow(
                        time = HistoryTimeFormatter.time(leftMs),
                        label = stringResource(R.string.history_detail_left),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(Dimens.AvatarXl),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = Dimens.DividerThin)
}

@Composable
private fun InfoRowMultiLine(label: String, values: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(Dimens.AvatarXl),
        )
        Column(modifier = Modifier.weight(1f)) {
            values.forEach { v ->
                Text(
                    text = v,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = Dimens.SpaceXxs),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = Dimens.DividerThin)
}

@Composable
private fun TimelineRow(time: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Dimens.SpaceL))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------
// Summary tab
// ---------------------------------------------------------------------------

@Composable
private fun SummaryTab(
    state: MeetingDetailViewModel.LoadState<SummaryDto?>,
) {
    when (state) {
        is MeetingDetailViewModel.LoadState.Loading -> CenteredText(stringResource(R.string.meeting_detail_loading))
        is MeetingDetailViewModel.LoadState.Failure -> CenteredText(stringResource(R.string.meeting_detail_load_failed))
        is MeetingDetailViewModel.LoadState.Success -> {
            val summary = state.value
            // For end users, "no summary generated yet" (404), "summary
            // exists but content is blank", and "backend tried but
            // failed (e.g. no transcripts to summarise)" all read the
            // same: there's no summary to show. Web surfaces the raw
            // error_message; the App keeps it simple — the regenerate
            // button is the action the user can take regardless.
            // 纪要闭环 M2:展示编辑版优先(effective_content),App 端只读。
            val body = summary?.effective_content?.takeIf { it.isNotBlank() }
                ?: summary?.content.orEmpty()
            val showEmpty = summary == null ||
                body.isBlank() ||
                summary.status == "failed"
            if (showEmpty) {
                Text(stringResource(R.string.meeting_detail_summary_empty))
            } else {
                if (summary!!.is_edited) {
                    Text(
                        text = stringResource(R.string.meeting_detail_summary_edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Dimens.SpaceXs),
                    )
                }
                MarkdownText(content = body)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Chapters section (纪要闭环 M3:智能章节原生渲染,只读)
// ---------------------------------------------------------------------------

@Composable
private fun ChaptersTab(
    state: MeetingDetailViewModel.LoadState<SummaryDto?>,
) {
    when (state) {
        is MeetingDetailViewModel.LoadState.Loading -> CenteredText(stringResource(R.string.meeting_detail_loading))
        is MeetingDetailViewModel.LoadState.Failure -> CenteredText(stringResource(R.string.meeting_detail_load_failed))
        is MeetingDetailViewModel.LoadState.Success -> {
            val chapters = state.value?.chapters.orEmpty()
            if (chapters.isEmpty()) {
                Text(stringResource(R.string.meeting_detail_chapters_empty))
                return
            }
            Column {
                chapters.forEach { chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.SpaceXs),
                    ) {
                        Text(
                            text = chapterTimeLabel(chapter.started_at, chapter.ended_at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = Dimens.AvatarXl),
                        )
                        Column(Modifier.padding(start = Dimens.SpaceS)) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (chapter.digest.isNotBlank()) {
                                Text(
                                    text = chapter.digest,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = Dimens.SpaceXxs),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ISO 时刻对 → 「HH:mm – HH:mm」;无合法时间窗时退化为 「—」。 */
private fun chapterTimeLabel(startIso: String?, endIso: String?): String {
    fun fmt(iso: String?): String? = try {
        iso?.let {
            java.time.OffsetDateTime.parse(it)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
    } catch (_: Throwable) {
        null
    }
    val start = fmt(startIso) ?: return "—"
    val end = fmt(endIso)
    return if (end != null) "$start – $end" else start
}

/**
 * Minimal markdown renderer — enough to keep summary output readable
 * without dragging in a markdown library. Recognizes `#`-style headings,
 * `-`/`*` bullet lines, and paragraphs separated by blank lines.
 * Inline emphasis (`**bold**`, `*italic*`) is left as-is — the LLM
 * rarely produces it for meeting summaries.
 */
@Composable
private fun MarkdownText(content: String) {
    Column {
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(Dimens.SpaceS))
                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Dimens.SpaceS, bottom = Dimens.SpaceXs),
                )
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Dimens.SpaceS, bottom = Dimens.SpaceXs),
                )
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = Dimens.SpaceM, bottom = Dimens.SpaceXs),
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val text = line.trimStart().removePrefix("- ").removePrefix("* ")
                    Row(modifier = Modifier.padding(vertical = Dimens.SpaceXxs)) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium)
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = Dimens.SpaceXxs),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Action items tab
// ---------------------------------------------------------------------------

@Composable
private fun ActionItemsTab(state: MeetingDetailViewModel.LoadState<List<ActionItemDto>>) {
    when (state) {
        is MeetingDetailViewModel.LoadState.Loading -> CenteredText(stringResource(R.string.meeting_detail_loading))
        is MeetingDetailViewModel.LoadState.Failure -> CenteredText(stringResource(R.string.meeting_detail_load_failed))
        is MeetingDetailViewModel.LoadState.Success -> {
            val items = state.value
            if (items.isEmpty()) {
                CenteredText(stringResource(R.string.meeting_detail_action_items_empty))
                return
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dimens.CornerS))
                            .background(
                                if (item.is_completed)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
                    ) {
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (item.owner_text.isNotBlank() || item.due_text.isNotBlank()) {
                            Spacer(Modifier.height(Dimens.SpaceXs))
                            Row {
                                if (item.owner_text.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.meeting_detail_action_items_owner) +
                                            ": " + item.owner_text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(Dimens.SpaceM))
                                }
                                if (item.due_text.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.meeting_detail_action_items_due) +
                                            ": " + item.due_text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transcript tab
// ---------------------------------------------------------------------------

@Composable
private fun TranscriptTab(state: MeetingDetailViewModel.LoadState<List<TranscriptDto>>) {
    when (state) {
        is MeetingDetailViewModel.LoadState.Loading -> CenteredText(stringResource(R.string.meeting_detail_loading))
        is MeetingDetailViewModel.LoadState.Failure -> CenteredText(stringResource(R.string.meeting_detail_load_failed))
        is MeetingDetailViewModel.LoadState.Success -> {
            val rows = state.value
            if (rows.isEmpty()) {
                CenteredText(stringResource(R.string.meeting_detail_transcript_empty))
                return
            }
            // System language — used to pick a relevant translation row
            // when the speaker's `language` differs.
            val userLang = Locale.getDefault().language.lowercase()
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                rows.forEach { row ->
                    val ts = formatTime(row.started_at)
                    val speaker = row.speaker_name.takeIf { it.isNotBlank() }
                        ?: row.speaker_identity.take(12)
                    val translationKey = row.translations.keys.firstOrNull {
                        it.lowercase().substringBefore('-') == userLang
                    }
                    val translation = translationKey
                        ?.let { row.translations[it] }
                        ?.takeIf { row.language.lowercase().substringBefore('-') != userLang }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Dimens.SpaceM)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(Dimens.CornerXs),
                            )
                            .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
                    ) {
                        Text(
                            text = "$ts · $speaker",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = row.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        translation?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun CenteredText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceXl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatSlug(slug: String): String {
    val digits = slug.filter { it.isDigit() }
    return when (digits.length) {
        8 -> "${digits.substring(0, 4)} ${digits.substring(4)}"
        9 -> "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
        6 -> "${digits.substring(0, 3)} ${digits.substring(3)}"
        else -> slug
    }
}

private fun parseIsoToMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val normalized = iso
        .replace(Regex("\\.\\d+"), "")
        .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return runCatching { fmt.parse(normalized)?.time }.getOrNull()
}

private fun buildTimeText(
    context: android.content.Context,
    createdAtIso: String?,
    closedAtIso: String?,
    ongoingLabel: String,
): String {
    val startMs = parseIsoToMillis(createdAtIso) ?: return "—"
    val endMs = closedAtIso?.takeIf { it.isNotBlank() }?.let { parseIsoToMillis(it) }
    // 详情页带年份;同天结束只显示时刻(飞书口径:7月18日 19:08 – 19:13)。
    val startStr = HistoryTimeFormatter.fullDateTimeLocalized(context, startMs)
    return when {
        endMs == null -> "$startStr ($ongoingLabel)"
        sameDay(startMs, endMs) -> "$startStr – ${HistoryTimeFormatter.time(endMs)}"
        else -> "$startStr – ${HistoryTimeFormatter.fullDateTimeLocalized(context, endMs)}"
    }
}

private fun sameDay(aMs: Long, bMs: Long): Boolean {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = aMs }
    val b = java.util.Calendar.getInstance().apply { timeInMillis = bMs }
    return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun formatTime(iso: String): String {
    val ms = parseIsoToMillis(iso) ?: return iso
    return HistoryTimeFormatter.time(ms)
}
