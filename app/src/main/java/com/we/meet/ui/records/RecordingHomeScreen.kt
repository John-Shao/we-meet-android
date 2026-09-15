@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordSource
import com.we.meet.ui.components.*
import com.we.meet.ui.home.ActionCard
import com.we.meet.ui.home.MeetingListItem
import com.we.meet.ui.home.MeetingListSectionTitle
import com.we.meet.ui.theme.Dimens
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Entering the module only reads history; capture starts in its own destination. */
@Composable
fun RecordingHomeScreen(
    repository: MeetingRecordRepository, viewer: String, historyEnabled: Boolean,
    onOpenNavDrawer: () -> Unit, onCapture: () -> Unit,
    onDetail: (String) -> Unit, onMore: () -> Unit,
) {
    var refresh by remember(viewer) { mutableIntStateOf(0) }
    val result = if (historyEnabled) visibleRead(viewer, refresh) {
        repository.records(viewer, source = RecordSource.AUDIO, isOngoing = false)
            .map { it.results.take(10) }
    } else null
    RecordingHomeContent(result, historyEnabled, onOpenNavDrawer, onCapture, onDetail, onMore, { refresh++ })
}

@Composable
internal fun RecordingHomeContent(
    result: Result<List<RecordDto>>?, historyEnabled: Boolean,
    onOpenNavDrawer: () -> Unit, onCapture: () -> Unit,
    onDetail: (String) -> Unit, onMore: () -> Unit, onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WeMeetTopBar(stringResource(R.string.home_ai_recording), onMenu = onOpenNavDrawer,
            menuDescription = stringResource(R.string.meeting_navigation),
            containerColor = MaterialTheme.colorScheme.background)
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceS, bottom = Dimens.SpaceM)) {
            ActionCard(Icons.Outlined.Mic, stringResource(R.string.records_start_recording),
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                onCapture, Modifier.weight(1f))
            Spacer(Modifier.weight(2f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (historyEnabled) Column(Modifier.weight(1f).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
            MeetingListSectionTitle(stringResource(R.string.recording_history))
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState(onRetry = onRetry, message = stringResource(R.string.records_unavailable))
                else -> {
                    val rows = result.getOrThrow().take(10)
                    if (rows.isEmpty()) Text(stringResource(R.string.recording_history_empty),
                        Modifier.padding(Dimens.ScreenPadding), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rows.forEach { record -> key(record.id) {
                        MeetingListItem(record.title.ifBlank { stringResource(R.string.home_ai_recording) },
                            recordingDate(record.originAt), Icons.Outlined.Mic, { onDetail(record.id) })
                    } }
                }
            }
            TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.video_more)) }
        }
    }
}

internal fun recordingDate(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))
}.getOrDefault("")
