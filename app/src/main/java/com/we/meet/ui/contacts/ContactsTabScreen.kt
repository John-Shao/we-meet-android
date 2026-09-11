package com.we.meet.ui.contacts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetSearchEntry
import com.we.meet.ui.theme.Dimens
import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.ui.MemberAvatar

/**
 * 通讯录 tab — Feishu-style department drill-down + member list. Drill state is
 * tab-local (the bottom bar stays visible); member detail is an app route.
 *
 * 这一页**不搜索**,只浏览。搜索入口是标题下面那个胶囊:点一下跳到全局搜索页,
 * 并把当前部门作为预选范围带过去([onOpenSearch] 的参数)。
 *
 * 原先部门内还有一个自己的搜索框,拆掉的理由见 [ContactsViewModel] 的 KDoc ——
 * 它是同一件事的第二个壳。连带好处是两条:面包屑不必再在搜索时藏起来(以前
 * 一输入就没了,用户再不知道结果是"本部门内"还是"全公司"),返回键也回到单调
 * 语义(以前是"先清搜索、再退部门"两段式)。
 */
@Composable
fun ContactsTabScreen(
    /** 参数 = 要预选的搜索范围(当前部门);根层级为 null。 */
    onOpenSearch: (departmentId: String?) -> Unit,
    onMemberClick: (userId: String) -> Unit,
    onOpenStarred: () -> Unit,
    onOpenMyGroups: () -> Unit,
) {
    val vm: ContactsViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = key(ui.currentDept?.id) { rememberLazyListState() }
    val app = LocalContext.current.applicationContext as WeMeetApp
    var showExternalContacts by remember { mutableStateOf(false) }
    val currentDept = ui.currentDept

    // 只剩「退一层部门」。搜索拆掉后不再需要先清关键词那一段。
    BackHandler(enabled = ui.deptStack.isNotEmpty()) { vm.popOne() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Text(
            text = stringResource(R.string.contacts_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(vertical = Dimens.SpaceS),
        )

        // 导航型搜索入口:看着和别处一样,但不可编辑,点了跳统一搜索页。
        // 部门名写进文案里 —— 这是用户点之前唯一能知道"会搜到哪"的地方。
        WeMeetSearchEntry(
            label = currentDept?.name?.takeIf { it.isNotBlank() }?.let { deptName ->
                stringResource(R.string.contacts_search_entry_in_dept, deptName)
            } ?: stringResource(R.string.contacts_search_hint),
            onClick = { onOpenSearch(currentDept?.id) },
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
        )

        Breadcrumbs(
            stack = ui.deptStack,
            onCrumbClick = { index -> vm.popTo(index) },
        )
        // 固定头部的下边线(与任务页同款):标题栏 / 搜索入口 / 面包屑都是
        // 浅灰固定区,下边线画出它与白底滚动列表的分界。
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

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // 星标联系人:只在组织根层级露出(钻进部门时是另一个上下文),
                // 对标飞书通讯录里与部门并列的那个独立分组。
                if (ui.deptStack.isEmpty()) {
                    item {
                        StarredEntryRow(onClick = onOpenStarred)
                        MyGroupsEntryRow(onClick = onOpenMyGroups)
                        ExternalContactsEntryRow(onClick = { showExternalContacts = true })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = Dimens.DividerIndent),
                        )
                    }
                }
                items(ui.childDepartments, key = { "d-${it.id}" }) { dept ->
                    DepartmentRow(dept = dept, onClick = { vm.openDepartment(dept) })
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = Dimens.DividerIndent),
                    )
                }
                if (ui.members.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = Dimens.SpaceXxxl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.contacts_empty_dept),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(ui.members, key = { "m-${it.id}" }) { member ->
                        MemberRow(member = member, onClick = { onMemberClick(member.id) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = Dimens.DividerIndentAvatar),
                        )
                    }
                    if (ui.hasMore) {
                        item {
                            when {
                                ui.loadingMore -> WeMeetInlineLoading()
                                ui.loadMoreError -> WeMeetInlineErrorState(
                                    onRetry = vm::loadMore,
                                    message = stringResource(R.string.contacts_load_error),
                                )
                                else -> TextButton(
                                    onClick = vm::loadMore,
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
    }

    if (showExternalContacts) {
        ExternalContactsSheet(
            repository = app.directoryRepository,
            onDismiss = { showExternalContacts = false },
        )
    }
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
            .background(MaterialTheme.colorScheme.background)
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

/** 「⭐ 星标联系人 ›」—— 部门列表之上的固定入口(仅根层级)。 */
@Composable
private fun StarredEntryRow(onClick: () -> Unit) {
    EntryRow(
        icon = Icons.Filled.Star,
        label = stringResource(R.string.starred_title),
        onClick = onClick,
    )
}

/** 「👥 我的群组 ›」—— 紧挨星标联系人的第二个固定入口(仅根层级)。 */
@Composable
private fun MyGroupsEntryRow(onClick: () -> Unit) {
    EntryRow(
        icon = Icons.Filled.Groups,
        label = stringResource(R.string.contacts_my_groups),
        onClick = onClick,
    )
}

@Composable
private fun ExternalContactsEntryRow(onClick: () -> Unit) {
    EntryRow(
        icon = Icons.Filled.PersonAdd,
        label = stringResource(R.string.external_contacts_title),
        onClick = onClick,
    )
}

/**
 * 三个固定入口行长得一模一样(仅图标与文案不同),原先各写了一遍。
 * 收成一处,免得改一次缩进要改三遍。
 */
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
            // 这三个是品牌蓝的高价值固定入口;普通部门是导航内容,不抢这个色。
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

@Composable
private fun DepartmentRow(dept: DepartmentDto, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            // Ordinary departments are navigation content, not primary actions.
            // Reserve brand blue for the fixed high-value entries above.
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconMedium),
        )
        Text(
            text = dept.name.orEmpty(),
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

@Composable
private fun MemberRow(member: MemberDto, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
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
    }
}
