package com.we.meet.feature.im.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jusi.lightim.ConnectionState
import com.jusi.lightim.ConversationSummary
import com.jusi.lightim.Message
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.vm.ImTabViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab-level entry point for the IM feature.
 *
 * Layout (P4 MVP — no separate sub-screens):
 *   ┌─────────────────────────────────────────┐
 *   │  Connection status bar                  │
 *   ├──────────┬──────────────────────────────┤
 *   │ Conv     │  ChatPane                    │
 *   │ list     │  (history + input)           │
 *   │          │                              │
 *   └──────────┴──────────────────────────────┘
 */
@Composable
fun ImTabRoot(deps: ImDeps) {
    val context = LocalContext.current
    val vm: ImTabViewModel = viewModel(
        factory = ImTabViewModel.Factory(context, deps)
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val state by vm.connectionState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusBar(state = state, onRetry = if (state == ConnectionState.AUTH_FAILED) {
            { vm.retry() }
        } else null)

        if (ui.error != null && state != ConnectionState.AUTH_FAILED) {
            ErrorBanner(message = ui.error!!)
        }

        Row(modifier = Modifier.fillMaxSize()) {
            ConversationListPane(
                conversations = ui.conversations,
                activeCid = ui.activeCid,
                onSelect = { vm.selectConversation(it) },
                selfUid = ui.selfUid,
                onCreateDirect = { peerUid -> vm.createDirect(peerUid) },
                modifier = Modifier.width(220.dp),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            ChatPane(
                cid = ui.activeCid,
                messages = ui.activeMessages,
                canSend = state == ConnectionState.CONNECTED && ui.activeCid != null,
                onSend = { body -> ui.activeCid?.let { vm.sendText(it, body) } ?: false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ConnectionStatusBar(state: ConnectionState, onRetry: (() -> Unit)?) {
    val labelRes = when (state) {
        ConnectionState.DISCONNECTED -> R.string.im_status_disconnected
        ConnectionState.CONNECTING -> R.string.im_status_connecting
        ConnectionState.CONNECTED -> R.string.im_status_connected
        ConnectionState.RECONNECTING -> R.string.im_status_reconnecting
        ConnectionState.AUTH_FAILED -> R.string.im_status_auth_failed
    }
    val bgColor = when (state) {
        ConnectionState.CONNECTED -> Color(0xFFE7F5E7)
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFF6E0)
        ConnectionState.AUTH_FAILED -> Color(0xFFFCE4E4)
        ConnectionState.DISCONNECTED -> Color(0xFFEDEDED)
    }
    Surface(color = bgColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodySmall)
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.im_action_retry))
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(color = Color(0xFFFCE4E4), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8B0000),
        )
    }
}

@Composable
private fun ConversationListPane(
    conversations: List<ConversationSummary>,
    activeCid: String?,
    onSelect: (String) -> Unit,
    selfUid: String?,
    onCreateDirect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.im_list_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                onClick = { showNewDialog = true },
                enabled = selfUid != null,
            ) {
                Text("+ " + stringResource(R.string.im_new_direct_button))
            }
        }
        if (conversations.isEmpty()) {
            Text(
                text = stringResource(R.string.im_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(conversations, key = { it.cid }) { conv ->
                    ConversationRow(
                        conv = conv,
                        selected = conv.cid == activeCid,
                        onClick = { onSelect(conv.cid) },
                    )
                    Divider()
                }
            }
        }
    }

    if (showNewDialog && selfUid != null) {
        NewDirectDialog(
            selfUid = selfUid,
            onDismiss = { showNewDialog = false },
            onConfirm = { peerUid ->
                showNewDialog = false
                onCreateDirect(peerUid)
            },
        )
    }
}

@Composable
private fun NewDirectDialog(
    selfUid: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var peerUid by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.im_new_direct_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.im_new_direct_self_uid, selfUid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))
                OutlinedTextField(
                    value = peerUid,
                    onValueChange = { peerUid = it },
                    placeholder = { Text(stringResource(R.string.im_new_direct_peer_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(peerUid) },
                enabled = peerUid.trim().isNotEmpty() && peerUid.trim() != selfUid,
            ) {
                Text(stringResource(R.string.im_new_direct_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.im_new_direct_cancel))
            }
        },
    )
}

@Composable
private fun ConversationRow(conv: ConversationSummary, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .let { mod ->
                // Apply a clickable wrapper via Surface for ripple; manual onClick via Modifier.
                mod
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Use a Surface to get the ripple ergonomics for free.
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = conv.cid.take(8),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (conv.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                )
                if (conv.unreadCount > 0) {
                    UnreadBadge(count = conv.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Long) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ChatPane(
    cid: String?,
    messages: List<Message>,
    canSend: Boolean,
    onSend: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                cid == null -> Text(
                    text = stringResource(R.string.im_chat_pick_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                messages.isEmpty() -> Text(
                    text = stringResource(R.string.im_chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.lastIndex)
                        }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(messages, key = { it.mid }) { m ->
                            MessageRow(m)
                        }
                    }
                }
            }
        }
        MessageInputBar(canSend = canSend, onSend = onSend)
    }
}

@Composable
private fun MessageRow(m: Message) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = m.senderUid.take(8),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(text = m.body, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = remember(m.ts) { TS_FORMAT.format(Date(m.ts)) },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private val TS_FORMAT: SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun MessageInputBar(canSend: Boolean, onSend: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.im_input_placeholder)) },
            enabled = canSend,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Button(
            onClick = {
                if (onSend(text)) text = ""
            },
            enabled = canSend && text.isNotBlank(),
        ) {
            Text(stringResource(R.string.im_input_send))
        }
    }
}

@Composable
private fun Divider() {
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .size(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
