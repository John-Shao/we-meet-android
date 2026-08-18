package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.we.meet.feature.im.model.CardResolution
import com.we.meet.feature.im.model.actionsBlockKey
import com.we.meet.feature.im.model.CardButton
import com.we.meet.feature.im.model.CardButtonAction
import com.we.meet.feature.im.model.CardButtonStyle
import com.we.meet.feature.im.model.CardField
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
 * 5 档语义全部映射到 `WeMeetTheme.extras.status` 的 `…Container` /
 * `on…Container` 配对(深浅两套齐备,对比度算过)。**不要在这里写
 * `Color(0x…)`** —— checkDesignTokens 会拦,而且那种硬编码在深色模式下必然
 * 失守。
 *
 * neutral 曾经走 `colorScheme.surfaceVariant`,真机上暴露出问题:那个值 M3
 * 是从 primary 派生的,在本 App 里带明显紫调 —— 「无强调」的卡片头看起来像
 * 有强调,而且**跟普通消息气泡撞色**,一眼分不出是卡片还是一条普通消息。
 * 现在走专门的 `neutralContainer`,与 Web 的 `greyscale.100` 对齐。
 */
@Composable
private fun themeColors(theme: CardTheme): Pair<Color, Color> {
    val status = WeMeetTheme.extras.status
    return when (theme) {
        CardTheme.INFO -> status.accentActiveContainer to status.onAccentActiveContainer
        CardTheme.SUCCESS -> status.successContainer to status.onSuccessContainer
        CardTheme.WARNING -> status.warningContainer to status.onWarningContainer
        CardTheme.DANGER -> status.dangerContainer to status.onDangerContainer
        CardTheme.NEUTRAL -> status.neutralContainer to status.onNeutralContainer
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RichCardBubble(
    body: RichCardBody,
    modifier: Modifier = Modifier,
    /**
     * 服务端的叠加层(actions 块 key → 定局结果)。**它是唯一真相** ——
     * ws 的 card-state 可能早于点击接口的响应到达,所以这里不做本地乐观态。
     */
    resolved: Map<String, CardResolution> = emptyMap(),
    /** 点一个 callback 按钮。null = 按钮渲染成禁用态(引用/转发的场景)。 */
    onClickButton: ((String) -> Unit)? = null,
    onOpenDoc: ((String) -> Unit)? = null,
) {
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
            body.blocks.forEachIndexed { index, block ->
                when (block) {
                    CardBlock.Divider -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    is CardBlock.Text -> SpansText(block.spans)

                    is CardBlock.Fields -> Column(
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    ) {
                        // **恒两列,奇数末项跨整行** —— 与 Web 同一条规则。
                        // 发送方是照着两列网格设计卡片的(「环境 | 耗时」本来
                        // 就该并排读),单列会把这个意图拆掉。
                        //
                        // 曾经这里是一列一项,理由是「窄屏会把『4 分 12 秒』
                        // 挤成两行」。真机上看:卡片宽度下每列仍有富余,而且
                        // 就算折行也比丢掉网格语义好。
                        val rows = block.items.chunked(2)
                        rows.forEach { pair ->
                            if (pair.size == 1) {
                                // 落单的一项跨整行,不留半行空白。
                                FieldItem(pair[0], Modifier.fillMaxWidth())
                            } else {
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(Dimens.SpaceM),
                                ) {
                                    pair.forEach { item ->
                                        FieldItem(item, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    is CardBlock.Actions -> {
                        // 定局之后**原地换成结果条**,而不是把按钮置灰留着 ——
                        // 留着会让人以为还能改。block key 的推导是三端契约,
                        // 见 actionsBlockKey。
                        val hit = resolved[actionsBlockKey(body.blocks, index)]
                        if (hit != null) {
                            Text(
                                text = hit.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                            ) {
                                block.buttons.forEach {
                                    CardButtonView(it, onClickButton, onOpenDoc)
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
private fun CardButtonView(
    button: CardButton,
    onClickButton: ((String) -> Unit)?,
    onOpenDoc: ((String) -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val tint = when (button.style) {
        CardButtonStyle.PRIMARY -> MaterialTheme.colorScheme.primary
        CardButtonStyle.DANGER -> WeMeetTheme.extras.status.danger
        CardButtonStyle.DEFAULT -> MaterialTheme.colorScheme.onSurface
    }
    // 没有 onClickButton 的场合(引用、转发预览)callback 按钮渲染成禁用态
    // —— 一个明摆着不能点的按钮,比一个点了没反应的按钮诚实。
    val clickable = when (button.action) {
        CardButtonAction.URL -> true
        CardButtonAction.DOC -> onOpenDoc != null && button.url.isNotBlank()
        CardButtonAction.CALLBACK -> onClickButton != null
    }
    OutlinedButton(
        enabled = clickable,
        onClick = {
            when (button.action) {
                CardButtonAction.URL -> uriHandler.openUri(button.url)
                CardButtonAction.DOC -> onOpenDoc?.invoke(button.url)
                CardButtonAction.CALLBACK -> onClickButton?.invoke(button.id)
            }
        },
    ) {
        Text(text = button.text, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * fields 里的一项。两列时 `weight(1f)` 平分,落单的一项 `fillMaxWidth()`。
 *
 * 抽出来是因为两个分支要渲染同一个东西 —— 内联两遍的话,以后改标签样式必然
 * 只改一处。
 */
@Composable
private fun FieldItem(item: CardField, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (item.label.isNotBlank()) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = item.value, style = MaterialTheme.typography.bodyMedium)
    }
}
