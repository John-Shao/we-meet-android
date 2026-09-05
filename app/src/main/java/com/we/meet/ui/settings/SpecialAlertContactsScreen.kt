package com.we.meet.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.ContactPrefs
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 「设置 › 通知 › 消息特别提醒」名单页 —— 我给哪些人开了「他的消息特别提醒」。
 *
 * 为什么名单在设置里而不在通讯录:这是**通知**类设置(消息穿透免打扰时段),归属
 * 通知页;星标那份名单才在通讯录,因为星标是归类。逐个人的开关仍在各自的成员详情
 * 页上 —— 两个入口同一份 [ContactPrefs] 状态,和星标完全同构:详情页「就地做决定」,
 * 这一页「回顾谁开过、批量整理」。
 *
 * ⚠️ 会话列表**不给**这些人打标记:会话自己可能开着免打扰,一个「会通知你」的图标
 * 和一个「别通知我」的图标并排会让人不知道哪个生效(实测发现)。要看名单来这里。
 *
 * 结构与 `StarredContactsScreen` 一致(添加走共享 [ContactPicker],行尾直接移除),
 * 只是投影的 flag 不同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialAlertContactsScreen(
    onBack: () -> Unit,
    onMemberClick: (userId: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val alertIds by ContactPrefs.specialAlertIds.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf<List<MemberDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 卡片信息(名字/头像/部门)每次进页面重拉:ContactPrefs 只存 id,存卡片只会
    // 变陈旧。alertIds 变化(本页移除、或详情页开了开关)后一并重拉。
    LaunchedEffect(alertIds, reloadKey) {
        loading = members.isEmpty()
        app.directoryRepository.listSpecialAlert()
            .onSuccess { members = it; error = false }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.special_alert_title),
                onBack = onBack,
                actions = {
                    TextButton(onClick = { picking = true }) {
                        Text(stringResource(R.string.special_alert_add))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> WeMeetLoading()

                error && members.isEmpty() -> WeMeetErrorState(
                    onRetry = { reloadKey += 1 },
                    message = stringResource(R.string.contacts_load_error),
                )

                members.isEmpty() -> WeMeetEmptyState(
                    title = stringResource(R.string.special_alert_empty),
                    description = stringResource(R.string.special_alert_empty_description),
                    icon = Icons.Filled.NotificationsActive,
                    action = {
                        Button(onClick = { picking = true }) {
                            Text(stringResource(R.string.special_alert_add))
                        }
                    },
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(members, key = { it.id }) { member ->
                        AlertRow(
                            member = member,
                            onClick = { onMemberClick(member.id) },
                            onRemove = {
                                ContactPrefs.setSpecialAlert(member.id, false) {
                                    Toast.makeText(
                                        context,
                                        R.string.starred_update_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                // 立刻从本页移除,不等重拉(setSpecialAlert 已乐观改
                                // 集合;失败会回滚,LaunchedEffect 随之把人补回来)。
                                members = members.filterNot { it.id == member.id }
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = Dimens.DividerIndentAvatar),
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Multi,
            excludeUserIds = alertIds,
            onConfirm = { picked ->
                picking = false
                if (picked.isEmpty()) return@ContactPicker
                scope.launch {
                    // 名单短,逐个落库;接口幂等。只发 special_alert,不碰星标。
                    val failed = picked.count { p ->
                        app.directoryRepository
                            .setContactPref(p.userId, specialAlert = true)
                            .isFailure
                    }
                    if (failed > 0) {
                        Toast.makeText(
                            context, R.string.starred_update_failed, Toast.LENGTH_SHORT,
                        ).show()
                    }
                    // 服务端为准刷新共享集合 → 触发本页重拉。
                    ContactPrefs.refresh()
                }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun AlertRow(
    member: MemberDto,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Dimens.ScreenPadding, end = Dimens.SpaceXs)
            .padding(vertical = Dimens.SpaceS),
    ) {
        MemberAvatar(
            name = member.displayName,
            url = member.avatarUrl,
            cacheKey = "avatar:${member.id}",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.SpaceM),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = stringResource(R.string.special_alert_title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = Dimens.SpaceXs)
                        .size(Dimens.IconTiny),
                )
            }
            val subtitle = listOfNotNull(
                member.title?.takeIf { it.isNotBlank() },
                member.department?.name?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.special_alert_remove))
        }
    }
}
