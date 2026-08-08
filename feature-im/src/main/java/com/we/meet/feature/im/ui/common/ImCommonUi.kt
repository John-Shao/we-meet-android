package com.we.meet.feature.im.ui.common

import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.jusi.lightim.ConnectionState
import com.we.meet.feature.im.R
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.feature.im.model.MessageContentParser
import com.we.meet.feature.im.model.RichCardParser
import com.we.meet.feature.im.model.RichTextParser

/** Connection banner shared by the list + chat screens. Hidden while CONNECTED. */
@Composable
fun ConnectionStatusBar(state: ConnectionState, onRetry: (() -> Unit)?) {
    if (state == ConnectionState.CONNECTED) return
    // CONNECTING and RECONNECTING mean the same thing to a user: the socket is
    // coming up. The SDK walks RECONNECTING → CONNECTING on every resume, so
    // giving them separate labels made the strip flip its text mid-reconnect
    // ("正在重连…" then "正在连接…"). One label, one message.
    val labelRes = when (state) {
        ConnectionState.DISCONNECTED -> R.string.im_status_disconnected
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> R.string.im_status_connecting
        ConnectionState.CONNECTED -> R.string.im_status_connected
        ConnectionState.AUTH_FAILED -> R.string.im_status_auth_failed
    }
    // Fixed light-tint backgrounds paired with an EXPLICIT dark foreground, so the
    // strip reads in BOTH light and dark themes. Without an explicit fg the text
    // inherits LocalContentColor — light-on-light-pink in dark mode (unreadable).
    // Same approach as [ErrorBanner] below, which was never reported broken.
    val im = WeMeetTheme.extras.im
    val (bgColor, fgColor) = when (state) {
        ConnectionState.CONNECTED -> im.connConnectedBg to im.connConnectedFg
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
            im.connConnectingBg to im.connConnectingFg
        ConnectionState.AUTH_FAILED -> im.connFailedBg to im.connFailedFg
        ConnectionState.DISCONNECTED -> im.connOfflineBg to im.connOfflineFg
    }
    Surface(color = bgColor, contentColor = fgColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.AvatarM)
                .padding(start = Dimens.ScreenPadding, end = Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = fgColor,
            )
            if (onRetry != null) {
                // A compact text action, not a filled Button: this is an ambient
                // status strip, and a 40dp-tall button ballooned it into a banner
                // that looked nothing like the button-less states.
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
                ) {
                    Text(
                        text = stringResource(R.string.im_action_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = fgColor,
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String) {
    val im = WeMeetTheme.extras.im
    Surface(color = im.connFailedBg, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            style = MaterialTheme.typography.bodySmall,
            color = im.connFailedFg,
        )
    }
}

/**
 * Localized one-line preview for a conversation row — the same parser that
 * drives bubble rendering, so new content types stay consistent in both.
 *
 * ⚠️ 结构化卡片的标签**先按 contentType 判定,不解析 body**:会话列表的预览
 * body 由服务端截断(jusi `previewMaxRunes=200`),超长卡片 JSON 会残缺,
 * `parseJson` 抛异常兜底成 Unsupported → 列表显示「暂不支持的消息」。实测
 * event-card 约 225 字(多出 event_id UUID + 起止时间)必被截断,meeting-card
 * 约 111 字侥幸幸存。这些卡片的预览是固定文案、本就不需要 body,故直接短路
 * (与 Web previewOf 的纯 content_type 分支同口径)。
 */
@Composable
fun previewText(contentType: String?, body: String?): String {
    if (body == null) return ""
    when (contentType) {
        "event-card" -> return stringResource(R.string.im_preview_event)
        "doc-card" -> return stringResource(R.string.im_preview_doc)
        "meeting-card" -> return stringResource(R.string.im_preview_meeting)
        // 富文本同理:预览 body 会被截断,但服务端在 body 里带了 `plain` 投影,
        // 截断的 plain 仍然是人话。解析不出来才退固定文案。
        "rich-text" -> return RichTextParser.preview(body)
            .ifBlank { stringResource(R.string.im_preview_rich_text) }
        // 卡片同理,而且**短路必须在 parse 之前**:jusi 把 last_message 截到
        // 200 字,截断的 JSON 解析不出来,截断的 plain 仍是人话。
        "rich-card" -> return RichCardParser.preview(body)
            .ifBlank { stringResource(R.string.im_preview_rich_card) }
    }
    return when (val content = MessageContentParser.parse(contentType ?: "text", body)) {
        is MessageContent.Text -> content.body
        is MessageContent.Image -> stringResource(
            if (content.objectKey.startsWith("emoji/")) R.string.im_preview_emoji
            else R.string.im_preview_image,
        )
        is MessageContent.File -> stringResource(R.string.im_preview_file, content.name)
        is MessageContent.Voice -> stringResource(R.string.im_preview_voice)
        is MessageContent.Quote -> content.text
        is MessageContent.Merged -> stringResource(R.string.im_preview_merged)
        is MessageContent.Recall -> stringResource(R.string.im_recalled_preview)
        is MessageContent.Reaction -> stringResource(R.string.im_preview_reaction)
        is MessageContent.RichText -> RichTextParser.flatten(content.body)
        is MessageContent.RichCard -> RichCardParser.flatten(content.body)
        // card-state 是控制消息,不进预览 —— 它被 isControlType 过滤在渲染
        // 之外,由 ChatViewModel 回放成卡片上的叠加层。
        is MessageContent.CardState -> ""
        is MessageContent.System -> content.body
        is MessageContent.CallLog -> stringResource(
            if (content.media == "video") R.string.im_calllog_video else R.string.im_calllog_voice
        )
        is MessageContent.GroupCall -> stringResource(R.string.im_calllog_voice)
        is MessageContent.EventCard -> stringResource(R.string.im_preview_event)
        is MessageContent.DocCard -> stringResource(R.string.im_preview_doc)
        is MessageContent.MeetingCard -> stringResource(R.string.im_preview_meeting)
        is MessageContent.PhoneViewed -> stringResource(R.string.im_phone_viewed_preview)
        is MessageContent.Unsupported -> stringResource(R.string.im_preview_unsupported)
    }
}
