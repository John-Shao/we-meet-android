package com.we.meet.ui.history

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.history.HistoryEntry
import com.we.meet.ui.home.HistoryTimeFormatter
import com.we.meet.ui.locale.appLocale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import retrofit2.HttpException

/** Meeting information and links to the exact session's native material workspaces. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    roomId: String,
    sessionId: String? = null,
    onOpenRecord: (String) -> Unit = {},
    onOpenSummary: (String) -> Unit = {},
    onBack: () -> Unit,
    /** P8 操作收进详情:进入会议(房间仍可重进)。 */
    onJoinSlug: (slug: String) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val viewModel: MeetingDetailViewModel =
        viewModel(key = roomId, factory = MeetingDetailViewModel.Factory(app))
    val historyEntries by app.historyStore.entries.collectAsStateWithLifecycle()
    val localEntry = remember(historyEntries, roomId) {
        historyEntries.firstOrNull { it.roomId == roomId }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val deleteFailedText = stringResource(R.string.event_delete_failed)

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(roomId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                val latest = viewModel.refreshRoom(roomId)
                if (!latest?.closed_at.isNullOrBlank()) break
                delay(15_000)
            } while (true)
        }
    }
    var joining by remember(roomId) { mutableStateOf(false) }
    val joinFailedText = stringResource(R.string.error_unknown)

    val roomState by viewModel.room.collectAsStateWithLifecycle()
    var sessionRefresh by remember(roomId, sessionId) { mutableStateOf(0) }
    val sessionResult = if (sessionId != null) com.we.meet.ui.records.visibleRead(app.tokenStore.userId, roomId, sessionId, sessionRefresh) {
        app.roomRepository.fetchVideoSession(roomId, sessionId)
    } else null
    val selectedSession = sessionResult?.getOrNull()

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
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.history_delete_confirm_text, deleteName))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (!deleting) {
                        deleting = true
                        scope.launch {
                            val id = room?.slug?.takeIf { it.isNotBlank() } ?: roomId
                            val result = app.roomRepository.deleteRoom(id)
                            val alreadyDeleted = (result.exceptionOrNull() as? HttpException)
                                ?.code() == 404
                            if (result.isSuccess || alreadyDeleted) {
                                confirmDelete = false
                                app.historyStore.remove(id)
                                handleBack()
                            } else {
                                deleting = false
                                Toast.makeText(
                                    app,
                                    deleteFailedText,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }, enabled = !deleting) {
                    if (deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.IconSmall),
                            strokeWidth = Dimens.BorderEmphasis,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            stringResource(R.string.history_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { confirmDelete = false },
                    enabled = !deleting,
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
            )
        },
    ) { padding ->
        // Keep the detail compact: basic metadata and material links only.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
        ) {
            // P8:进入会议(房间仍在,可重进)——操作收进详情页。
            room?.slug?.takeIf { it.isNotBlank() }?.let {
                val isClosed = !room.closed_at.isNullOrBlank() || selectedSession?.status == "ended"
                val sessionJoinable = sessionId == null || selectedSession?.status == "active"
                Button(
                    onClick = {
                        if (!isClosed && !joining) {
                            joining = true
                            scope.launch {
                                try {
                                    val latest = viewModel.refreshRoom(roomId)
                                    val sessionActive = sessionId == null || app.roomRepository.fetchVideoSession(roomId, sessionId).getOrNull()?.status == "active"
                                    if (latest == null) {
                                        Toast.makeText(app, joinFailedText, Toast.LENGTH_SHORT).show()
                                    } else if (latest.closed_at.isNullOrBlank() && sessionActive) {
                                        latest.slug?.takeIf { it.isNotBlank() }?.let(onJoinSlug)
                                    }
                                } finally {
                                    joining = false
                                }
                            }
                        }
                    },
                    enabled = !isClosed && !joining && sessionJoinable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (joining) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.IconSmall),
                            strokeWidth = Dimens.BorderEmphasis,
                        )
                    } else {
                        Text(stringResource(
                            if (isClosed) R.string.error_meeting_ended
                            else R.string.event_join_meeting,
                        ))
                    }
                }
                Spacer(Modifier.height(Dimens.SpaceS))
            }

            SectionHeader(stringResource(R.string.meeting_detail_tab_info))
            if (sessionId != null && sessionResult == null) WeMeetInlineLoading()
            else if (sessionResult?.isFailure == true) WeMeetInlineErrorState(onRetry = { sessionRefresh++ })
            else InfoTab(
                roomState = roomState,
                selectedSession = selectedSession,
                localEntry = if (sessionId == null) localEntry else null,
                onRetry = { viewModel.retryRoom(roomId) },
            )
            SectionSpacer()

            if (com.we.meet.BuildConfig.WE_MEET_RECORDS_NATIVE) {
                MeetingRecordLinks(app.meetingRecordRepository, app.tokenStore.userId.orEmpty(), roomId, sessionId, onOpenRecord, onOpenSummary)
            }
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
    selectedSession: RoomDto?,
    localEntry: HistoryEntry?,
    onRetry: () -> Unit,
) {
    when (roomState) {
        is MeetingDetailViewModel.LoadState.Loading -> WeMeetInlineLoading()
        is MeetingDetailViewModel.LoadState.Failure -> WeMeetInlineErrorState(onRetry = onRetry)
        is MeetingDetailViewModel.LoadState.Success -> {
            val room = roomState.value
            val emptyMark = stringResource(R.string.meeting_detail_info_empty)
            val ongoing = stringResource(R.string.meeting_detail_info_ongoing)

            val memberNames: List<String> = (room.accesses ?: emptyList()).map {
                it.user.full_name?.takeIf { n -> n.isNotBlank() }
                    ?: it.user.short_name?.takeIf { n -> n.isNotBlank() }
                    ?: it.user.email
                    ?: emptyMark
            }
            val participantNames = memberNames

            val timeText = buildTimeText(
                context = LocalContext.current,
                createdAtIso = selectedSession?.started_at ?: room.created_at,
                closedAtIso = selectedSession?.ended_at ?: room.closed_at,
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
