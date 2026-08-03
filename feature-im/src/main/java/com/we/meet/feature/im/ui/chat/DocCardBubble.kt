package com.we.meet.feature.im.ui.chat

import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.feature.im.R
import com.we.meet.feature.im.model.MessageContent

/**
 * 分享云文档到聊天气泡(content_type='doc-card'):左竖色条 + 文档图标 + 标题 +
 * 底部「查看文档」。与 EventCardBubble 不同,这是分享时刻的静态快照,没有
 * badge/取消态——一次性卡片,不追更。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocCardBubble(
    content: MessageContent.DocCard,
    onLongPress: (() -> Unit)?,
    onOpen: () -> Unit,
) {
    val clickable = content.docId.isNotBlank() && content.url.isNotBlank()

    Surface(
        shape = RoundedCornerShape(Dimens.CornerM),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Dimens.ElevationSubtle,
        border = androidx.compose.foundation.BorderStroke(
            Dimens.BorderThin, MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .widthIn(min = Dimens.Chat.CardMinWidth, max = Dimens.Chat.CardMaxWidth)
            .combinedClickable(
                enabled = clickable,
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
    ) {
        Row {
            Box(
                Modifier
                    .width(Dimens.Chat.CardAccentBarWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Dimens.IconTiny),
                    )
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    Text(
                        text = content.title.ifBlank {
                            stringResource(R.string.im_preview_doc)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (clickable) {
                    Text(
                        text = stringResource(R.string.im_doc_card_view),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                    )
                }
            }
        }
    }
}
