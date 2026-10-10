package com.we.meet.feature.assistant.aicall.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Follow new final sentences only while the reader is at the bottom. */
@Composable
fun CallTranscriptList(rows: List<AssistantHistoryRow>, modifier: Modifier = Modifier, timestamps: Map<String, Long> = emptyMap()) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val timeSeparators = remember(rows, timestamps) {
        buildMap<String, Long> {
            var previous: Long? = null
            rows.forEach { row -> timestamps[row.id]?.let { time ->
                if (previous == null || time - previous!! >= 5 * 60_000 || !sameDay(time, previous!!)) put(row.id, time)
                previous = time
            } }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollBackward }
            .distinctUntilChanged().collect { (scrolling, canScroll) ->
                if (scrolling || !canScroll) followLatest = !canScroll
            }
    }
    LaunchedEffect(rows) {
        if (followLatest && rows.isNotEmpty()) list.scrollToItem(0)
    }
    Box(modifier.testTag("call-transcript")) {
        if (rows.isEmpty()) {
            Text(stringResource(R.string.assistant_call_transcript_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(Dimens.ScreenPadding))
        }
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL)) {
            items(rows.asReversed(), key = { it.id }) { row ->
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL)) {
                    timeSeparators[row.id]?.let { CallTranscriptTimestamp(it) }
                    CallTranscriptBubble(row)
                }
            }
        }
        if (!followLatest && list.canScrollBackward) {
            Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.SpaceL).size(Dimens.MinTouchTarget),
                shape = CircleShape, shadowElevation = Dimens.ElevationRaised, onClick = {
                    followLatest = true
                    scope.launch { if (rows.isNotEmpty()) list.scrollToItem(0) }
                }) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowDownward, stringResource(R.string.assistant_call_latest), Modifier.size(Dimens.IconLarge))
                }
            }
        }
    }
}

/** Short sentences hug their content; long sentences stop before the opposite edge. */
@Composable
fun CallTranscriptBubble(row: AssistantHistoryRow) {
    val user = row.role == "user"
    val colors = WeMeetTheme.extras.aiCall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Surface(modifier = Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart)
            .widthIn(max = maxWidth * 0.88f), shape = RoundedCornerShape(Dimens.CornerXl),
            color = if (user) colors.transcriptUser else colors.transcriptAi, contentColor = colors.onTranscript) {
            SelectionContainer {
                Text(row.text, modifier = Modifier.padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceM),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun CallTranscriptTimestamp(timestamp: Long) {
    val context = LocalContext.current
    val date = Date(timestamp)
    val time = android.text.format.DateFormat.getTimeFormat(context).format(date)
    val label = if (sameDay(timestamp, System.currentTimeMillis())) time
        else "${android.text.format.DateFormat.getDateFormat(context).format(date)} $time"
    Text(label, modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS).testTag("call-transcript-time"),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

private fun sameDay(first: Long, second: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
