package com.we.meet.feature.im.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.ErrorBanner
import com.we.meet.feature.im.vm.GroupInfoViewModel
import com.we.meet.ui.components.DestructiveConfirmDialog
import com.we.meet.feature.im.vm.GroupMemberUi
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens

/**
 * 群成员 —— 名单从 [GroupInfoScreen] 内联的一段搬成独立页(对标飞书),与
 * 「群机器人 ›」同级。搬家的动机是**名单只该有一处**。
 *
 * 走独立 route 而不是页内 state,理由与群机器人同构:返回键语义 Navigation
 * 免费给。复用 [GroupInfoViewModel] 而不是另起一个 —— 它已经有
 * members / isOwner / removeMember / transferOwner,再写一个只会把
 * rebuildRoster 那套抄第二遍。新的 NavBackStackEntry 自带新的 ViewModelStore,
 * 所以这里拿到的是与群信息页**互相独立的实例**(两者各自 refresh)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembersScreen(
    deps: ImDeps,
    cid: String,
    onBack: () -> Unit,
    onAddMembers: (cid: String) -> Unit,
) {
    val vm: GroupInfoViewModel =
        viewModel(key = "group-$cid", factory = remember(deps, cid) { GroupInfoViewModel.Factory(deps, cid) })
    val ui by vm.ui.collectAsStateWithLifecycle()

    var removeTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var transferTarget by remember { mutableStateOf<GroupMemberUi?>(null) }

    // 群成员搜索。搜的是**名单上显示的那个名字**(群昵称优先、目录名兜底,
    // displayName 已是这个口径)—— 搜不到自己刚看见的名字比没有搜索还费解。
    var memberQuery by remember { mutableStateOf("") }
    val searchable = ui.members.size > MEMBER_SEARCH_THRESHOLD
    val visibleMembers = remember(ui.members, memberQuery, searchable) {
        val q = memberQuery.trim()
        if (q.isBlank() || !searchable) ui.members
        else ui.members.filter { it.displayName.contains(q, ignoreCase = true) }
    }

    // NavHost 只组合栈顶目的地,所以从「添加成员」返回时这里会重新进入组合 ——
    // 名单靠这次重跑刷新,而不是指望一定会来的 conv 事件(同 GroupBotsScreen)。
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.im_group_members_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        when {
            ui.loading && ui.members.isEmpty() -> WeMeetLoading(Modifier.padding(padding))
            ui.error != null && ui.members.isEmpty() -> WeMeetErrorState(
                onRetry = vm::refresh,
                modifier = Modifier.padding(padding),
                message = stringResource(ui.error!!),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ui.error?.let { ErrorBanner(stringResource(it)) }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                    ) {
                        Text(
                            text = stringResource(R.string.im_group_members_count, ui.members.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        IconButton(onClick = { onAddMembers(cid) }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.im_group_add_members))
                        }
                    }
                    // 小群不出搜索框:三个人的名单上顶一个输入框纯属噪音。
                    if (searchable) {
                        OutlinedTextField(
                            value = memberQuery,
                            onValueChange = { memberQuery = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.im_group_search_members)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceXs),
                        )
                    }
                    if (visibleMembers.isEmpty() && ui.members.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.im_group_no_member_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                        )
                    }
                }

                items(visibleMembers, key = { it.uid }) { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceS),
                    ) {
                        MemberAvatar(
                            name = member.displayName,
                            url = member.avatarUrl,
                            cacheKey = "im-avatar:${member.uid}",
                            size = Dimens.AvatarS,
                        )
                        Text(
                            text = member.displayName.ifBlank { member.uid.take(8) },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = Dimens.SpaceM),
                        )
                        // 离职标记与群主徽章各自独立 —— 群主本人也可能已离职,
                        // 挂在 else 分支上正好会漏掉最该提醒的那种情况。中性灰:
                        // 离职是常态事实,不是错误态。
                        if (member.isDeparted) {
                            Text(
                                text = stringResource(R.string.im_departed_chip),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = Dimens.SpaceS),
                            )
                        }
                        if (member.isOwner) {
                            Text(
                                text = stringResource(R.string.im_group_owner_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (ui.isOwner && !member.isSelf) {
                            // 转让群主:每行一个按钮,与 Web 同口径。原来是底部一个
                            // 「转让群主」入口再弹一个选人 Dialog —— 名单单独成页
                            // 之后,那个 Dialog 就是同一屏上的第二份成员列表,而这
                            // 整件事的动机正是「名单只该有一处」。
                            IconButton(onClick = { transferTarget = member }) {
                                Icon(
                                    Icons.Filled.AdminPanelSettings,
                                    contentDescription = stringResource(R.string.im_group_transfer_owner),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimens.IconSmall),
                                )
                            }
                            IconButton(onClick = { removeTarget = member }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.im_group_remove_member),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimens.IconSmall),
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    removeTarget?.let { member ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.im_group_remove_member),
            message = stringResource(R.string.im_group_remove_confirm, member.displayName),
            confirmLabel = stringResource(R.string.im_group_remove_member),
            dismissLabel = stringResource(R.string.im_action_cancel),
            onConfirm = {
                vm.removeMember(member)
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
        )
    }

    transferTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text(stringResource(R.string.im_group_transfer_owner)) },
            text = { Text(stringResource(R.string.im_group_transfer_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = { vm.transferOwner(member); transferTarget = null }) {
                    Text(stringResource(R.string.im_group_transfer_owner))
                }
            },
            dismissButton = {
                TextButton(onClick = { transferTarget = null }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }
}

/** 成员数超过这个值才出搜索框 —— 少于一屏的名单上顶个输入框纯属噪音。与 Web
 *  GroupMembersPage 的 MEMBER_SEARCH_THRESHOLD 取同一个值,免得两端一个有一个没有。 */
private const val MEMBER_SEARCH_THRESHOLD = 10
