package com.we.meet.feature.im.ui.common

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jusi.lightim.ConnectionState
import com.we.meet.feature.im.R
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.feature.im.model.MessageContentParser

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
    val (bgColor, fgColor) = when (state) {
        ConnectionState.CONNECTED -> Color(0xFFE7F5E7) to Color(0xFF1B5E20)
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
            Color(0xFFFFF6E0) to Color(0xFF7A4F01)
        ConnectionState.AUTH_FAILED -> Color(0xFFFCE4E4) to Color(0xFF8B0000)
        ConnectionState.DISCONNECTED -> Color(0xFFEDEDED) to Color(0xFF444444)
    }
    Surface(color = bgColor, contentColor = fgColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = 16.dp, end = 8.dp),
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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
    Surface(color = Color(0xFFFCE4E4), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8B0000),
        )
    }
}

/**
 * Localized one-line preview for a conversation row — the same parser that
 * drives bubble rendering, so new content types stay consistent in both.
 */
@Composable
fun previewText(contentType: String?, body: String?): String {
    if (body == null) return ""
    return when (val content = MessageContentParser.parse(contentType ?: "text", body)) {
        is MessageContent.Text -> content.body
        is MessageContent.Image -> stringResource(R.string.im_preview_image)
        is MessageContent.File -> stringResource(R.string.im_preview_file, content.name)
        is MessageContent.Voice -> stringResource(R.string.im_preview_voice)
        is MessageContent.Quote -> content.text
        is MessageContent.Merged -> stringResource(R.string.im_preview_merged)
        is MessageContent.Recall -> stringResource(R.string.im_recalled_preview)
        is MessageContent.Reaction -> stringResource(R.string.im_preview_reaction)
        is MessageContent.System -> content.body
        is MessageContent.CallLog -> stringResource(
            if (content.media == "video") R.string.im_calllog_video else R.string.im_calllog_voice
        )
        is MessageContent.GroupCall -> stringResource(R.string.im_calllog_voice)
        is MessageContent.EventCard -> stringResource(R.string.im_preview_event)
        is MessageContent.PhoneViewed -> stringResource(R.string.im_phone_viewed_preview)
        is MessageContent.Unsupported -> stringResource(R.string.im_preview_unsupported)
    }
}
