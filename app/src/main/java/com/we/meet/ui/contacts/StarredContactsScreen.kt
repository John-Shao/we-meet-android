package com.we.meet.ui.contacts

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.data.ContactPrefs
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.MemberRow
import com.we.meet.core.directory.ui.MemberRowDivider
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 星标联系人列表页(对标飞书通讯录 › 星标联系人)。
 *
 * 「添加」复用共享的 [ContactPicker](Multi 模式,已星标的人排掉),行尾可直接
 * 取消星标。名单本身跟着 [ContactPrefs] 走 —— 在别处(成员详情)改了星标,
 * 回到这页不会看到过期状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredContactsScreen(
    onBack: () -> Unit,
    onMemberClick: (userId: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val starredIds by ContactPrefs.starredIds.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf<List<MemberDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 卡片信息(名字/头像/部门)每次进页面重拉:ContactPrefs 只存 id,存卡片
    // 只会变陈旧。starredIds 变化(本页取消、或详情页打了星标)后一并重拉。
    LaunchedEffect(starredIds, reloadKey) {
        loading = members.isEmpty()
        app.directoryRepository.listStarred()
            .onSuccess { members = it; error = false }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.starred_title),
                onBack = onBack,
                actions = {
                    TextButton(onClick = { picking = true }) {
                        Text(stringResource(R.string.starred_add))
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
                    title = stringResource(R.string.starred_empty),
                    icon = Icons.Filled.Star,
                    action = {
                        Button(onClick = { picking = true }) {
                            Text(stringResource(R.string.starred_add))
                        }
                    },
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(members, key = { it.id }) { member ->
                        StarredRow(
                            member = member,
                            onClick = { onMemberClick(member.id) },
                            onUnstar = {
                                ContactPrefs.setStarred(member.id, false) {
                                    Toast.makeText(
                                        context,
                                        R.string.starred_update_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                // 立刻从本页移除,不等重拉(setStarred 已乐观改集合;
                                // 失败会回滚,LaunchedEffect 随之把人补回来)。
                                members = members.filterNot { it.id == member.id }
                            },
                        )
                        MemberRowDivider()
                    }
                }
            }
        }
    }

    if (picking) {
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Multi,
            excludeUserIds = starredIds,
            onConfirm = { picked ->
                picking = false
                if (picked.isEmpty()) return@ContactPicker
                scope.launch {
                    // 名单短,逐个落库;接口幂等,重复提交不会脏数据。只发
                    // is_starred,不碰对方的「特别提醒」。
                    val failed = picked.count { p ->
                        app.directoryRepository
                            .setContactPref(p.userId, isStarred = true)
                            .isFailure
                    }
                    if (failed > 0) {
                        Toast.makeText(
                            context, R.string.starred_update_failed, Toast.LENGTH_SHORT,
                        ).show()
                    }
                    // 服务端为准刷新共享集合 → 触发本页重拉 + 会话列表 ⭐ 同步。
                    ContactPrefs.refresh()
                }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun StarredRow(
    member: MemberDto,
    onClick: () -> Unit,
    onUnstar: () -> Unit,
) {
    // 行本体走共享的 MemberRow(几何/字体/分隔线在 DirectoryRows.kt 一处定):
    // 这一页只多说两件事 —— 名字后那颗星,和行尾的「移除」。
    MemberRow(
        member = member,
        onClick = onClick,
        badge = {
            // 名字后一颗实心星(对标飞书星标列表)。
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.starred_title),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconTiny),
            )
        },
        trailing = {
            TextButton(onClick = onUnstar) {
                Text(stringResource(R.string.starred_remove))
            }
        },
    )
}
