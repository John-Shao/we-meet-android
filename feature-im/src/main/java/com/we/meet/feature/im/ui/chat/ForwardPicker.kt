package com.we.meet.feature.im.ui.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.userMessageRes
import com.we.meet.feature.im.vm.ChatViewModel.ForwardTarget
import kotlinx.coroutines.launch

/**
 * Feishu-style forward picker: a full-screen chooser over the user's other
 * conversations, with search, avatars, a single/multi-select toggle, and an
 * entry to create a new group and forward into it.
 *
 * [onForward] receives one or more target cids (one in single mode, the checked
 * set in multi mode); the caller does the actual re-send. [onCreateGroupForward]
 * hands control to the create-group flow, keeping the pending forward payload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPicker(
    targets: List<ForwardTarget>,
    onForward: (List<String>) -> Unit,
    onCreateGroupForward: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var multi by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        var confirmTarget by remember { mutableStateOf<ForwardTarget?>(null) }

        val visible = remember(targets, query) {
            val q = query.trim()
            if (q.isBlank()) targets
            else targets.filter { it.title.contains(q, ignoreCase = true) }
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar: close · title · multi-select toggle.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.im_action_cancel))
                    }
                    Text(
                        text = stringResource(R.string.im_forward_to),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(onClick = {
                        multi = !multi
                        if (!multi) selected = emptySet()
                    }) {
                        Text(
                            text = stringResource(R.string.im_forward_multi),
                            color = if (multi) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Search.
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.im_search)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // Create-group-and-forward entry (hidden in multi mode: the two
                // are alternative destinations, and Feishu shows it only up top).
                if (!multi) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCreateGroupForward)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.im_forward_new_group),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Text(
                    text = stringResource(R.string.im_forward_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(visible.size) { i ->
                        val t = visible[i]
                        ForwardRow(
                            target = t,
                            multi = multi,
                            checked = t.cid in selected,
                            onClick = {
                                if (multi) {
                                    selected = if (t.cid in selected) selected - t.cid
                                    else selected + t.cid
                                } else {
                                    confirmTarget = t
                                }
                            },
                        )
                    }
                }

                // Multi-select send bar.
                if (multi) {
                    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = { onForward(selected.toList()) },
                                enabled = selected.isNotEmpty(),
                            ) {
                                Text(
                                    if (selected.isEmpty()) stringResource(R.string.im_forward_send)
                                    else "${stringResource(R.string.im_forward_send)} (${selected.size})",
                                )
                            }
                        }
                    }
                }
            }
        }

        confirmTarget?.let { t ->
            val name = t.title.ifBlank { stringResource(R.string.im_untitled_chat) }
            AlertDialog(
                onDismissRequest = { confirmTarget = null },
                // 短确认句无需 M3 默认 headlineSmall(24sp)那么大,压到 titleMedium。
                title = {
                    Text(
                        stringResource(R.string.im_forward_confirm, name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmTarget = null
                        onForward(listOf(t.cid))
                    }) { Text(stringResource(R.string.im_forward_send)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmTarget = null }) {
                        Text(stringResource(R.string.im_action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ForwardRow(
    target: ForwardTarget,
    multi: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) {
            // Checkbox is a visual indicator only — the whole row drives selection.
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
        }
        if (target.isGroup) {
            GroupAvatar(tiles = target.memberTiles, size = 44.dp)
        } else {
            MemberAvatar(
                name = target.title,
                url = target.avatarUrl,
                cacheKey = "im-avatar:${target.avatarKey}",
                size = 44.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = target.title.ifBlank { stringResource(R.string.im_untitled_chat) },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * "创建群组并转发": pick members over the org directory, create a group named
 * after the first few members (Feishu default), then hand the new cid back so
 * the caller forwards the pending message(s) into it.
 */
@Composable
fun ForwardCreateGroupFlow(
    deps: ImDeps,
    onCreated: (cid: String) -> Unit,
    onCancel: () -> Unit,
) {
    val session = remember(deps) { ImSession.get(deps) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    ContactPicker(
        deps = deps,
        mode = ContactPickerMode.Multi,
        onConfirm = { picked ->
            if (picked.isEmpty() || creating) {
                if (picked.isEmpty()) onCancel()
                return@ContactPicker
            }
            creating = true
            val name = picked.take(3).joinToString("、") { it.displayName }.take(40)
            scope.launch {
                runCatching { session.bridge.createGroup(picked.map { it.userId }, name) }
                    .onSuccess { res ->
                        session.conversations.refresh()
                        onCreated(res.cid)
                    }
                    .onFailure { e ->
                        creating = false
                        Toast.makeText(
                            context,
                            "${context.getString(R.string.im_create_chat_failed)}: " +
                                context.getString(e.userMessageRes()),
                            Toast.LENGTH_SHORT,
                        ).show()
                        onCancel()
                    }
            }
        },
        onDismiss = onCancel,
    )
}
