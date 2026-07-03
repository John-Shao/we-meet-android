package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.feature.im.R

/** Quick-reaction emoji set — mirrors the web context-menu bar. */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * Long-press message menu: a quick-reaction emoji row on top, then actions
 * (copy / reply / recall). Recall shows only for the caller's own recent
 * messages ([canRecall]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    canRecall: Boolean,
    myReactions: Set<String>,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onMultiSelect: () -> Unit,
    onRecall: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            QUICK_REACTIONS.forEach { emoji ->
                val active = emoji in myReactions
                Text(
                    text = emoji,
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable { onReact(emoji); onDismiss() }
                        .padding(8.dp)
                        .then(
                            if (active) Modifier.background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                androidx.compose.foundation.shape.CircleShape,
                            ) else Modifier
                        ),
                )
            }
        }
        HorizontalDivider()
        ActionRow(Icons.Filled.ContentCopy, stringResource(R.string.im_action_copy)) {
            onCopy(); onDismiss()
        }
        ActionRow(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.im_action_reply)) {
            onReply(); onDismiss()
        }
        ActionRow(Icons.AutoMirrored.Filled.Send, stringResource(R.string.im_action_forward)) {
            onForward(); onDismiss()
        }
        ActionRow(Icons.Filled.Checklist, stringResource(R.string.im_action_multi_select)) {
            onMultiSelect(); onDismiss()
        }
        if (canRecall) {
            ActionRow(Icons.Filled.Undo, stringResource(R.string.im_action_recall)) {
                onRecall(); onDismiss()
            }
        }
        Column(Modifier.padding(bottom = 16.dp)) {}
    }
}

/** Pick a conversation to forward into. */
@Composable
fun ForwardTargetDialog(
    targets: List<com.we.meet.feature.im.vm.ChatViewModel.ForwardTarget>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.im_reply_cancel))
            }
        },
        title = { Text(stringResource(R.string.im_forward_to)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(targets.size) { i ->
                    val target = targets[i]
                    Text(
                        text = target.title.ifBlank { stringResource(R.string.im_untitled_chat) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(target.cid) }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
