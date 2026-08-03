package com.we.meet.feature.im.ui.chat

import com.we.meet.ui.theme.Dimens
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.net.DirectoryNetwork
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
 * 搜索同时覆盖**已有会话**和**通讯录**(对标飞书):还没聊过的同事也能直接转发,
 * 确认时先 create-or-get 出单聊会话再发。目标解析放在这里而不是甩给调用方,是
 * 为了让 [onForward] 只收 cid 的契约保持不变 —— 四个调用方(消息转发/日程/会议/
 * 云文档)一行不用改就都拿到了通讯录搜索。
 *
 * [onForward] receives one or more target cids (one in single mode, the checked
 * set in multi mode); the caller does the actual re-send. [onCreateGroupForward]
 * hands control to the create-group flow, keeping the pending forward payload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPicker(
    deps: ImDeps,
    targets: List<ForwardTarget>,
    onForward: (List<String>) -> Unit,
    onCreateGroupForward: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val session = remember(deps) { ImSession.get(deps) }
        val repo = remember(deps) { DirectoryRepository(DirectoryNetwork.directoryApi(deps)) }

        var multi by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        // 通讯录里勾选的人:userId → 展示名。存名字是为了搜索词变了、行已经不在
        // 列表里时,底部计数和后续解析仍然拿得到。
        var selectedUsers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var confirmTarget by remember { mutableStateOf<ForwardTarget?>(null) }
        var confirmMember by remember { mutableStateOf<MemberDto?>(null) }
        var resolving by remember { mutableStateOf(false) }
        var hits by remember { mutableStateOf<List<MemberDto>>(emptyList()) }

        val visible = remember(targets, query) {
            val q = query.trim()
            if (q.isBlank()) targets
            else targets.filter { it.title.contains(q, ignoreCase = true) }
        }

        // 通讯录候选:仅在搜索时拉(不搜索时列表就是「最近对话」,塞进整本通讯录
        // 只会干扰);已经有单聊会话的人不重复出现 —— 选上面那行会话即可。
        // 250ms 去抖,与 Web 端 useDirectoryMemberSearch 对齐。
        LaunchedEffect(query, targets) {
            val q = query.trim()
            if (q.isBlank()) {
                hits = emptyList()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(250)
            val known = targets.mapNotNull { it.peerUserId }.toSet()
            repo.searchMembers(q)
                .onSuccess { page ->
                    hits = page.members.filter { !it.isSelf && it.id !in known }
                }
                .onFailure { hits = emptyList() }
        }

        /** 通讯录选中的人还没有会话 —— 先 create-or-get(幂等),全部成功才发。 */
        fun forwardResolving(cids: List<String>, userIds: Collection<String>) {
            if (resolving) return
            if (userIds.isEmpty()) {
                onForward(cids)
                return
            }
            resolving = true
            scope.launch {
                runCatching {
                    userIds.map { session.bridge.createDirectByUserId(it).cid }
                }
                    .onSuccess { onForward(cids + it) }
                    .onFailure {
                        resolving = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.im_forward_resolve_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
            }
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar: close · title · multi-select toggle.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXs),
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
                        if (!multi) {
                            selected = emptySet()
                            selectedUsers = emptyMap()
                        }
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
                    placeholder = { Text(stringResource(R.string.im_forward_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
                )

                // Create-group-and-forward entry (hidden in multi mode: the two
                // are alternative destinations, and Feishu shows it only up top).
                if (!multi) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCreateGroupForward)
                            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
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
                    modifier = Modifier.padding(start = Dimens.ScreenPadding, top = Dimens.SpaceM, bottom = Dimens.SpaceXs),
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
                    if (hits.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.im_forward_section_directory),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = Dimens.ScreenPadding,
                                    top = Dimens.SpaceM,
                                    bottom = Dimens.SpaceXs,
                                ),
                            )
                        }
                    }
                    items(hits.size) { i ->
                        val m = hits[i]
                        MemberForwardRow(
                            member = m,
                            multi = multi,
                            checked = m.id in selectedUsers,
                            onClick = {
                                if (multi) {
                                    selectedUsers = if (m.id in selectedUsers) {
                                        selectedUsers - m.id
                                    } else {
                                        selectedUsers + (m.id to m.displayName)
                                    }
                                } else {
                                    confirmMember = m
                                }
                            },
                        )
                    }
                }

                // Multi-select send bar.
                if (multi) {
                    val total = selected.size + selectedUsers.size
                    Surface(tonalElevation = Dimens.ElevationSubtle, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = {
                                    forwardResolving(selected.toList(), selectedUsers.keys)
                                },
                                enabled = total > 0 && !resolving,
                            ) {
                                Text(
                                    if (total == 0) stringResource(R.string.im_forward_send)
                                    else "${stringResource(R.string.im_forward_send)} ($total)",
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

        confirmMember?.let { m ->
            val name = m.displayName.ifBlank { stringResource(R.string.im_untitled_chat) }
            AlertDialog(
                onDismissRequest = { confirmMember = null },
                title = {
                    Text(
                        stringResource(R.string.im_forward_confirm, name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !resolving,
                        onClick = {
                            confirmMember = null
                            forwardResolving(emptyList(), listOf(m.id))
                        },
                    ) { Text(stringResource(R.string.im_forward_send)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmMember = null }) {
                        Text(stringResource(R.string.im_action_cancel))
                    }
                },
            )
        }
    }
}

/** 通讯录命中行 —— 与 [ForwardRow] 同款布局,多一行 职位 · 部门 副标题。 */
@Composable
private fun MemberForwardRow(
    member: MemberDto,
    multi: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val label = member.displayName.ifBlank { member.email.orEmpty() }
    val sub = listOfNotNull(
        member.title?.takeIf { it.isNotBlank() },
        member.department?.name?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(Dimens.SpaceS))
        }
        MemberAvatar(
            name = label,
            url = member.avatarUrl,
            cacheKey = "directory-avatar:${member.id}",
            size = Dimens.ListLeadingIcon,
        )
        Spacer(Modifier.width(Dimens.SpaceM))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) {
            // Checkbox is a visual indicator only — the whole row drives selection.
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(Dimens.SpaceS))
        }
        if (target.isGroup) {
            GroupAvatar(tiles = target.memberTiles, size = Dimens.ListLeadingIcon)
        } else {
            MemberAvatar(
                name = target.title,
                url = target.avatarUrl,
                cacheKey = "im-avatar:${target.avatarKey}",
                size = Dimens.ListLeadingIcon,
            )
        }
        Spacer(Modifier.width(Dimens.SpaceM))
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
