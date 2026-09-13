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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.data.repository.OrgState
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 通讯录 tab 的首页 —— **入口列表**,不是名单。
 *
 * 之前这一页把「固定入口 + 部门树 + 全部成员名单」堆在同一屏:一屏里既有导航又有内容,
 * 部门树还只是组织层级的第一层(点进去还有)。飞书的做法是首页**只有入口**,而且:
 *
 * - 顶部工具栏右侧一个放大镜就是搜索入口 —— 不在页面里再摆一条搜索框:那条框占掉
 *   一整行,还把「当前组织」挤到下面;
 * - 工具栏下面第一行是**当前组织**(头像 + 名字),用户由此知道自己在哪个组织的名册里;
 * - 再往下是几组入口:内部联系人 / 外部联系人、星标联系人、我的群组。
 *
 * 「内部联系人」→ [OrgContactsScreen](应用级路由 `org_contacts`):部门树 + 成员名单
 * + 字母小节头 + 部门信息/发起群聊,那一页自带返回键。「星标联系人」「我的群组」→
 * 各自的应用级路由;「外部联系人」仍是一个底部弹层。
 *
 * 首页只多取一份**组织上下文**(一个单行查询;进程级共享的那一份见
 * `com.we.meet.data.repository.OrgContextStore`),没有 ViewModel 也没有列表请求:
 * 它不是页面的骨架 —— 还不知道时先占住位置、确实没有时不画那一行,都不拦住任何东西。
 * 列表页的 VM 跟着 `org_contacts` 那条路由走 —— 退出那一页,下钻状态就该清掉
 * (下次进来从组织根开始)。
 */
@Composable
fun ContactsTabScreen(
    /** 顶部工具栏的搜索入口:没有部门范围,跳到全局搜索的联系人分类。 */
    onOpenSearch: () -> Unit,
    /** 「内部联系人」→ 部门下钻 + 成员名单那一页。 */
    onOpenOrgContacts: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenMyGroups: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    var showExternalContacts by remember { mutableStateOf(false) }

    // 组织上下文:进程级共享(MainTabScreen 启动时已预热一次),读的是 StateFlow ——
    // 有本地缓存时第一次组合就有值,不会再"先空着、数据回来才跳出来"。进页面再刷一次
    // 拿最新的,拉的是同一个 store:与我的页共一份,两处不会各拉一次、也不会说法不一。
    val orgState by app.orgContextStore.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        app.orgContextStore.refresh()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 一级页的固定头部:浅灰(见 docs/page-backgrounds.md 的层级表)。
        // 标题 + 右侧搜索入口,与「会议」tab 的头部同一个形状。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                // 左侧标题顶到 ScreenPadding;右侧按钮自带 12dp 内缩,外侧只留
                // SpaceXs,使右侧图标字形与左侧标题同为 16dp(左右对称)。
                .padding(start = Dimens.ScreenPadding, end = Dimens.SpaceXs)
                .padding(vertical = Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.contacts_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.contacts_search_hint),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
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
            // 当前组织:内容区的第一行。只说「你在哪个组织的名册里」,不是入口 ——
            // App 没有「组织详情」页,做成可点却什么都不发生比不点更糟。
            //
            // 三态见 [OrgState]:有就画;还不知道就占住同样的位置(否则值回来时下面四个
            // 入口会一起往下弹);服务端明确说没有组织时整行不画。
            when (val state = orgState) {
                is OrgState.Known -> OrgHeader(
                    name = state.org.name.orEmpty(),
                    orgId = state.org.id,
                )
                OrgState.Unknown -> OrgHeaderPlaceholder()
                OrgState.None -> Unit
            }

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
 * 当前组织那一行:头像 + 名字。就一件事 —— 让用户知道自己在哪个组织的名册里。
 */
@Composable
private fun OrgHeader(name: String, orgId: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceS, bottom = Dimens.SpaceM),
    ) {
        MemberAvatar(
            name = name,
            url = null,
            cacheKey = "org:$orgId",
            size = Dimens.AvatarM,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.SpaceM),
        )
    }
}

/**
 * 组织还不知道时的同一行:几何照抄 [OrgHeader](同样 40dp 的圆角方块 + 名字位置),只把
 * 内容换成灰块。这样值回来时是"填进去",不是把下面四个入口推下去。
 *
 * 用 `neutralContainer`(设计规范里「没有强调」那一档)而不是 `surfaceVariant`:后者在本
 * App 里带紫调,见 Theme.kt 那段说明。
 */
@Composable
private fun OrgHeaderPlaceholder() {
    val placeholder = WeMeetTheme.extras.status.neutralContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceS, bottom = Dimens.SpaceM),
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.AvatarM)
                .clip(RoundedCornerShape(Dimens.CornerS))
                .background(placeholder),
        )
        Box(
            modifier = Modifier
                .padding(start = Dimens.SpaceM)
                .size(width = Dimens.SkeletonBarWidth, height = Dimens.SkeletonBarHeight)
                .clip(RoundedCornerShape(Dimens.CornerXs))
                .background(placeholder),
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
