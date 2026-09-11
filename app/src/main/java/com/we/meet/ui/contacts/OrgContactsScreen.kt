package com.we.meet.ui.contacts

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetSearchEntry
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

/**
 * 通讯录 › **内部联系人** —— 部门下钻 + 当前节点的成员名单。
 *
 * 这一页是应用级路由(`org_contacts`),不在 tab 内:通讯录首页是入口列表(见
 * [ContactsTabScreen]),真正的「逛组织」在这里,进入时带上返回键。
 *
 * 这一页**不搜索**,只浏览。搜索入口是下面那个胶囊:点一下跳到全局搜索页,并把当前
 * 部门作为预选范围带过去([onOpenSearch] 的参数)—— 部门名写在文案里,这是用户点之前
 * 唯一能知道"会搜到哪"的地方。
 *
 * 名册一律按**拼音**排(服务端 `?ordering=pinyin`),列表里按首字母插小节头
 * (只在界面语言是简体中文时画,见 [letterHeadersEnabled])。
 *
 * (右侧的 A–Z 索引条已经去掉:一列 27 行的小竖条在手机上既挤又突兀,而它换来的
 * 「跳到一个字母」这一步,搜索与滚动已经够用。排序与字母头保留。)
 *
 * 原先部门内还有一个自己的搜索框,拆掉的理由见 [ContactsViewModel] 的 KDoc ——
 * 它是同一件事的第二个壳。连带好处是两条:面包屑不必再在搜索时藏起来(以前
 * 一输入就没了,用户再不知道结果是"本部门内"还是"全公司"),返回键也回到单调
 * 语义(以前是"先清搜索、再退部门"两段式)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrgContactsScreen(
    onBack: () -> Unit,
    /** 参数 = 要预选的搜索范围(当前部门);根层级为 null。 */
    onOpenSearch: (departmentId: String?) -> Unit,
    onMemberClick: (userId: String) -> Unit,
    /** 部门群建好后进会话。 */
    onOpenChat: (cid: String) -> Unit,
) {
    val vm: ContactsViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val currentDept = ui.currentDept

    // 字母头的语言门槛(与 Web 一致)。读的是**当前生效**的语言:设置里的
    // per-app locale 与「跟随系统」都会体现在 configuration 里。
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag()
    LaunchedEffect(languageTag) { vm.setLetterHeadersEnabled(letterHeadersEnabled(languageTag)) }

    // 换部门 = 换了一份名册 → 回到顶部。同一个 listState 会留着滚动位置,
    // 而新名单的第一行跟上一份结果没有任何关系。
    LaunchedEffect(ui.listResetTick) {
        if (ui.listResetTick > 0) listState.scrollToItem(0)
    }

    LaunchedEffect(vm) { vm.chatReady.collect { cid -> onOpenChat(cid) } }
    LaunchedEffect(vm) {
        vm.notice.collect { notice ->
            Toast.makeText(context, noticeText(context, notice), Toast.LENGTH_SHORT).show()
        }
    }

    // 只剩「退一层部门」。搜索拆掉后不再需要先清关键词那一段;退到组织根时交给
    // 系统返回(回到通讯录首页)。
    BackHandler(enabled = ui.deptStack.isNotEmpty()) { vm.popOne() }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.contacts_org_members),
                onBack = {
                    if (ui.deptStack.isNotEmpty()) vm.popOne() else onBack()
                },
            )
        },
    ) { padding ->
        // 二级页的底色规范(docs/page-backgrounds.md):顶栏白色,下方滚动区域浅灰。
        // 所以这里**不**铺白底 —— 白色只留给顶栏与它下面那条固定头部(搜索入口 /
        // 面包屑),名单本身直接落在浅灰上(与 StarredContactsScreen 同一个做法)。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            WeMeetSearchEntry(
                label = currentDept?.name?.takeIf { it.isNotBlank() }?.let { deptName ->
                    stringResource(R.string.contacts_search_entry_in_dept, deptName)
                } ?: stringResource(R.string.contacts_search_hint),
                onClick = { onOpenSearch(currentDept?.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
            )

            Breadcrumbs(
                stack = ui.deptStack,
                onCrumbClick = { index -> vm.popTo(index) },
            )
            // 固定头部的下边线(与任务页同款):搜索入口 / 面包屑是白色固定区,
            // 下边线画出它与浅灰滚动列表的分界。
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = Dimens.DividerThin,
            )

            when {
                ui.loading -> WeMeetLoading()

                ui.error -> WeMeetErrorState(
                    onRetry = vm::retry,
                    message = stringResource(R.string.contacts_load_error),
                )

                else -> ContactList(
                    ui = ui,
                    currentDept = currentDept,
                    listState = listState,
                    onOpenDept = vm::openDepartment,
                    onMemberClick = onMemberClick,
                    onLoadMore = vm::loadMore,
                    onStartGroupChat = vm::requestGroupChat,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    ui.groupChatPrompt?.let { prompt ->
        GroupChatConfirmDialog(
            prompt = prompt,
            onConfirm = vm::confirmGroupChat,
            onDismiss = vm::dismissGroupChat,
        )
    }
}

/**
 * 名册本体:固定头(搜索入口 / 面包屑)之下的那一整块。
 *
 * `stickyHeader` 用来画字母小节头 —— 它与窗口化的 `LazyColumn` 天然合得来,不需要
 * 自己算位置。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactList(
    ui: ContactsUiState,
    currentDept: DepartmentDto?,
    listState: LazyListState,
    onOpenDept: (DepartmentDto) -> Unit,
    onMemberClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onStartGroupChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(ui.childDepartments, key = { "d-${it.id}" }) { dept ->
            DepartmentRow(dept = dept, onClick = { onOpenDept(dept) })
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = Dimens.DividerIndent),
            )
        }
        // 当前部门的信息行(负责人 / 直属人数 / 发起群聊)。只有真的在部门里才有
        // —— 组织根层级没有"哪个部门"这回事。放在子部门与成员之间:它既是这个
        // 部门的一句话摘要,也顺手把「部门」和「人」两块分开。
        currentDept?.let { dept ->
            item(key = "dept-info") {
                DepartmentInfoRow(
                    dept = dept,
                    // 直属 0 人但有下级部门的部门是真的存在(人全在下级),那时屏幕上
                    // 其实有人可拉 —— 所以判据是「这个部门直属有人 or 这一屏有人」,
                    // 二者皆空才是"点了也只能听到一句没有成员"。
                    onStartGroupChat = if (dept.memberCount > 0 || ui.members.isNotEmpty()) {
                        onStartGroupChat
                    } else {
                        null
                    },
                )
            }
        }
        if (ui.members.isEmpty()) {
            item(key = "empty") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.SpaceXxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (currentDept == null) {
                            stringResource(R.string.contacts_empty_org)
                        } else {
                            stringResource(R.string.contacts_empty_dept)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            ui.entries.forEach { entry ->
                when (entry) {
                    // sticky:滚动时始终知道自己看到哪个字母了。
                    is ContactEntry.Letter -> stickyHeader(key = "h-${entry.initial}") {
                        LetterHeader(initial = entry.initial)
                    }

                    is ContactEntry.Person -> item(key = "m-${entry.member.id}") {
                        MemberRow(
                            member = entry.member,
                            // 部门视图里整列都是同一个部门,再写一遍是零信息;
                            // 「全部成员」里部门恰恰是这个人唯一的区别。
                            showDepartment = currentDept == null,
                            onClick = { onMemberClick(entry.member.id) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = Dimens.DividerIndentAvatar),
                        )
                    }
                }
            }
            if (ui.hasMore) {
                item(key = "more") {
                    when {
                        ui.loadingMore -> WeMeetInlineLoading()
                        ui.loadMoreError -> WeMeetInlineErrorState(
                            onRetry = onLoadMore,
                            message = stringResource(R.string.contacts_load_error),
                        )
                        else -> TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Dimens.SpaceXs),
                        ) {
                            Text(stringResource(R.string.contacts_load_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * sticky 字母头。直接显示服务端下发的 `initial` —— '#' 桶就显示井号本身:
 * 它是一段(数字/符号/空名字),不是「其他」。
 *
 * 底色是 `background`(浅灰)——字母头属于**列表的底**,白底条目之间的那道灰缝
 * 由它给出(与「通讯录」首页的 GroupSeam 同一个读法)。原先这里写的是 `surface`
 * (白):行没有底色(落成浅灰)、字母头却是白的,底色关系正好反了 —— 名单看着
 * 是凹进去的,小节头反而成了唯一有底色的一块。
 */
@Composable
private fun LetterHeader(initial: String) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.AlphabetHeaderHeight)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Dimens.ScreenPadding),
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 当前部门的信息行:负责人、直属人数、以及部门级「发起群聊」。
 *
 * 人数写的是 [DepartmentDto.memberCount](**直属**,服务端 annotate 的值),不是
 * 当前列表的条数:这份列表含下级部门、还会被索引条的起点字母收窄,拿它当"部门
 * 有多少人"就会一会儿一个数。直属/全部这层区别由文案说清(「直属 N 人」)。
 */
@Composable
private fun DepartmentInfoRow(
    dept: DepartmentDto,
    /** null = 这个部门没人可拉,不显示按钮(与 Web 同一克制:点了必然是一句空话)。 */
    onStartGroupChat: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dept.head?.fullName?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.contacts_dept_head, it) }
                    ?: stringResource(R.string.contacts_dept_head_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.contacts_dept_direct_members,
                    dept.memberCount,
                    dept.memberCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 空部门不给按钮:点了必然是一句「没有成员」,不如不给(与 Web 一致)。
        if (onStartGroupChat != null) {
            TextButton(onClick = onStartGroupChat) {
                Text(stringResource(R.string.contacts_dept_start_group_chat))
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 建群前的确认框:把「拉几个人进哪个群」说清楚。
 *
 * 建群会通知到每一个人,所以这一步不做「点完直接建」——那是不可撤销的社交动作。
 */
@Composable
private fun GroupChatConfirmDialog(
    prompt: GroupChatPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!prompt.creating) onDismiss() },
        title = { Text(stringResource(R.string.contacts_dept_start_group_chat)) },
        text = {
            Text(
                stringResource(
                    R.string.contacts_dept_group_chat_confirm,
                    prompt.deptName,
                    prompt.memberCount,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !prompt.creating) {
                if (prompt.creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSmall),
                        strokeWidth = Dimens.BorderEmphasis,
                    )
                } else {
                    Text(stringResource(R.string.common_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !prompt.creating) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 一次性提示的文案(VM 不碰 strings.xml,映射放在 UI 侧)。 */
private fun noticeText(context: Context, notice: ContactsNotice): String = when (notice) {
    ContactsNotice.EmptyDepartment -> context.getString(R.string.contacts_dept_group_chat_empty)
    is ContactsNotice.GroupChatTooMany -> context.getString(
        R.string.contacts_dept_group_chat_too_many,
        notice.count,
        notice.limit,
    )
    is ContactsNotice.GroupChatFailed -> context.getString(
        R.string.contacts_dept_group_chat_failed,
        notice.message,
    )
}

@Composable
private fun Breadcrumbs(
    stack: List<DepartmentDto>,
    onCrumbClick: (index: Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
    ) {
        Text(
            text = stringResource(R.string.contacts_root_org),
            style = MaterialTheme.typography.bodyMedium,
            color = if (stack.isEmpty()) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = stack.isNotEmpty()) { onCrumbClick(-1) },
        )
        stack.forEachIndexed { index, dept ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            val isLast = index == stack.lastIndex
            Text(
                text = dept.name.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLast) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = !isLast) { onCrumbClick(index) },
            )
        }
    }
}

@Composable
private fun DepartmentRow(dept: DepartmentDto, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 条目白底、列表浅灰:名字与人数落在白底上,块与块之间由字母头那道
            // 灰缝分开(见 LetterHeader)。行内不铺白底的话,整张名单是一片浅灰。
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            // Ordinary departments are navigation content, not primary actions.
            // Reserve brand blue for the fixed high-value entries on the home page.
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconMedium),
        )
        Text(
            text = dept.name.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.ScreenPadding),
        )
        // 人数:空部门不写一个「0」,那是纯噪声;有人的部门点进去之前就该知道
        // 里面有多少人(服务端 annotate,不额外请求 —— 与 Web 的部门树一致)。
        if (dept.memberCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.contacts_member_count,
                    dept.memberCount,
                    dept.memberCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.SpaceS),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemberRow(
    member: MemberDto,
    showDepartment: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
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
            Text(
                text = member.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                member.title?.takeIf { it.isNotBlank() },
                if (showDepartment) member.department?.name?.takeIf { it.isNotBlank() } else null,
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
    }
}
