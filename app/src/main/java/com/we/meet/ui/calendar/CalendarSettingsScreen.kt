package com.we.meet.ui.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.SettingsDivider
import com.we.meet.ui.components.SettingsGroup
import com.we.meet.ui.components.SettingsGroupHeader
import com.we.meet.ui.components.SettingsHint
import com.we.meet.ui.components.SettingsPickerOption
import com.we.meet.ui.components.SettingsPickerSheet
import com.we.meet.ui.components.SettingsRow
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.settings.CalendarWeekStart
import com.we.meet.data.settings.CalendarTimezoneMode
import com.we.meet.data.settings.TimeRangeMode
import com.we.meet.data.settings.WORKING_HOURS_STEP_MIN
import com.we.meet.data.settings.isValidWorkingHours
import java.time.DayOfWeek
import java.time.format.TextStyle
import com.we.meet.ui.locale.appLocale

/**
 * P8 日历设置页(对标飞书日历设置的 we-meet 可落地子集,纯本地设置):
 * - 在消息列表提醒日程(与提醒页开关同一存储——入口被关后这里是固定的
 *   重开入口,解「关掉就找不到开关」死锁);
 * - 每周的第一天(周一/周日,作用于月网格与日视图日期条);
 * - 日程默认时长 / 默认提醒时间(作用于新建日程表单预设)。
 *
 * 这一页行数最多(十几行),所以是设置树里唯一带**分组标题**的页面:时区/工作时间/
 * 默认值/显示/提醒五段,不给标题就只剩一片卡片。版式仍走共享 `SettingsList` ——
 * 改之前这里是全设置树里唯一的"扁平原生列表"(没有卡片、行高 40dp 低于 48dp 热区、
 * 分隔线通栏、标题是主题色),与其它设置页完全两副面孔。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val store = app.settingsStore
    val reminderEntry by store.imReminderEntry.collectAsStateWithLifecycle()
    val weekStart by store.calendarWeekStart.collectAsStateWithLifecycle()
    val durationMin by store.calendarDefaultDurationMin.collectAsStateWithLifecycle()
    val reminderMin by store.calendarDefaultReminderMin.collectAsStateWithLifecycle()
    val dimPast by store.calendarDimPast.collectAsStateWithLifecycle()
    val workingHours by store.workingHours.collectAsStateWithLifecycle()
    val calendarTimeRangeMode by store.calendarTimeRangeMode.collectAsStateWithLifecycle()
    val meetingRoomTimeRangeMode by store.meetingRoomTimeRangeMode.collectAsStateWithLifecycle()
    val calendarTimezoneMode by store.calendarTimezoneMode.collectAsStateWithLifecycle()
    val calendarFixedTimezone by store.calendarFixedTimezone.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        store.synchronizeCalendarPreferences()
    }

    // 星期选项名跟应用内语言走,和选项文案本身同一种语言。
    val locale = appLocale()
    val dowLabel: (CalendarWeekStart) -> String = { ws ->
        val dow = if (ws == CalendarWeekStart.SUNDAY) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
        dow.getDisplayName(TextStyle.FULL, locale)
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.calendar_settings_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 时区 ────────────────────────────────────────────────────
            SettingsGroupHeader(stringResource(R.string.calendar_settings_group_timezone))
            SettingsGroup {
                PickerRow(
                    label = stringResource(R.string.calendar_settings_timezone_mode),
                    current = stringResource(
                        if (calendarTimezoneMode == CalendarTimezoneMode.AUTO) {
                            R.string.calendar_timezone_auto
                        } else {
                            R.string.calendar_timezone_fixed
                        },
                    ),
                    options = listOf(
                        stringResource(R.string.calendar_timezone_auto) to {
                            store.setCalendarTimezoneMode(CalendarTimezoneMode.AUTO)
                        },
                        stringResource(R.string.calendar_timezone_fixed) to {
                            store.setCalendarTimezoneMode(CalendarTimezoneMode.FIXED)
                        },
                    ),
                )
                // 固定时区只在选了「固定」之后才有意义(自动时它跟着设备走)。
                if (calendarTimezoneMode == CalendarTimezoneMode.FIXED) {
                    SettingsDivider()
                    PickerRow(
                        label = stringResource(R.string.calendar_settings_fixed_timezone),
                        current = calendarFixedTimezone,
                        options = calendarTimezoneOptions(calendarFixedTimezone).map { timezone ->
                            timezone to { store.setCalendarFixedTimezone(timezone) }
                        },
                    )
                }
                SettingsDivider()
                PickerRow(
                    label = stringResource(R.string.calendar_settings_week_start),
                    current = dowLabel(weekStart),
                    options = listOf(CalendarWeekStart.MONDAY, CalendarWeekStart.SUNDAY).map { ws ->
                        dowLabel(ws) to { store.setCalendarWeekStart(ws) }
                    },
                )
            }
            Spacer(Modifier.height(Dimens.SpaceL))

            // ── 工作时间 ────────────────────────────────────────────────
            SettingsGroupHeader(stringResource(R.string.calendar_settings_group_working_hours))
            SettingsGroup {
                MinutePickerRow(
                    label = stringResource(R.string.calendar_settings_working_start),
                    currentMin = workingHours.startMin,
                    // 只列得出合法值:开始必须早于结束。改之前是"列出来但点不动",
                    // 那是「看着能点、点了没反应」—— 不合格的选项不如不摆。
                    options = (0 until 24 * 60 step WORKING_HOURS_STEP_MIN)
                        .filter { isValidWorkingHours(it, workingHours.endMin) },
                    onSelect = { start -> store.setWorkingHours(start, workingHours.endMin) },
                )
                SettingsDivider()
                MinutePickerRow(
                    label = stringResource(R.string.calendar_settings_working_end),
                    currentMin = workingHours.endMin,
                    options = (WORKING_HOURS_STEP_MIN..24 * 60 step WORKING_HOURS_STEP_MIN)
                        .filter { isValidWorkingHours(workingHours.startMin, it) },
                    onSelect = { end -> store.setWorkingHours(workingHours.startMin, end) },
                )
            }
            SettingsHint(stringResource(R.string.calendar_settings_working_hours_hint))
            Spacer(Modifier.height(Dimens.SpaceL))

            // ── 默认值 ──────────────────────────────────────────────────
            SettingsGroupHeader(stringResource(R.string.calendar_settings_group_defaults))
            SettingsGroup {
                PickerRow(
                    label = stringResource(R.string.calendar_settings_default_duration),
                    current = stringResource(R.string.calendar_settings_duration_minutes, durationMin),
                    options = listOf(30, 60, 90).map { min ->
                        val label = stringResource(R.string.calendar_settings_duration_minutes, min)
                        label to { store.setCalendarDefaultDurationMin(min) }
                    },
                )
            }
            Spacer(Modifier.height(Dimens.SpaceL))

            // ── 显示 ────────────────────────────────────────────────────
            SettingsGroupHeader(stringResource(R.string.calendar_settings_group_display))
            SettingsGroup {
                PickerRow(
                    label = stringResource(R.string.calendar_settings_calendar_time_range),
                    current = stringResource(
                        if (calendarTimeRangeMode == TimeRangeMode.WORK) {
                            R.string.calendar_working_time
                        } else {
                            R.string.calendar_full_day_time
                        },
                    ),
                    options = listOf(
                        stringResource(R.string.calendar_working_time) to {
                            store.setCalendarTimeRangeMode(TimeRangeMode.WORK)
                        },
                        stringResource(R.string.calendar_full_day_time) to {
                            store.setCalendarTimeRangeMode(TimeRangeMode.FULL)
                        },
                    ),
                )
                SettingsDivider()
                PickerRow(
                    label = stringResource(R.string.calendar_settings_meeting_room_time_range),
                    current = stringResource(
                        if (meetingRoomTimeRangeMode == TimeRangeMode.WORK) {
                            R.string.calendar_working_time
                        } else {
                            R.string.calendar_full_day_time
                        },
                    ),
                    options = listOf(
                        stringResource(R.string.calendar_working_time) to {
                            store.setMeetingRoomTimeRangeMode(TimeRangeMode.WORK)
                        },
                        stringResource(R.string.calendar_full_day_time) to {
                            store.setMeetingRoomTimeRangeMode(TimeRangeMode.FULL)
                        },
                    ),
                )
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.calendar_settings_dim_past),
                    trailing = {
                        Switch(
                            checked = dimPast,
                            onCheckedChange = { store.setCalendarDimPast(it) },
                        )
                    },
                )
            }
            Spacer(Modifier.height(Dimens.SpaceL))

            // ── 提醒 ────────────────────────────────────────────────────
            SettingsGroupHeader(stringResource(R.string.calendar_settings_group_reminders))
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.calendar_settings_reminder_entry),
                    trailing = {
                        Switch(
                            checked = reminderEntry,
                            onCheckedChange = { store.setImReminderEntry(it) },
                        )
                    },
                )
                SettingsDivider()
                PickerRow(
                    label = stringResource(R.string.calendar_settings_default_reminder),
                    current = reminderLabel(reminderMin.takeIf { it >= 0 }),
                    options = (listOf<Int?>(null) + REMINDER_OPTIONS).map { min ->
                        reminderLabel(min) to { store.setCalendarDefaultReminderMin(min ?: -1) }
                    },
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }

}

private fun formatMinuteOfDay(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)

/**
 * 选择行:标签 + 当前值,点开底部弹层选一个。
 *
 * 弹层由 [SettingsPickerSheet] 统一提供(会自动滚到当前选中项),所以这里只剩
 * 「把选项文案和动作配成对」这一件事 —— 改之前每个选择项都要自己写一遍
 * `ModalBottomSheet` + `LazyColumn` + 勾选标记。
 */
@Composable
private fun PickerRow(
    label: String,
    current: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var picking by remember { mutableStateOf(false) }

    SettingsRow(
        label = label,
        value = current,
        onClick = { picking = true },
    )
    if (picking) {
        SettingsPickerSheet(
            title = label,
            options = options.map { (text, select) ->
                SettingsPickerOption(
                    label = text,
                    selected = text == current,
                    onSelect = select,
                )
            },
            onDismissRequest = { picking = false },
        )
    }
}

/** 时刻选择行:「12:30」这样的值,选项也按同一格式显示。 */
@Composable
private fun MinutePickerRow(
    label: String,
    currentMin: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    PickerRow(
        label = label,
        current = formatMinuteOfDay(currentMin),
        options = options.map { minute -> formatMinuteOfDay(minute) to { onSelect(minute) } },
    )
}
