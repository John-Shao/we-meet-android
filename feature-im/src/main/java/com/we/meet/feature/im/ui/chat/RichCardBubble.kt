package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.we.meet.feature.im.model.CardBlock
import com.we.meet.feature.im.model.CardButton
import com.we.meet.feature.im.model.CardButtonAction
import com.we.meet.feature.im.model.CardButtonStyle
import com.we.meet.feature.im.model.CardSpan
import com.we.meet.feature.im.model.CardTheme
import com.we.meet.feature.im.model.RichCardBody
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.Dimens

/**
 * `rich-card` 气泡内容(群机器人经 webhook 发来的块级卡片)。
 *
 * 与 [RichTextBubble] 一样挂在**气泡内层**而不是自成一行 —— 白拿表情回应、
 * 已读回执、长按菜单、多选、时间戳、头像整套设施。
 *
 * ## 配色:零新增色值
 *
 * 5 档语义全部映射到 `WeMeetTheme.extras.status` 已有的 `…Container` /
 * `on…Container` 配对(深浅两套齐备,对比度算过),neutral 走
 * `surfaceVariant` / `onSurfaceVariant`。**不要在这里写 `Color(0x…)`** ——
 * checkDesignTokens 会拦,而且那种硬编码在深色模式下必然失守。
 *
 * `surfaceVariant` 只出现在 `.background()`(规则显式豁免这一处),文字一律取
 * `onSurfaceVariant`。
 */
@Composable
private fun themeColors(theme: CardTheme): Pair<Color, Color> {
    val status = WeMeetTheme.extras.status
    return when (theme) {
        CardTheme.INFO -> status.accentActiveContainer to status.onAccentActiveContainer
        CardTheme.SUCCESS -> status.successContainer to status.onSuccessContainer
        CardTheme.WARNING -> status.warningContainer to status.onWarningContainer
        CardTheme.DANGER -> status.dangerContainer to status.onDangerContainer
        CardTheme.NEUTRAL ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RichCardBubble(body: RichCardBody, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = Dimens.Chat.CardMaxWidth)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .border(
                width = Dimens.BorderThin,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(Dimens.CornerM),
            )
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (body.hasHeader) {
            val (container, onContainer) = themeColors(body.headerTheme)
            Text(
                text = body.headerTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = onContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(container)
                    .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            modifier = Modifier.padding(Dimens.SpaceM),
        ) {
            body.blocks.forEach { block ->
                when (block) {
                    CardBlock.Divider -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    is CardBlock.Text -> SpansText(block.spans)

                    is CardBlock.Fields -> Column(
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    ) {
                        // 两列在窄屏上会把「4 分 12 秒」挤成两行,所以 App 端
                        // 一列一项。Web 是宽屏才做两列 —— 这是布局差异,协议
                        // 只保证顺序(见 build_rich_card 的注释)。
                        block.items.forEach { item ->
                            Column {
                                if (item.label.isNotBlank()) {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = item.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    is CardBlock.Actions -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    ) {
                        block.buttons.forEach { CardButtonView(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpansText(spans: List<CardSpan>) {
    val linkColor = MaterialTheme.colorScheme.primary

    // 链接要可点,所以整段走 AnnotatedString + LinkAnnotation(同 RichTextBubble)。
    val text = buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is CardSpan.Text -> withStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                    ),
                ) { append(span.text) }

                is CardSpan.Link -> withLink(
                    LinkAnnotation.Url(
                        url = span.href,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) { append(span.text) }

                is CardSpan.At -> withStyle(
                    SpanStyle(color = linkColor, fontWeight = FontWeight.Bold),
                ) { append("@${span.name}") }
            }
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CardButtonView(button: CardButton) {
    val uriHandler = LocalUriHandler.current
    val tint = when (button.style) {
        CardButtonStyle.PRIMARY -> MaterialTheme.colorScheme.primary
        CardButtonStyle.DANGER -> WeMeetTheme.extras.status.danger
        CardButtonStyle.DEFAULT -> MaterialTheme.colorScheme.onSurface
    }
    OutlinedButton(
        // A2 之前 callback 按钮不会到达客户端(映射器丢掉了它们)。这个分支
        // 是给协议兼容留的:万一来了,禁用比一个点了没反应的按钮诚实。
        enabled = button.action == CardButtonAction.URL,
        onClick = { if (button.action == CardButtonAction.URL) uriHandler.openUri(button.url) },
    ) {
        Text(text = button.text, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}
