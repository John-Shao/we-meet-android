package com.we.meet.ui.contacts

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.components.WeMeetSearchEntry
import com.we.meet.ui.theme.Dimens

/**
 * 通讯录 tab 的首页 —— **入口列表**,不是名单。
 *
 * 之前这一页把「固定入口 + 部门树 + 全部成员名单」堆在同一屏:一屏里既有导航又有内容,
 * 部门树还只是组织层级的第一层(点进去还有)。飞书的做法是把首页做成**只有入口**的
 * 几组卡片(内部联系人 / 外部联系人 / 星标 / 我的群组),真正的部门下钻与名单在下一层。
 * 这一页现在就是那个形状 ——
 *
 * - 「内部联系人」→ [OrgContactsScreen](应用级路由 `org_contacts`):部门树 + 成员名单
 *   + 字母小节头 + 部门信息/发起群聊,那一页自带返回键;
 * - 「星标联系人」「我的群组」→ 各自的应用级路由;「外部联系人」仍是一个底部弹层。
 *
 * 首页本身**不取任何目录数据**(没有 ViewModel、没有请求):它就是几个入口,进来即渲染。
 * 列表页的 VM 因此跟着 `org_contacts` 那条路由走 —— 退出那一页,下钻状态就该清掉
 * (下次进来从组织根开始),这也正是路由级作用域的自然语义。
 */
@Composable
fun ContactsTabScreen(
    /** 通讯录首页的搜索入口:没有部门范围,跳到全局搜索的联系人分类。 */
    onOpenSearch: () -> Unit,
    /** 「内部联系人」→ 部门下钻 + 成员名单那一页。 */
    onOpenOrgContacts: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenMyGroups: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    var showExternalContacts by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 一级页的固定头部:浅灰(见 docs/page-backgrounds.md 的层级表)。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Text(
                text = stringResource(R.string.contacts_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(vertical = Dimens.SpaceS),
            )

            // 导航型搜索入口:看着和别处一样,但不可编辑,点了跳统一搜索页。
            WeMeetSearchEntry(
                label = stringResource(R.string.contacts_search_hint),
                onClick = onOpenSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
            )
        }

        // 一级页的滚动内容区:白色,一直延续到底部模块导航栏。
        //
        // 分组用**灰缝**表达(飞书那种「一块一块」的读法),而不是给每块套一个白卡片:
        // 这一页本来就是白的,再套白卡片看不出来。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
        ) {
            // 组织内的两种「人」放一组:都是"找某个同事"的入口,只是范围不同
            // (本组织 / 别的组织)。飞书同款分组。
            EntryRow(
                icon = Icons.Filled.AccountTree,
                label = stringResource(R.string.contacts_org_members),
                onClick = onOpenOrgContacts,
            )
            EntryDivider()
            EntryRow(
                icon = Icons.Filled.PersonAdd,
                label = stringResource(R.string.external_contacts_title),
                onClick = { showExternalContacts = true },
            )

            GroupSeam()

            // 星标与群组各自成组:它们与「组织架构」不是一类东西
            // (一个是归类,一个是会话)。
            EntryRow(
                icon = Icons.Filled.Star,
                label = stringResource(R.string.starred_title),
                onClick = onOpenStarred,
            )

            GroupSeam()

            EntryRow(
                icon = Icons.Filled.Groups,
                label = stringResource(R.string.contacts_my_groups),
                onClick = onOpenMyGroups,
            )

            Spacer(Modifier.height(Dimens.SpaceL))
        }
    }

    if (showExternalContacts) {
        ExternalContactsSheet(
            repository = app.directoryRepository,
            onDismiss = { showExternalContacts = false },
        )
    }
}

/**
 * 两组入口之间的灰缝。分组靠它表达:白底上一条浅灰带,读起来就是「上一组到此为止」
 * (飞书的卡片之间也是这道缝,只是它整页是浅灰、块是白的)。
 */
@Composable
private fun GroupSeam() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.SpaceS)
            .background(MaterialTheme.colorScheme.background),
    )
}

/** 组内两行之间的分隔线:从文字左缘起(与全站列表一致)。 */
@Composable
private fun EntryDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = Dimens.DividerIndent),
    )
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            // 这几个是入口(不是导航内容),用品牌蓝点出来 —— 与部门行区分开。
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.IconMedium),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.ScreenPadding),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
