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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.R
import com.we.meet.ui.calendar.EventUi
import java.time.ZonedDateTime

/** 飞书同款橙(日程提醒专属,不随主题)。 */
private val ReminderOrange = Color(0xFFFF8800)

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
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .background(ReminderOrange, RoundedCornerShape(10.dp)),
            ) {
                Icon(
                    Icons.Filled.EditCalendar,
                    contentDescription = stringResource(R.string.reminder_title),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(12.dp))
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
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when (badge) {
                                is ReminderBadge.Now ->
                                    stringResource(R.string.reminder_now)
                                is ReminderBadge.Soon -> stringResource(
                                    R.string.reminder_in_minutes, badge.minutes,
                                )
                            },
                            fontSize = 11.sp,
                            color = ReminderOrange,
                            modifier = Modifier
                                .background(
                                    ReminderOrange.copy(alpha = 0.14f),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp),
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
            modifier = Modifier.padding(start = 68.dp),
        )
    }
}
