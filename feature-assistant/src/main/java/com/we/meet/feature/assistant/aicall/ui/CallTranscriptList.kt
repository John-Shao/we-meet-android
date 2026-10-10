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
import android.icu.text.BreakIterator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Follow streaming text only while the reader is at the bottom. */
@Composable
fun CallTranscriptList(rows: List<AssistantHistoryRow>, modifier: Modifier = Modifier, timestamps: Map<String, Long> = emptyMap()) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var typingRevision by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(rows, typingRevision) {
        if (followLatest && !list.isScrollInProgress && rows.isNotEmpty()) list.scrollToItem(0)
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
                    CallTranscriptBubble(row, animateText = true, onTyping = { typingRevision++ })
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
fun CallTranscriptBubble(row: AssistantHistoryRow, animateText: Boolean = false, onTyping: () -> Unit = {}) {
    val user = row.role == "user"
    val colors = WeMeetTheme.extras.aiCall
    val text = if (animateText && !user && row.photo == null) typewriterText(row, onTyping) else row.text
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (row.photo != null) {
            CallTranscriptPhoto(row.photo, row.id, Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart).width(maxWidth * 0.66f))
        } else Surface(modifier = Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart)
            .widthIn(max = maxWidth * 0.88f), shape = RoundedCornerShape(Dimens.CornerXl),
            color = if (user) colors.transcriptUser else colors.transcriptAi, contentColor = colors.onTranscript) {
            SelectionContainer {
                Text(text, modifier = Modifier.testTag("call-text-${row.id}").padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceM),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Save the revealed position so rotation and scrolling do not replay an answer. */
@Composable
private fun typewriterText(row: AssistantHistoryRow, onTyping: () -> Unit): String {
    var revealed by rememberSaveable(row.id) { mutableIntStateOf(if (row.isStreaming) 0 else row.text.length) }
    val notify by rememberUpdatedState(onTyping)
    val boundaries = remember(row.text) { BreakIterator.getCharacterInstance().apply { setText(row.text) } }
    val safeEnd = revealed.coerceIn(0, row.text.length).let { end ->
        if (boundaries.isBoundary(end)) end else boundaries.preceding(end).coerceAtLeast(0)
    }
    LaunchedEffect(row.id, row.text) {
        revealed = safeEnd
        var lastFrame = 0L
        while (revealed < row.text.length) {
            val frame = withFrameNanos { it }
            if (lastFrame != 0L && frame - lastFrame < 25_000_000L) continue
            lastFrame = frame
            // Catch up with large provider chunks instead of queuing minutes of animation.
            val step = maxOf(1, (row.text.length - revealed) / 20)
            var next = revealed
            repeat(step) {
                if (next < row.text.length) next = boundaries.following(next).let {
                    if (it == BreakIterator.DONE) row.text.length else it
                }
            }
            revealed = next
            notify()
        }
    }
    // An incomplete UTF-16 pair from a transport chunk must never reach Text.
    val end = if (safeEnd > 0 && row.text[safeEnd - 1].isHighSurrogate()) safeEnd - 1 else safeEnd
    return row.text.take(end)
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
