package com.we.meet.feature.docs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.util.formatIsoTime
import com.we.meet.ui.theme.Dimens

/**
 * 文档列表行(设计规范 §1 语义:标题 titleMedium + 副文案 bodySmall + 行高)。
 *
 * 行尾两个动作:收藏星标(可切换,有语义描述)与「更多」溢出菜单(收藏/重命名/
 * 移动/删除,按 `abilities` 逐项显隐);回收站模式只给「恢复」。
 */
@Composable
fun DocListItem(
    doc: DocumentDto,
    mode: DocsHomeViewModel.Mode,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    start = Dimens.ScreenPadding,
                    top = Dimens.SpaceM,
                    bottom = Dimens.SpaceM,
                    end = Dimens.SpaceXs,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (doc.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconMedium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.SpaceM),
            ) {
                Text(
                    text = doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildMetaLine(doc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (mode == DocsHomeViewModel.Mode.HOME) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (doc.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(
                            if (doc.isFavorite) R.string.cd_docs_unfavorite else R.string.cd_docs_favorite,
                        ),
                        tint = if (doc.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.cd_docs_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (doc.isFavorite) R.string.docs_more_unfavorite else R.string.docs_more_favorite,
                                    ),
                                    softWrap = false,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleFavorite()
                            },
                        )
                        if (doc.abilities.canRename) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.docs_rename_title), softWrap = false) },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                },
                            )
                        }
                        if (doc.abilities.move) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.docs_move_title), softWrap = false) },
                                onClick = {
                                    menuExpanded = false
                                    onMove()
                                },
                            )
                        }
                        if (doc.abilities.destroy) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.docs_delete_title),
                                        color = MaterialTheme.colorScheme.error,
                                        softWrap = false,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            } else {
                if (doc.abilities.restore) {
                    IconButton(onClick = onRestore) {
                        Icon(
                            imageVector = Icons.Outlined.Restore,
                            contentDescription = stringResource(R.string.docs_restore),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun buildMetaLine(doc: DocumentDto): String {
    val updated = stringResource(R.string.docs_updated_at, formatIsoTime(doc.updatedAt))
    val excerpt = doc.excerpt?.takeIf { it.isNotBlank() }
    return if (excerpt == null) updated else "$updated · $excerpt"
}
