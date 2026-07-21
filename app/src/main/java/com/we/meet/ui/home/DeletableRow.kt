package com.we.meet.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.we.meet.R

/**
 * Wraps a meeting-list row so a long-press opens a context menu holding the
 * destructive "delete" action, while a plain tap still runs [onClick].
 *
 * Replaces the earlier swipe-to-reveal drawer: a hidden left-swipe isn't
 * discoverable, and long-press is the platform convention for row-scoped
 * actions. The menu anchors to the row, so the caller passes its normal
 * content and does NOT attach its own `clickable` — this wrapper owns both
 * the tap and the long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeletableRow(
    onClick: () -> Unit,
    onDelete: () -> Unit,
    /** Row title, shown in the delete-confirm prompt. */
    itemName: String,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val deleteLabel = stringResource(R.string.history_action_delete)

    // deleteMeeting 是服务端删房(发起人删对所有人生效),不可撤销 → 二次确认。
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_text, itemName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        onDelete()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.history_delete_confirm_ok),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onLongClickLabel = deleteLabel,
                ),
        ) {
            content()
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(text = deleteLabel, color = MaterialTheme.colorScheme.error)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    confirmOpen = true
                },
            )
        }
    }
}
