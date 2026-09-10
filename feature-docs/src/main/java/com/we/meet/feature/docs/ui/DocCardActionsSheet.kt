package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.ui.theme.Dimens

/**
 * 文档行的操作弹层 —— 长按列表行弹出,与消息页「长按 ModalBottomSheet 菜单」同形态。
 *
 * 文档列表不再在行尾放「···」按钮:消息、任务两个列表都用长按出行操作,云文档留一个
 * 可见的溢出按钮是这里唯一的不一致,而且那 48dp 每行都在挤压本就很长的文档标题。
 *
 * 后续的「长按多选」在同一个弹层里加一项(飞书同款),不要另外占用长按手势。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocCardActionsSheet(
    doc: DocumentDto,
    trashMode: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = Dimens.SpaceXl)) {
            if (trashMode) {
                // 回收站行尾已有可见的「恢复」按钮;长按给同一条动作,免得这一行长按
                // 没有反应 —— 两个入口指向同一个回调。
                if (doc.abilities.restore) {
                    DocSheetAction(text = stringResource(R.string.docs_restore)) {
                        onRestore()
                        onDismiss()
                    }
                }
            } else {
                // 未收藏的行没有星标,收藏入口只在这里;已收藏的行星标可快速取消收藏,
                // 这一项保留(与「···」时期的菜单项一致)。
                DocSheetAction(
                    text = stringResource(
                        if (doc.isFavorite) R.string.docs_more_unfavorite else R.string.docs_more_favorite,
                    ),
                    enabled = doc.abilities.favorite,
                ) {
                    onToggleFavorite()
                    onDismiss()
                }
                if (doc.abilities.canRename) {
                    DocSheetAction(text = stringResource(R.string.docs_rename_title)) {
                        onRename()
                        onDismiss()
                    }
                }
                if (doc.abilities.move) {
                    DocSheetAction(text = stringResource(R.string.docs_move_title)) {
                        onMove()
                        onDismiss()
                    }
                }
                if (doc.abilities.destroy) {
                    DocSheetAction(
                        text = stringResource(R.string.docs_delete_title),
                        destructive = true,
                    ) {
                        onDelete()
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** 弹层里的一行动作:整行可点、带涟漪,高度与消息页的弹层动作一致。 */
@Composable
private fun DocSheetAction(
    text: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
        )
    }
}
