package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.feature.docs.R
import com.we.meet.ui.theme.Dimens

/**
 * 云文档二级导航抽屉(设计对齐:内容结构保留 Web 端文档的二级导航 ——
 * 所有文档/我的文档/与我分享/垃圾桶;「导航栏」的视觉与交互参考任务模块,
 * 即 [DocsHomeScreen] 内用 ModalNavigationDrawer + 带计数、选中高亮的
 * DrawerItem(与 TaskScreen 的 DrawerItem 一致:选中 primaryContainer 底 +
 * primary 图标文字、行尾计数)。
 */
@Composable
fun DocsNavDrawer(
    selectedFilter: DocsHomeViewModel.Filter,
    allCount: Int?,
    mineCount: Int?,
    sharedCount: Int?,
    trashCount: Int?,
    onDismiss: () -> Unit,
    onSelectFilter: (DocsHomeViewModel.Filter) -> Unit,
    onOpenTrash: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceXl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.ListLeadingIcon),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Description,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(Dimens.SpaceM))
            Text(
                text = stringResource(R.string.docs_screen_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, stringResource(R.string.docs_cancel))
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                Text(
                    text = stringResource(R.string.docs_nav_views),
                    modifier = Modifier.padding(
                        horizontal = Dimens.SpaceXl,
                        vertical = Dimens.SpaceS,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DrawerItemRow(
                    Icons.Outlined.Description,
                    stringResource(R.string.docs_nav_all),
                    allCount?.toString(),
                    selected = selectedFilter == DocsHomeViewModel.Filter.ALL,
                ) {
                    onSelectFilter(DocsHomeViewModel.Filter.ALL)
                }
                DrawerItemRow(
                    Icons.Outlined.PersonOutline,
                    stringResource(R.string.docs_nav_mine),
                    mineCount?.toString(),
                    selected = selectedFilter == DocsHomeViewModel.Filter.MINE,
                ) {
                    onSelectFilter(DocsHomeViewModel.Filter.MINE)
                }
                DrawerItemRow(
                    Icons.Outlined.PeopleOutline,
                    stringResource(R.string.docs_nav_shared),
                    sharedCount?.toString(),
                    selected = selectedFilter == DocsHomeViewModel.Filter.SHARED,
                ) {
                    onSelectFilter(DocsHomeViewModel.Filter.SHARED)
                }
                DrawerItemRow(
                    Icons.Outlined.DeleteOutline,
                    stringResource(R.string.docs_nav_trash),
                    trashCount?.toString(),
                ) {
                    onOpenTrash()
                }
            }
        }
    }
}

/** 与 TaskScreen.DrawerItem 同款的行项:选中 primaryContainer 底,行尾计数。 */
@Composable
private fun DrawerItemRow(
    icon: ImageVector,
    label: String,
    count: String?,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceM)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val contentColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, null, tint = contentColor)
            Spacer(Modifier.width(Dimens.SpaceL))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (count != null) {
                Text(count, color = contentColor)
            }
        }
    }
}
