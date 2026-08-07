package com.we.meet.feature.im.ui.chat

import com.we.meet.ui.theme.WeMeetTextStyles
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.we.meet.feature.im.R

/** Quick-reaction emoji set — mirrors the web context-menu bar. */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * Long-press message menu: a quick-reaction emoji row on top, then actions
 * (copy / reply / forward / multi-select / pin / recall / delete). Recall shows
 * only for the caller's own recent messages ([canRecall]); delete is 仅本端 so
 * it shows for every message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    canRecall: Boolean,
    myReactions: Set<String>,
    /** P17: whether the message is currently pinned (drives the label). */
    isPinned: Boolean,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onMultiSelect: () -> Unit,
    onRecall: () -> Unit,
    onTogglePin: () -> Unit,
    /** 仅本端删除(不限发送者),与多选删除同一条路径,只是一次一条。 */
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            QUICK_REACTIONS.forEach { emoji ->
                val active = emoji in myReactions
                Text(
                    text = emoji,
                    style = WeMeetTextStyles.EmojiPicker,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable { onReact(emoji); onDismiss() }
                        .padding(Dimens.SpaceS)
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
        // P17 会话共享置顶:按当前状态切换文案;权限由服务端裁决。
        ActionRow(
            Icons.Filled.PushPin,
            stringResource(if (isPinned) R.string.im_action_unpin else R.string.im_action_pin),
        ) {
            onTogglePin(); onDismiss()
        }
        if (canRecall) {
            ActionRow(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.im_action_recall)) {
                onRecall(); onDismiss()
            }
        }
        ActionRow(
            Icons.Filled.DeleteOutline,
            stringResource(R.string.im_action_delete),
            danger = true,
        ) {
            onDelete(); onDismiss()
        }
        Column(Modifier.padding(bottom = Dimens.SpaceL)) {}
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    /** Destructive rows (删除) render in the error colour, like the web menu. */
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint =
        if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            modifier = Modifier.padding(start = Dimens.ScreenPadding),
        )
    }
}
