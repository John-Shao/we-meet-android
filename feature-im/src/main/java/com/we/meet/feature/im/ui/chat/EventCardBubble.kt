package com.we.meet.feature.im.ui.chat

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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.we.meet.feature.im.R
import com.we.meet.feature.im.model.MessageContent
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * P8 日程卡片气泡(content_type='event-card'):左竖色条 + 日历图标 + 标题
 * (变更/取消角标)+ 时间行 + 「N 人参与 · 组织者 X」+ 底部「查看详情」。
 * cancelled 卡降饱和 + 标题删除线;时间解析失败只显标题不崩溃。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventCardBubble(
    content: MessageContent.EventCard,
    isOwn: Boolean,
    onLongPress: (() -> Unit)?,
    onOpen: () -> Unit,
) {
    val cancelled = content.kind == "cancelled"
    val badge = when (content.kind) {
        "time_changed" -> stringResource(R.string.im_event_card_time_changed)
        "attendees_changed" -> stringResource(R.string.im_event_card_attendees_changed)
        "cancelled" -> stringResource(R.string.im_event_card_cancelled)
        else -> null
    }
    val timeText = formatEventWhen(
        content.startIso, content.endIso, content.allDay,
        stringResource(R.string.im_event_card_all_day),
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .widthIn(min = 220.dp, max = 300.dp)
            .combinedClickable(
                enabled = content.eventId.isNotBlank(),
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
    ) {
        Row {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        if (cancelled) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.primary,
                    ),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = if (cancelled) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = content.title.ifBlank {
                            stringResource(R.string.im_preview_event)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cancelled) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (cancelled) 0.06f else 0.10f,
                                    ),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                if (timeText != null) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (content.attendeeCount > 0 || content.organizerName.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.im_event_card_meta,
                            content.attendeeCount,
                            content.organizerName,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (content.eventId.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.im_event_card_view),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** 「M月d日 HH:mm-HH:mm」;跨日起止各带日期;全天 → 日期+全天;解析失败 → null。 */
private fun formatEventWhen(
    startIso: String,
    endIso: String,
    allDay: Boolean,
    allDayLabel: String,
): String? {
    val zone = ZoneId.systemDefault()
    val s = runCatching { OffsetDateTime.parse(startIso).toInstant().atZone(zone) }
        .getOrNull() ?: return null
    val e = runCatching { OffsetDateTime.parse(endIso).toInstant().atZone(zone) }
        .getOrNull() ?: return null
    val dateFmt = DateTimeFormatter.ofPattern("M月d日")
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    if (allDay) return "${s.format(dateFmt)} $allDayLabel"
    return if (s.toLocalDate() == e.toLocalDate()) {
        "${s.format(dateFmt)} ${s.format(timeFmt)}-${e.format(timeFmt)}"
    } else {
        "${s.format(dateFmt)} ${s.format(timeFmt)} → ${e.format(dateFmt)} ${e.format(timeFmt)}"
    }
}
