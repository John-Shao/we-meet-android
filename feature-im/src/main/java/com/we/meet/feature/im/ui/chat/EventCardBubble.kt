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
    val recurrenceScopeLabel = when (content.recurrenceScope) {
        "one" -> stringResource(R.string.im_event_card_scope_one)
        "following" -> stringResource(R.string.im_event_card_scope_following)
        "all" -> stringResource(R.string.im_event_card_scope_all)
        else -> null
    }
    val allDayLabel = stringResource(R.string.im_event_card_all_day)
    // 日期格式随语言走(中文「M月d日」、英文「MMM d」),所以从资源取而不是写死。
    val datePattern = stringResource(R.string.im_fmt_month_day)
    val timeText = formatEventWhen(
        content.startIso, content.endIso, content.allDay, allDayLabel, datePattern,
    )
    // 改期卡把**改期前**的时间窗划掉显示在新时间上方(与 Web 一致)。原先 App
    // 只有一个「时间已变更」徽章 —— 看得出变了,看不出从什么变成什么。
    val oldTimeText = if (content.kind == "time_changed") {
        formatEventWhen(
            content.oldStartIso, content.oldEndIso, content.allDay, allDayLabel, datePattern,
        )
    } else null

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
                enabled = content.eventId.isNotBlank(),
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
    ) {
        Row {
            Box(
                Modifier
                    .width(Dimens.Chat.CardAccentBarWidth)
                    .fillMaxHeight()
                    .background(
                        if (cancelled) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.primary,
                    ),
            )
            Column(modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Dimens.IconTiny),
                    )
                    Spacer(Modifier.width(Dimens.SpaceXs))
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
                        Spacer(Modifier.width(Dimens.SpaceXs))
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (cancelled) 0.06f else 0.10f,
                                    ),
                                    RoundedCornerShape(Dimens.CornerXs),
                                )
                                .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.BorderThin),
                        )
                    }
                }
                if (recurrenceScopeLabel != null) {
                    Text(
                        text = recurrenceScopeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = Dimens.SpaceXs)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(Dimens.CornerXs),
                            )
                            .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.BorderThin),
                    )
                }
                if (oldTimeText != null) {
                    Text(
                        text = oldTimeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                    )
                }
                if (timeText != null) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (oldTimeText != null) Dimens.SpaceNone else Dimens.SpaceXs),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Dimens.SpaceXxs),
                    )
                }
                if (content.eventId.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.im_event_card_view),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
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
    datePattern: String,
): String? {
    val zone = ZoneId.systemDefault()
    val s = runCatching { OffsetDateTime.parse(startIso).toInstant().atZone(zone) }
        .getOrNull() ?: return null
    val e = runCatching { OffsetDateTime.parse(endIso).toInstant().atZone(zone) }
        .getOrNull() ?: return null
    val dateFmt = DateTimeFormatter.ofPattern(datePattern)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    if (allDay) return "${s.format(dateFmt)} $allDayLabel"
    return if (s.toLocalDate() == e.toLocalDate()) {
        "${s.format(dateFmt)} ${s.format(timeFmt)}-${e.format(timeFmt)}"
    } else {
        "${s.format(dateFmt)} ${s.format(timeFmt)} → ${e.format(dateFmt)} ${e.format(timeFmt)}"
    }
}
