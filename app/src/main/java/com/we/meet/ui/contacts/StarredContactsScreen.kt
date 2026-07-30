package com.we.meet.ui.contacts

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.data.StarredContacts
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 星标联系人列表页(对标飞书通讯录 › 星标联系人)。
 *
 * 「添加」复用共享的 [ContactPicker](Multi 模式,已星标的人排掉),行尾可直接
 * 取消星标。名单本身跟着 [StarredContacts] 走 —— 在别处(成员详情)改了星标,
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

    val starredIds by StarredContacts.ids.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf<List<MemberDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    // 卡片信息(名字/头像/部门)每次进页面重拉:StarredContacts 只存 id,存卡片
    // 只会变陈旧。starredIds 变化(本页取消、或详情页打了星标)后一并重拉。
    LaunchedEffect(starredIds) {
        loading = members.isEmpty()
        app.directoryRepository.listStarred()
            .onSuccess { members = it; error = false }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.starred_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
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
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                error && members.isEmpty() -> Text(
                    text = stringResource(R.string.contacts_load_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )

                members.isEmpty() -> Text(
                    text = stringResource(R.string.starred_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(members, key = { it.id }) { member ->
                        StarredRow(
                            member = member,
                            onClick = { onMemberClick(member.id) },
                            onUnstar = {
                                StarredContacts.setStarred(member.id, false) {
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
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 72.dp),
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
            excludeUserIds = starredIds,
            onConfirm = { picked ->
                picking = false
                if (picked.isEmpty()) return@ContactPicker
                scope.launch {
                    // 名单短,逐个落库;星标接口幂等,重复提交不会脏数据。
                    val failed = picked.count { p ->
                        app.directoryRepository.setStarred(p.userId, true).isFailure
                    }
                    if (failed > 0) {
                        Toast.makeText(
                            context, R.string.starred_update_failed, Toast.LENGTH_SHORT,
                        ).show()
                    }
                    // 服务端为准刷新共享集合 → 触发本页重拉 + 会话列表 ⭐ 同步。
                    StarredContacts.refresh()
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Dimens.ScreenPadding, end = 4.dp)
            .padding(vertical = 10.dp),
    ) {
        MemberAvatar(
            name = member.displayName,
            url = member.avatarUrl,
            cacheKey = "avatar:${member.id}",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 名字后一颗实心星(对标飞书星标列表)。
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.starred_title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(14.dp),
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
        Spacer(Modifier.size(4.dp))
        TextButton(onClick = onUnstar) {
            Text(stringResource(R.string.starred_remove))
        }
    }
}
