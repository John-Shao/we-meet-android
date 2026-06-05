package com.we.meet.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.we.meet.R
import kotlinx.coroutines.launch

/**
 * In-room AI assistant sheet — POSTs `ask-ai-stream` and renders the
 * streamed answer as a chat bubble that grows token by token. Mirrors
 * Web's `RoomAIPanel`. Single-turn from the user's POV (one input
 * field, no slash commands); multi-turn under the hood (the last 6
 * messages travel as `history` on the next ask, see
 * [RoomViewModel.askAi]).
 *
 * History stays in the ViewModel for the meeting's lifetime — the
 * "新对话" button clears it, and disconnect tears down the VM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomAiSheet(
    messages: List<RoomAiMessage>,
    asking: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Auto-scroll the transcript to the bottom on new content. Re-runs
    // on message-count change AND on text-length change of the last
    // message (so streaming deltas keep the view pinned to the cursor).
    val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastTextLen) {
        scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.room_ai_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (messages.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.room_ai_clear))
                    }
                }
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 420.dp)
                    .verticalScroll(scrollState)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.room_ai_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    messages.forEach { msg -> AiBubble(msg) }
                    if (asking && messages.lastOrNull()?.role == "user") {
                        // Belt-and-braces: streaming hasn't sent its first
                        // delta yet, show a thinking placeholder.
                        AiBubble(
                            RoomAiMessage(
                                id = "thinking",
                                role = "assistant",
                                text = stringResource(R.string.room_ai_thinking),
                                done = false,
                            )
                        )
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.room_ai_input_hint)) },
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val q = draft.trim()
                        if (q.isNotEmpty() && !asking) {
                            onSend(q)
                            draft = ""
                        }
                    },
                    enabled = draft.trim().isNotEmpty() && !asking,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.room_ai_send),
                        tint = if (draft.trim().isNotEmpty() && !asking)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiBubble(msg: RoomAiMessage) {
    val isUser = msg.role == "user"
    val bg = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val align = if (isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = align,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val displayed = msg.text.ifBlank {
                if (!msg.done && !isUser) "…" else ""
            }
            Column {
                Text(
                    text = displayed + if (!msg.done && !isUser && msg.text.isNotEmpty()) " ▍" else "",
                    color = fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (msg.error != null) {
                    Text(
                        text = msg.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Suppress("unused") // silences "snapshot state hint" lint suggestion
private typealias _SnapshotStateListHint = SnapshotStateList<Int>
