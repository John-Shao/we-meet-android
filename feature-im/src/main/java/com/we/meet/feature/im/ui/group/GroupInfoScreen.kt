package com.we.meet.feature.im.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.ErrorBanner
import com.we.meet.feature.im.vm.GroupInfoEvent
import com.we.meet.feature.im.vm.GroupInfoViewModel
import com.we.meet.feature.im.vm.GroupMemberUi

/** Group management — roster, rename, add/remove, transfer, clear, leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    deps: ImDeps,
    cid: String,
    onBack: () -> Unit,
    onLeftGroup: () -> Unit,
    onAddMembers: (cid: String) -> Unit,
) {
    val vm: GroupInfoViewModel =
        viewModel(key = "group-$cid", factory = remember(deps, cid) { GroupInfoViewModel.Factory(deps, cid) })
    val ui by vm.ui.collectAsStateWithLifecycle()

    var showRename by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var transferTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var showTransferPicker by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                GroupInfoEvent.LeftGroup -> onLeftGroup()
                GroupInfoEvent.HistoryCleared -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.im_group_info)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ui.error?.let { ErrorBanner(it) }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    // Group name row (owner taps to rename).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ui.isOwner) { showRename = true }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.im_group_name_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = ui.name.ifBlank { stringResource(R.string.im_untitled_chat) },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (ui.isOwner) {
                            Text(
                                text = stringResource(R.string.im_group_rename),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.im_group_members_count, ui.members.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        IconButton(onClick = { onAddMembers(cid) }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.im_group_add_members))
                        }
                    }
                }

                items(ui.members, key = { it.uid }) { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        MemberAvatar(
                            name = member.displayName,
                            url = member.avatarUrl,
                            cacheKey = "im-avatar:${member.uid}",
                            size = 36.dp,
                        )
                        Text(
                            text = member.displayName.ifBlank { member.uid.take(8) },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                        if (member.isOwner) {
                            Text(
                                text = stringResource(R.string.im_group_owner_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (ui.isOwner && !member.isSelf) {
                            IconButton(onClick = { removeTarget = member }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.im_group_remove_member),
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    ActionRow(stringResource(R.string.im_group_clear_history)) { confirmClear = true }
                    if (ui.isOwner && ui.members.any { !it.isSelf }) {
                        ActionRow(stringResource(R.string.im_group_transfer_owner)) {
                            showTransferPicker = true
                        }
                    }
                    ActionRow(
                        text = stringResource(
                            if (ui.isOwner) R.string.im_menu_leave_dissolve else R.string.im_menu_leave
                        ),
                        destructive = true,
                    ) { confirmLeave = true }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showRename) {
        var name by remember(ui.name) { mutableStateOf(ui.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.im_group_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.rename(name.trim()); showRename = false },
                    enabled = name.trim().isNotEmpty() && name.trim() != ui.name,
                ) { Text(stringResource(R.string.im_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.im_group_remove_member)) },
            text = { Text(stringResource(R.string.im_group_remove_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = { vm.removeMember(member); removeTarget = null }) {
                    Text(stringResource(R.string.im_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    if (showTransferPicker) {
        AlertDialog(
            onDismissRequest = { showTransferPicker = false },
            title = { Text(stringResource(R.string.im_group_transfer_owner)) },
            text = {
                LazyColumn {
                    items(ui.members.filterNot { it.isSelf }, key = { it.uid }) { member ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    transferTarget = member
                                    showTransferPicker = false
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            MemberAvatar(
                                name = member.displayName,
                                url = member.avatarUrl,
                                cacheKey = "im-avatar:${member.uid}",
                                size = 32.dp,
                            )
                            Text(
                                text = member.displayName.ifBlank { member.uid.take(8) },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTransferPicker = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    transferTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text(stringResource(R.string.im_group_transfer_owner)) },
            text = { Text(stringResource(R.string.im_group_transfer_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = { vm.transferOwner(member); transferTarget = null }) {
                    Text(stringResource(R.string.im_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { transferTarget = null }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.im_group_clear_history)) },
            text = { Text(stringResource(R.string.im_group_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) {
                    Text(stringResource(R.string.im_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = {
                Text(
                    stringResource(
                        if (ui.isOwner) R.string.im_menu_leave_dissolve else R.string.im_confirm_leave_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (ui.isOwner) R.string.im_confirm_leave_owner_message
                        else R.string.im_confirm_leave_message
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.leaveGroup(); confirmLeave = false }) {
                    Text(stringResource(R.string.im_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ActionRow(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
