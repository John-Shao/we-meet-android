package com.we.meet.ui.calendar.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.WeMeetApp
import com.we.meet.ui.calendar.EventUi
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * P8 日程提醒页(对标飞书):TopAppBar「日程提醒」+ 右上设置齿轮(点入日历
 * 设置页,含「在消息列表提醒日程」开关);最近/进行中日程横幅卡(进入会议);
 * 今日安排/明日安排列表,点条目进日程详情。数据 resume 刷新 + 60s 轮询,
 * 30s tick 刷角标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onEventClick: (eventId: String) -> Unit,
    onJoinSlug: (slug: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val timezoneMode by app.settingsStore.calendarTimezoneMode.collectAsStateWithLifecycle()
    val fixedTimezone by app.settingsStore.calendarFixedTimezone.collectAsStateWithLifecycle()
    val calendarZone = remember(timezoneMode, fixedTimezone) { app.settingsStore.calendarZoneId() }

    var window by remember { mutableStateOf<ReminderWindow?>(null) }
    // First-load failure: without this the screen spins forever on an offline
    // first paint (the poll loop only handled onSuccess).
    var loadFailed by remember { mutableStateOf(false) }
    var now by remember(calendarZone) { mutableStateOf(ZonedDateTime.now(calendarZone)) }
    var refreshKey by remember { mutableStateOf(0) }

    LifecycleResumeEffect(Unit) {
        refreshKey += 1
        onPauseOrDispose { }
    }
    LaunchedEffect(refreshKey, calendarZone) {
        while (true) {
            runCatching { loadReminderWindow(app.apiClient.calendarApi, calendarZone) }
                .onSuccess { window = it; loadFailed = false }
                .onFailure { if (window == null) loadFailed = true }
            delay(60_000)
        }
    }
    LaunchedEffect(calendarZone) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now(calendarZone)
        }
    }

    val nearest = window?.nearest(now)
    val reminderColor = WeMeetTheme.extras.calendar.reminder
    val monthDayPattern = stringResource(R.string.fmt_month_day)
    val dayFmt = remember(monthDayPattern) { DateTimeFormatter.ofPattern(monthDayPattern) }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.reminder_title),
                onBack = onBack,
                actions = {
                    // 对标飞书:右上角设置齿轮,点入日历设置页(含「在消息列表
                    // 提醒日程」开关),而非把开关直接摆在标题栏。
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.calendar_settings_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val w = window
        if (w == null) {
            if (loadFailed) {
                WeMeetErrorState(
                    onRetry = { loadFailed = false; refreshKey += 1 },
                    message = stringResource(R.string.reminder_load_error),
                    modifier = Modifier.padding(padding),
                )
            } else {
                WeMeetLoading(modifier = Modifier.padding(padding))
            }
            return@Scaffold
        }

        if (w.today.isEmpty() && w.tomorrow.isEmpty()) {
            WeMeetEmptyState(
                title = stringResource(R.string.reminder_empty),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        ) {
            if (nearest != null) {
                Surface(
                    shape = RoundedCornerShape(Dimens.CornerM),
                    tonalElevation = Dimens.ElevationSubtle,
                    border = androidx.compose.foundation.BorderStroke(
                        Dimens.BorderThin, MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(Dimens.SpaceM)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = nearest.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val badge = reminderBadge(nearest, now)
                            if (badge != null) {
                                Spacer(Modifier.width(Dimens.SpaceS))
                                Text(
                                    text = when (badge) {
                                        is ReminderBadge.Now ->
                                            stringResource(R.string.reminder_now)
                                        is ReminderBadge.Soon -> stringResource(
                                            R.string.reminder_in_minutes,
                                            badge.minutes,
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    // 文字不能和底同色 —— 橙字压在 14% 橙底上
                                    // 浅色只有 2.07:1。见 Color.kt 的
                                    // LightCalendarReminderText。
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
                            text = "${nearest.start.format(dayFmt)} " +
                                (reminderTimeRange(nearest)
                                    ?: stringResource(R.string.calendar_all_day)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Dimens.SpaceXs),
                        )
                        if (nearest.roomSlug != null) {
                            Spacer(Modifier.height(Dimens.SpaceS))
                            Button(onClick = { onJoinSlug(nearest.roomSlug!!) }) {
                                Text(stringResource(R.string.reminder_join))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.SpaceL))
            }

            if (w.today.isNotEmpty()) {
                ReminderSection(
                    label = stringResource(R.string.reminder_today),
                    events = w.today,
                    onEventClick = onEventClick,
                )
            }
            if (w.tomorrow.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.SpaceM))
                ReminderSection(
                    label = stringResource(R.string.reminder_tomorrow),
                    events = w.tomorrow,
                    onEventClick = onEventClick,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

@Composable
private fun ReminderSection(
    label: String,
    events: List<EventUi>,
    onEventClick: (String) -> Unit,
) {
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = Dimens.SpaceXs),
        )
        events.forEach { e ->
            Row(modifier = Modifier.padding(vertical = Dimens.SpaceXs)) {
                Text(
                    text = if (e.allDay) stringResource(R.string.calendar_all_day)
                    else e.start.format(timeFmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(Dimens.MinTouchTarget)
                        .padding(top = Dimens.SpaceS),
                )
                Spacer(Modifier.width(Dimens.SpaceS))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(Dimens.CornerS),
                        )
                        .clickable { onEventClick(e.id) }
                        .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
                ) {
                    Text(
                        text = e.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (e.allDay) stringResource(R.string.calendar_all_day)
                        else "${e.start.format(timeFmt)} - ${e.end.format(timeFmt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
