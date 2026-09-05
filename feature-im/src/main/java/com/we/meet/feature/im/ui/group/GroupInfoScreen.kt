package com.we.meet.feature.im.ui.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.we.meet.feature.im.ui.common.ImActionRow
import com.we.meet.feature.im.ui.common.ImNavRow
import com.we.meet.feature.im.ui.common.ImSwitchRow
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.ui.common.ErrorBanner
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.vm.GroupInfoEvent
import com.we.meet.feature.im.vm.GroupInfoViewModel
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.DestructiveConfirmDialog
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading

/** Group management — roster, rename, add/remove, transfer, clear, leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    deps: ImDeps,
    cid: String,
    onBack: () -> Unit,
    onLeftGroup: () -> Unit,
    /** P8 应用「日程」：携带已解析出 we-meet id 的成员（未解析静默过滤，
     * 忙闲页会对 freebusy 缺席列另行置灰)。null 隐藏宫格。 */
    onOpenGroupCalendar: ((memberUserIds: List<String>) -> Unit)? = null,
    /** 群机器人二级页(对标飞书)。null 隐藏入口。 */
    onOpenBots: ((cid: String) -> Unit)? = null,
    /** 群成员二级页(对标飞书)。null 隐藏入口。 */
    onOpenMembers: ((cid: String) -> Unit)? = null,
) {
    val vm: GroupInfoViewModel =
        viewModel(key = "group-$cid", factory = remember(deps, cid) { GroupInfoViewModel.Factory(deps, cid) })
    val ui by vm.ui.collectAsStateWithLifecycle()

    var showRename by remember { mutableStateOf(false) }
    var showAnnounce by remember { mutableStateOf(false) }
    var showNickname by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmAvatarRemove by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::uploadAvatar) }

    // 成员页有自己的 GroupInfoViewModel 实例(新 NavBackStackEntry = 新
    // ViewModelStore),所以在那边踢人之后,这一页只能靠自己重新拉一次才知道 ——
    // 指望 conversationEvents 一定会来是赌运气。同 GroupBotsScreen 的理由。
    LaunchedEffect(Unit) { vm.refresh() }

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
            WeMeetTopBar(
                title = stringResource(R.string.im_settings_title),
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
                    // Custom group avatar. Owners can replace/remove it; all
                    // members see the generated mosaic when no custom image exists.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ui.isOwner && !ui.busy) {
                                avatarPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            }
                            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.ScreenPadding),
                    ) {
                        GroupAvatar(
                            tiles = ui.members.take(9).map { member ->
                                GroupTile(member.uid, member.displayName, member.avatarUrl)
                            },
                            customAvatarUrl = ui.avatarUrl,
                            avatarKey = cid,
                            size = Dimens.AvatarM,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = Dimens.SpaceM),
                        ) {
                            Text(
                                text = stringResource(R.string.im_group_avatar_label),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            if (ui.isOwner) {
                                Text(
                                    text = stringResource(R.string.im_group_avatar_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (ui.busy) {
                            CircularProgressIndicator(modifier = Modifier.size(Dimens.IconMedium))
                        } else if (ui.isOwner && ui.avatarUrl != null) {
                            TextButton(onClick = { confirmAvatarRemove = true }) {
                                Text(stringResource(R.string.im_group_avatar_remove))
                            }
                        } else if (ui.isOwner) {
                            Text(
                                text = stringResource(R.string.im_group_avatar_change),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                    // Group name row (owner taps to rename).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ui.isOwner) { showRename = true }
                            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.ScreenPadding),
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
                    // Group announcement (description) row — owner taps to edit.
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ui.isOwner) { showAnnounce = true }
                            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.ScreenPadding),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.im_group_announce_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = ui.description.ifBlank {
                                    stringResource(R.string.im_group_announce_empty)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (ui.description.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (ui.isOwner) {
                            Text(
                                text = stringResource(R.string.im_group_announce_edit),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = Dimens.SpaceM),
                            )
                        }
                    }
                    HorizontalDivider()
                    // My per-group nickname row — any member may edit their own.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showNickname = true }
                            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.ScreenPadding),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.im_group_my_nickname_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = ui.myNickname.ifBlank {
                                    stringResource(R.string.im_group_my_nickname_empty)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (ui.myNickname.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = stringResource(R.string.im_group_announce_edit),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                    // P8 应用宫格（首期仅「日程」）。
                    if (onOpenGroupCalendar != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                        ) {
                            Text(
                                text = stringResource(R.string.im_group_apps),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(modifier = Modifier.padding(top = Dimens.SpaceS)) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Dimens.CornerS))
                                        .clickable {
                                            onOpenGroupCalendar(
                                                ui.members.mapNotNull { it.userId },
                                            )
                                        }
                                        .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(Dimens.ListLeadingIcon)
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                RoundedCornerShape(Dimens.CornerS),
                                            ),
                                    ) {
                                        Icon(
                                            Icons.Filled.CalendarMonth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.im_group_calendar),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    // 群成员:放在机器人**上面** —— 人优先于工具。
                    if (onOpenMembers != null) {
                        ImNavRow(
                            label = stringResource(R.string.im_group_members_title),
                            value = ui.members.size.toString(),
                            onClick = { onOpenMembers(cid) },
                        )
                        HorizontalDivider()
                    }
                    // 群机器人:单独一行而不是塞进上面的宫格 —— 宫格里的「群成员
                    // 日历」是打开一个只读视图,机器人是管理入口(增删改配置、看
                    // 密钥),飞书自己也是独立一行;而且这一行以后要挂计数和配置
                    // 异常红点,宫格格子里放不下。
                    if (onOpenBots != null) {
                        ImNavRow(
                            label = stringResource(R.string.im_bots_entry),
                            value = ui.botCount?.toString(),
                            onClick = { onOpenBots(cid) },
                        )
                        HorizontalDivider()
                    }
                    // P10: private per-conversation toggles (pin / mute / mute @all).
                    ImSwitchRow(
                        label = stringResource(R.string.im_menu_pin),
                        checked = ui.pinned,
                        enabled = !ui.busy,
                        onToggle = { vm.togglePin() },
                    )
                    ImSwitchRow(
                        label = stringResource(R.string.im_menu_mute),
                        checked = ui.muted,
                        enabled = !ui.busy,
                        onToggle = { vm.toggleMute() },
                    )
                    ImSwitchRow(
                        label = stringResource(R.string.im_menu_mute_at_all),
                        checked = ui.muteAtAll,
                        enabled = !ui.busy,
                        onToggle = { vm.toggleMuteAtAll() },
                    )
                    HorizontalDivider()
                }

                item {
                    // 分组靠这段空白断开就够了 —— 上一组末行已经有收尾线(与本屏
                    // 其它行同口径),这里再补一条起始线就成了 12dp 里夹两条线。
                    Spacer(Modifier.height(Dimens.SpaceL))
                    ImActionRow(stringResource(R.string.im_group_clear_history)) { confirmClear = true }
                    // 「转让群主」不再在这里 —— 它现在是成员页每一行上的按钮
                    // (与 Web 同口径)。原来那条入口会弹一个选人 Dialog,那就是
                    // 同一屏上的第二份成员名单。
                    ImActionRow(
                        text = stringResource(R.string.im_menu_leave),
                        destructive = true,
                    ) { confirmLeave = true }
                    Spacer(Modifier.height(Dimens.SpaceXl))
                }
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

    if (showAnnounce) {
        var desc by remember(ui.description) { mutableStateOf(ui.description) }
        AlertDialog(
            onDismissRequest = { showAnnounce = false },
            title = { Text(stringResource(R.string.im_group_announce_label)) },
            text = {
                OutlinedTextField(
                    value = desc,
                    onValueChange = { if (it.length <= 200) desc = it },
                    placeholder = { Text(stringResource(R.string.im_group_announce_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.setDescription(desc.trim()); showAnnounce = false },
                    enabled = desc.trim() != ui.description,
                ) { Text(stringResource(R.string.im_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showAnnounce = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    if (showNickname) {
        var nick by remember(ui.myNickname) { mutableStateOf(ui.myNickname) }
        AlertDialog(
            onDismissRequest = { showNickname = false },
            title = { Text(stringResource(R.string.im_group_my_nickname_label)) },
            text = {
                OutlinedTextField(
                    value = nick,
                    onValueChange = { if (it.length <= 40) nick = it },
                    placeholder = { Text(stringResource(R.string.im_group_my_nickname_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.setMyNickname(nick.trim()); showNickname = false },
                    enabled = nick.trim() != ui.myNickname,
                ) { Text(stringResource(R.string.im_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showNickname = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    if (confirmClear) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.im_group_clear_history),
            message = stringResource(R.string.im_group_clear_confirm),
            confirmLabel = stringResource(R.string.im_group_clear_history),
            dismissLabel = stringResource(R.string.im_action_cancel),
            onConfirm = {
                vm.clearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (confirmAvatarRemove) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.im_group_avatar_remove),
            message = stringResource(R.string.im_group_avatar_remove_confirm),
            confirmLabel = stringResource(R.string.im_group_avatar_remove),
            dismissLabel = stringResource(R.string.im_action_cancel),
            onConfirm = {
                vm.removeAvatar()
                confirmAvatarRemove = false
            },
            onDismiss = { confirmAvatarRemove = false },
        )
    }

    if (confirmLeave) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.im_confirm_leave_title),
            message = stringResource(
                if (ui.isOwner) R.string.im_confirm_leave_owner_message
                else R.string.im_confirm_leave_message,
            ),
            confirmLabel = stringResource(R.string.im_menu_leave),
            dismissLabel = stringResource(R.string.im_action_cancel),
            onConfirm = {
                vm.leaveGroup()
                confirmLeave = false
            },
            onDismiss = { confirmLeave = false },
        )
    }
}
