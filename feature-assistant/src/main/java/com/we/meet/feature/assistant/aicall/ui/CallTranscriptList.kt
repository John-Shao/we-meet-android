package com.we.meet.feature.assistant.aicall.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Follow new final sentences only while the reader is at the bottom. */
@Composable
fun CallTranscriptList(rows: List<AssistantHistoryRow>, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followLatest by rememberSaveable { mutableStateOf(true) }
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
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            items(rows.asReversed(), key = { it.id }) { row ->
                val user = row.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(modifier = Modifier.fillMaxWidth(0.85f), shape = MaterialTheme.shapes.medium,
                        border = if (user) null else BorderStroke(Dimens.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                        color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(Dimens.SpaceM)) {
                            Text(stringResource(if (user) R.string.assistant_history_you else R.string.assistant_history_ai),
                                style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(row.text, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
        if (!followLatest && list.canScrollBackward) {
            FilledTonalButton(onClick = {
                followLatest = true
                scope.launch { if (rows.isNotEmpty()) list.scrollToItem(0) }
            }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text(stringResource(R.string.assistant_call_latest))
            }
        }
    }
}
