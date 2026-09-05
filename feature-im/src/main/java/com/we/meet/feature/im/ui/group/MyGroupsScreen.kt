package com.we.meet.feature.im.ui.group

import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.vm.ConversationListViewModel
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetLoading

/**
 * 「我的群组」——通讯录里的群清单(对标飞书通讯录的同名分组)。
 *
 * 零后端:群清单就是会话列表里 isGroup 的那部分。刻意复用
 * [ConversationListViewModel] 而不是新写一个 —— 群名、九宫格头像的解析口径全在
 * 那边(尤其头像瓦片必须**预解析后挂在行上**,靠回调读目录快照的话 Compose 不会
 * 重组,群头像会一直停在字母兜底)。重写一遍等于把那些坑再踩一次。
 *
 * 点一行回聊天页,不在这里再造一套会话视图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyGroupsScreen(
    deps: ImDeps,
    onBack: () -> Unit,
    onOpenChat: (cid: String) -> Unit,
) {
    val vm: ConversationListViewModel =
        viewModel(factory = remember(deps) { ConversationListViewModel.Factory(deps) })
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.refresh() }

    var query by remember { mutableStateOf("") }
    val groups = rows.filter { it.isGroup }
    val visible = remember(groups, query) {
        val q = query.trim()
        if (q.isBlank()) groups
        else groups.filter { it.title.contains(q, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.im_my_groups_title, groups.size),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.im_my_groups_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            )

            when {
                loading && groups.isEmpty() -> WeMeetLoading(Modifier.weight(1f))
                error != null && groups.isEmpty() -> WeMeetErrorState(
                    onRetry = vm::refresh,
                    modifier = Modifier.weight(1f),
                    message = stringResource(error!!),
                )
                visible.isEmpty() -> WeMeetEmptyState(
                    title = stringResource(
                        if (groups.isEmpty()) R.string.im_my_groups_empty
                        else R.string.im_my_groups_no_match,
                    ),
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    if (error != null) {
                        WeMeetInlineErrorState(
                            onRetry = vm::refresh,
                            message = stringResource(error!!),
                        )
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.cid }) { row ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenChat(row.cid) }
                                    .padding(
                                        horizontal = Dimens.ScreenPadding,
                                        vertical = Dimens.SpaceS,
                                    ),
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GroupAvatar(
                                        tiles = row.memberTiles,
                                        customAvatarUrl = row.avatarUrl,
                                        avatarKey = row.cid,
                                        size = Dimens.AvatarM,
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = Dimens.SpaceM),
                                    ) {
                                        Text(
                                            text = row.title.ifBlank {
                                                stringResource(R.string.im_untitled_chat)
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = pluralStringResource(
                                                R.plurals.im_my_groups_member_count,
                                                row.memberUids.size,
                                                row.memberUids.size,
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
