package com.we.meet.feature.docs.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Restore
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
 * 行尾不再放「···」:长按整行弹 [DocCardActionsSheet](收藏/重命名/移动/删除,按
 * `abilities` 逐项显隐),与消息、任务两个列表的长按操作一致。行尾只留状态类图标 ——
 * 已收藏的星标(点一下即取消收藏)、回收站的「恢复」。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    var sheetOpen by remember { mutableStateOf(false) }
    val trashMode = mode != DocsHomeViewModel.Mode.HOME
    // 行尾有图标时外侧只留 SpaceXs(图标自带 12dp 内缩,字形才落在 ScreenPadding);
    // 没有图标时内边距直接给正文,免得长标题顶到屏幕边上。
    val rowEndPadding = when {
        trashMode -> if (doc.abilities.restore) Dimens.SpaceXs else Dimens.ScreenPadding
        doc.isFavorite -> Dimens.SpaceXs
        else -> Dimens.ScreenPadding
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 与消息、任务两个列表一致:长按整行出操作弹层,行尾不再放「···」。
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = stringResource(R.string.cd_docs_more),
                    onLongClick = { sheetOpen = true },
                )
                .padding(
                    start = Dimens.ScreenPadding,
                    top = Dimens.SpaceM,
                    bottom = Dimens.SpaceM,
                    end = rowEndPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocsFileIcon(doc.isFolder)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.SpaceM),
            ) {
                Text(
                    text = doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
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
            if (trashMode) {
                if (doc.abilities.restore) {
                    IconButton(onClick = onRestore) {
                        Icon(
                            imageVector = Icons.Outlined.Restore,
                            contentDescription = stringResource(R.string.docs_restore),
                        )
                    }
                }
            } else if (doc.isFavorite) {
                // 星标兼作「已收藏」状态,点一下即取消收藏;收藏入口在长按弹层里。
                IconButton(onClick = onToggleFavorite, enabled = doc.abilities.favorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.cd_docs_unfavorite),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = Dimens.ScreenPadding + Dimens.ListLeadingIcon + Dimens.SpaceM),
            thickness = Dimens.DividerThin, color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (sheetOpen) {
        DocCardActionsSheet(
            doc = doc,
            trashMode = trashMode,
            onDismiss = { sheetOpen = false },
            onToggleFavorite = onToggleFavorite,
            onRename = onRename,
            onMove = onMove,
            onDelete = onDelete,
            onRestore = onRestore,
        )
    }
}

@Composable
private fun buildMetaLine(doc: DocumentDto): String {
    val updated = stringResource(R.string.docs_updated_at, formatIsoTime(doc.updatedAt))
    val excerpt = doc.excerpt?.takeIf { it.isNotBlank() }
    return if (excerpt == null) updated else "$updated · $excerpt"
}
