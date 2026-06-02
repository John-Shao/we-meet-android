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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.data.api.dto.ActionItemDto
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.api.dto.SummaryDto
import com.we.meet.data.api.dto.TranscriptDto
import com.we.meet.data.history.HistoryEntry
import com.we.meet.ui.home.HistoryTimeFormatter
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
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val viewModel: MeetingDetailViewModel =
        viewModel(factory = MeetingDetailViewModel.Factory(app))
    val historyEntries by app.historyStore.entries.collectAsStateWithLifecycle()
    val localEntry = remember(historyEntries, roomId) {
        historyEntries.firstOrNull { it.roomId == roomId }
    }

    LaunchedEffect(roomId) { viewModel.load(roomId) }

    val roomState by viewModel.room.collectAsStateWithLifecycle()
    val summaryState by viewModel.summary.collectAsStateWithLifecycle()
    val actionItemsState by viewModel.actionItems.collectAsStateWithLifecycle()
    val transcriptsState by viewModel.transcripts.collectAsStateWithLifecycle()
    val regenerating by viewModel.regenerating.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meeting_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
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

            SectionHeader(stringResource(R.string.meeting_detail_tab_transcript))
            TranscriptTab(state = transcriptsState)
            Spacer(Modifier.height(24.dp))
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
            .padding(top = 16.dp, bottom = 8.dp),
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
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionSpacer() {
    Spacer(Modifier.height(16.dp))
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
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.meeting_detail_info_timeline),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
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
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

@Composable
private fun InfoRowMultiLine(label: String, values: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            values.forEach { v ->
                Text(
                    text = v,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

@Composable
private fun TimelineRow(time: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
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
            when {
                summary == null -> Text(stringResource(R.string.meeting_detail_summary_empty))
                summary.status == "failed" -> Text(
                    buildString {
                        append(stringResource(R.string.meeting_detail_summary_failed))
                        if (summary.error_message.isNotBlank()) {
                            append(": ")
                            append(summary.error_message)
                        }
                    }
                )
                summary.content.isBlank() ->
                    Text(stringResource(R.string.meeting_detail_summary_empty))
                else -> MarkdownText(content = summary.content)
            }
        }
    }
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
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val text = line.trimStart().removePrefix("- ").removePrefix("* ")
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium)
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (item.is_completed)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (item.owner_text.isNotBlank() || item.due_text.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Row {
                                if (item.owner_text.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.meeting_detail_action_items_owner) +
                                            ": " + item.owner_text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(12.dp))
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            .padding(start = 12.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
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
            .padding(vertical = 24.dp),
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
    createdAtIso: String?,
    closedAtIso: String?,
    ongoingLabel: String,
): String {
    val startMs = parseIsoToMillis(createdAtIso) ?: return "—"
    val endMs = closedAtIso?.takeIf { it.isNotBlank() }?.let { parseIsoToMillis(it) }
    val startStr = HistoryTimeFormatter.monthDayTime(startMs)
    return if (endMs == null) {
        "$startStr ($ongoingLabel)"
    } else {
        val endStr = HistoryTimeFormatter.monthDayTime(endMs)
        "$startStr – $endStr"
    }
}

private fun formatTime(iso: String): String {
    val ms = parseIsoToMillis(iso) ?: return iso
    return HistoryTimeFormatter.time(ms)
}
