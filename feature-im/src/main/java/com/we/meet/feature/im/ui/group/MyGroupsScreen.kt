package com.we.meet.feature.im.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.core.directory.ui.ContactListRow
import com.we.meet.core.directory.ui.MemberRowDivider
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.vm.ConversationListViewModel
import com.we.meet.feature.im.vm.ConversationRowUi
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetSearchField
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.highlightMatches
import com.we.meet.ui.theme.Dimens

/**
 * 「我的群组」——通讯录里的群清单(对标飞书通讯录的同名分组)。
 *
 * 零后端:群清单就是会话列表里 isGroup 的那部分。刻意复用
 * [ConversationListViewModel] 而不是新写一个 —— 群名、九宫格头像的解析口径全在
 * 那边(尤其头像瓦片必须**预解析后挂在行上**,靠回调读目录快照的话 Compose 不会
 * 重组,群头像会一直停在字母兜底)。重写一遍等于把那些坑再踩一次。
 *
 * 点一行回聊天页,不在这里再造一套会话视图。
 *
 * 这一页的形状照着通讯录的「内部联系人」那一页(`OrgContactsScreen`,在 app 模块,
 * 这里不做链接)走 —— 同一套「白色固定头部 + 浅灰滚动区 + 白色条目」的层级,
 * 同一套行内几何(头像 40dp、行内距 16dp、副标题 bodySmall、分隔线从文字左缘起):
 *
 * - 头部是**白色固定区**,搜索框坐在上面,下边一条线把它与滚动区分开(二级页的
 *   头部是白的,见 docs/page-backgrounds.md §1)。原先搜索框直接落在浅灰上,
 *   同一页里"头部"与"列表"分不出来;
 * - 条目之间要有分隔线:群行与通讯录的人行、会话列表的会话行是同一个东西,
 *   而只有这一页原先没有线,一列白方块糊成一片。
 *
 * **不搬拼音**:那一页按拼音排、按首字母插 sticky 小节头,是因为它是**名册**
 * (与服务端 `?ordering=pinyin` 绑在一起)。群没有这个字段,而且用户对"我的群"
 * 的顺序预期就是会话列表那个顺序 —— 按最近活跃排在前面。所以这里只有白底条目
 * 与分隔线,没有字母头,也不改排序。
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
            // 搜索框属于**固定头部**,不属于列表:滚动时它不该被带走,而且它需要
            // 白底 —— 二级页的头部是白的(搜索框自身的填充色也是 surface,落在
            // 浅灰上就是一块谁也不挨着谁的白)。
            WeMeetSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.im_my_groups_search),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            )
            // 固定头部与滚动列表的分界(与「内部联系人」搜索入口下面那条线同款)。
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = Dimens.DividerThin,
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
                            GroupRow(
                                row = row,
                                query = query,
                                onClick = { onOpenChat(row.cid) },
                            )
                            // 条目白底落在浅灰的滚动区上:列表底是深色、
                            // 条目是浅色(与「内部联系人」「星标联系人」「消息」
                            // 同一套底色关系)。线从文字左缘起,不横穿头像。
                            MemberRowDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一行群:九宫格头像 + 群名 + 成员数。
 *
 * 行本体走通讯录那份共享的 [ContactListRow](见 DirectoryRows.kt)——「头像 40dp、
 * 左右 16dp、上下 8dp、名字 bodyLarge、副标题 bodySmall」这些**不该由这一页再说一遍**。
 * 这里只提供两样这一页独有的东西:群头像是九宫格([GroupAvatar],不是首字母块),以及
 * 名字要按搜索关键词高亮([highlightMatches] 给的是 AnnotatedString)。
 */
@Composable
private fun GroupRow(
    row: ConversationRowUi,
    query: String,
    onClick: () -> Unit,
) {
    ContactListRow(
        name = row.title.ifBlank { stringResource(R.string.im_untitled_chat) },
        nameAnnotated = highlightMatches(
            row.title.ifBlank { stringResource(R.string.im_untitled_chat) },
            query,
        ),
        subtitle = pluralStringResource(
            R.plurals.im_my_groups_member_count,
            row.memberUids.size,
            row.memberUids.size,
        ),
        avatar = {
            GroupAvatar(
                tiles = row.memberTiles,
                customAvatarUrl = row.avatarUrl,
                avatarKey = row.cid,
                size = Dimens.AvatarM,
            )
        },
        onClick = onClick,
    )
}
