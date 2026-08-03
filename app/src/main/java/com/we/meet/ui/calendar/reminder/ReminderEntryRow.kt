package com.we.meet.ui.calendar.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.ui.calendar.EventUi
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import java.time.ZonedDateTime

/** 副行缩进:行内边距 12 + 图标 44 + 图标与文字间距 12,让副行与标题左缘对齐。 */
private val ReminderTextIndent = Dimens.SpaceM + Dimens.ListLeadingIcon + Dimens.SpaceM

/**
 * P8 会话列表「日程提醒」入口行(由 app 层构造,经 listHeader 槽位注入
 * feature-im,作为列表首项随列表滚动):橙色日历图标 + 最近日程名 +
 * 倒计时角标 + 时间段副行。
 */
@Composable
fun ReminderEntryRow(
    nearest: EventUi,
    now: ZonedDateTime,
    onClick: () -> Unit,
) {
    val badge = reminderBadge(nearest, now)
    val reminderColor = WeMeetTheme.extras.calendar.reminder
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Dimens.ListLeadingIcon)
                    .background(reminderColor, RoundedCornerShape(Dimens.CornerS)),
            ) {
                Icon(
                    Icons.Filled.EditCalendar,
                    contentDescription = stringResource(R.string.reminder_title),
                    // 不是白 —— 白压在提醒橙上只有 2.39:1(浅)/ 1.95:1(深),
                    // 过不了 SC 1.4.11 的 3:1。见 Color.kt 的 CalendarOnReminder。
                    tint = WeMeetTheme.extras.calendar.onReminder,
                )
            }
            Spacer(Modifier.width(Dimens.SpaceM))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = nearest.title.ifBlank {
                            stringResource(R.string.reminder_title)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(Dimens.SpaceXs))
                        Text(
                            text = when (badge) {
                                is ReminderBadge.Now ->
                                    stringResource(R.string.reminder_now)
                                is ReminderBadge.Soon -> stringResource(
                                    R.string.reminder_in_minutes, badge.minutes,
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            // 同 ReminderScreen 的角标:文字不能和底同色。
                            color = WeMeetTheme.extras.calendar.reminderText,
                            modifier = Modifier
                                .background(
                                    reminderColor.copy(alpha = 0.14f),
                                    RoundedCornerShape(Dimens.CornerXs),
                                )
                                .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXxs),
                        )
                    }
                }
                Text(
                    text = reminderTimeRange(nearest)
                        ?: stringResource(R.string.calendar_all_day),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = ReminderTextIndent),
        )
    }
}
