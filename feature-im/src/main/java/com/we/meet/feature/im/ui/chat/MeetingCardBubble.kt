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
import androidx.compose.material.icons.filled.Videocam
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
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 分享会议到聊天气泡(content_type='meeting-card'):左竖色条 + 视频图标 + 标题 +
 * 时间行(进行中 / 预约时间)+ 会议号 + 底部「加入会议」。点卡片按 slug 走入会
 * 预览。与 DocCardBubble 一样是分享时刻静态快照,不追更。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MeetingCardBubble(
    content: MessageContent.MeetingCard,
    onLongPress: (() -> Unit)?,
    onJoin: () -> Unit,
) {
    val clickable = content.slug.isNotBlank()
    // 日期格式随语言走,从资源取而不是写死在格式化函数里。
    val whenPattern = stringResource(R.string.im_fmt_month_day_time)
    val whenText =
        if (content.status == "scheduled") formatMeetingWhen(content.scheduledAtIso, whenPattern)
        else stringResource(R.string.im_meeting_card_ongoing)

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
                onClick = onJoin,
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
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Dimens.IconTiny),
                    )
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    Text(
                        text = content.title.ifBlank {
                            stringResource(R.string.im_preview_meeting)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (whenText != null) {
                    Text(
                        text = whenText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                    )
                }
                if (content.slug.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.im_meeting_card_no, formatMeetingNo(content.slug),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = Dimens.SpaceXxs),
                    )
                }
                if (clickable) {
                    Text(
                        text = stringResource(R.string.im_meeting_card_join),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                    )
                }
            }
        }
    }
}

/** 数字会议号按 4 位分组:「20801316」→「2080 1316」。 */
private fun formatMeetingNo(slug: String): String =
    if (slug.all { it.isDigit() }) slug.chunked(4).joinToString(" ") else slug

/** 「M月d日 HH:mm」(本地时区);解析失败 → null。 */
private fun formatMeetingWhen(iso: String, pattern: String): String? {
    if (iso.isBlank()) return null
    val zoned = runCatching {
        OffsetDateTime.parse(iso).toInstant().atZone(ZoneId.systemDefault())
    }.getOrNull() ?: return null
    return zoned.format(DateTimeFormatter.ofPattern(pattern))
}
