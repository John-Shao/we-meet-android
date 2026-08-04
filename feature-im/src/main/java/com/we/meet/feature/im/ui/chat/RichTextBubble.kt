package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.we.meet.feature.im.model.RichTextBody
import com.we.meet.feature.im.model.RichTextTag
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 群机器人经 webhook 发来的富文本气泡。
 *
 * 只渲染,不构造 —— 这种消息只有机器人产生。链接走 Compose 1.7 的
 * [LinkAnnotation]/[withLink],不用退回 ClickableText;非 http(s) 的 href 在
 * 解析层就已经降级成纯文本了(见 RichTextParser),这里拿到的一定是可点的。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RichTextBubble(
    body: RichTextBody,
    isOwn: Boolean,
    selfMentionNames: List<String>,
    onLongPress: (() -> Unit)?,
) {
    val bubbleColor = if (isOwn) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOwn) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val mentionBg = WeMeetTheme.extras.im.mentionSelfBg
    val mentionFg = WeMeetTheme.extras.im.mentionSelfFg
    val linkColor = if (isOwn) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        color = bubbleColor,
        shape = RoundedCornerShape(Dimens.CornerM),
        modifier = if (onLongPress != null) {
            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
        } else {
            Modifier
        },
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = Dimens.SpaceM,
                vertical = Dimens.SpaceS,
            ),
        ) {
            if (body.title.isNotBlank()) {
                Text(
                    text = body.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
            body.paragraphs.forEach { paragraph ->
                Text(
                    text = buildAnnotatedString {
                        paragraph.forEach { tag ->
                            when (tag) {
                                is RichTextTag.Text -> append(tag.text)
                                is RichTextTag.Link -> withLink(
                                    LinkAnnotation.Url(
                                        url = tag.href,
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = linkColor,
                                                textDecoration = TextDecoration.Underline,
                                            ),
                                        ),
                                    ),
                                ) { append(tag.text) }
                                is RichTextTag.At -> {
                                    // uid=="all" 是「@所有人」,对每个人都算点名。
                                    val isSelf = tag.uid == "all" ||
                                        selfMentionNames.contains(tag.name)
                                    withStyle(
                                        if (isSelf) {
                                            SpanStyle(
                                                background = mentionBg,
                                                color = mentionFg,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        } else {
                                            SpanStyle(fontWeight = FontWeight.Bold)
                                        },
                                    ) { append("@${tag.name}") }
                                }
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }
        }
    }
}
