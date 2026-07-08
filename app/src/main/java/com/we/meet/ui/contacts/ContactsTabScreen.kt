package com.we.meet.ui.contacts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.ui.MemberAvatar

/**
 * 通讯录 tab — Feishu-style department drill-down + member list. Drill state is
 * tab-local (the bottom bar stays visible); member detail is an app route.
 */
@Composable
fun ContactsTabScreen(onMemberClick: (userId: String) -> Unit) {
    val vm: ContactsViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    // System back clears an active search first, then pops one drill level —
    // otherwise back while searching would leave the tab entirely.
    BackHandler(enabled = ui.searching || ui.deptStack.isNotEmpty()) {
        if (ui.searching) vm.onQueryChange("") else vm.popOne()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.contacts_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 4.dp),
        )

        if (!ui.searching) {
            Breadcrumbs(
                stack = ui.deptStack,
                onCrumbClick = { index -> vm.popTo(index) },
            )
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            ui.error -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    stringResource(R.string.contacts_load_error),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { vm.retry() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.contacts_retry))
                }
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (!ui.searching) {
                    items(ui.childDepartments, key = { "d-${it.id}" }) { dept ->
                        DepartmentRow(dept = dept, onClick = { vm.openDepartment(dept) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 56.dp),
                        )
                    }
                }
                if (ui.members.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(
                                    if (ui.searching) R.string.contacts_empty_search
                                    else R.string.contacts_empty_dept
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(ui.members, key = { "m-${it.id}" }) { member ->
                        MemberRow(member = member, onClick = { onMemberClick(member.id) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 72.dp),
                        )
                    }
                    if (ui.hasMore) {
                        item {
                            TextButton(
                                onClick = { vm.loadMore() },
                                enabled = !ui.loadingMore,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                if (ui.loadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    Text(stringResource(R.string.contacts_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
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
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
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
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
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
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = dept.name.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
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
            .padding(horizontal = Dimens.ScreenPadding, vertical = 10.dp),
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
