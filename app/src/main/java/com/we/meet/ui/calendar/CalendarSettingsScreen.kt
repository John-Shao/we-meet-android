package com.we.meet.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.settings.CALENDAR_WEEK_VISIBLE_DAYS_RANGE
import com.we.meet.data.settings.CalendarWeekStart
import com.we.meet.data.settings.WORKING_HOURS_STEP_MIN
import com.we.meet.data.settings.isValidWorkingHours
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * P8 日历设置页(对标飞书日历设置的 we-meet 可落地子集,纯本地设置):
 * - 在消息列表提醒日程(与提醒页开关同一存储——入口被关后这里是固定的
 *   重开入口,解「关掉就找不到开关」死锁);
 * - 每周的第一天(周一/周日,作用于月网格与周视图);
 * - 日程默认时长 / 默认提醒时间(作用于新建日程表单预设)。
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
    val showWeekend by store.calendarShowWeekend.collectAsStateWithLifecycle()
    val weekVisibleDays by store.calendarWeekVisibleDays.collectAsStateWithLifecycle()
    val workingHours by store.workingHours.collectAsStateWithLifecycle()

    val locale = Locale.getDefault()
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            SettingRow(label = stringResource(R.string.calendar_settings_reminder_entry)) {
                Switch(
                    checked = reminderEntry,
                    onCheckedChange = { store.setImReminderEntry(it) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingDropdownRow(
                label = stringResource(R.string.calendar_settings_week_start),
                current = dowLabel(weekStart),
                options = listOf(CalendarWeekStart.MONDAY, CalendarWeekStart.SUNDAY).map { ws ->
                    dowLabel(ws) to { store.setCalendarWeekStart(ws) }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingRow(label = stringResource(R.string.calendar_settings_show_weekend)) {
                Switch(
                    checked = showWeekend,
                    onCheckedChange = { store.setCalendarShowWeekend(it) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 与上一条是两件事:上面决定周视图「有几列」(5 / 7),这条决定
            // 「一屏铺几列」——列数比它少时铺满不滚。
            SettingDropdownRow(
                label = stringResource(R.string.calendar_settings_week_visible_days),
                current = stringResource(
                    R.string.calendar_settings_week_days_value, weekVisibleDays,
                ),
                options = CALENDAR_WEEK_VISIBLE_DAYS_RANGE.map { days ->
                    val label =
                        stringResource(R.string.calendar_settings_week_days_value, days)
                    label to { store.setCalendarWeekVisibleDays(days) }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingDropdownRow(
                label = stringResource(R.string.calendar_settings_default_duration),
                current = stringResource(R.string.calendar_settings_duration_minutes, durationMin),
                options = listOf(30, 60, 90).map { min ->
                    val label = stringResource(R.string.calendar_settings_duration_minutes, min)
                    label to { store.setCalendarDefaultDurationMin(min) }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            WorkingTimeDropdownRow(
                label = stringResource(R.string.calendar_settings_working_start),
                currentMin = workingHours.startMin,
                options = (0 until 24 * 60 step WORKING_HOURS_STEP_MIN).toList(),
                enabled = { start -> isValidWorkingHours(start, workingHours.endMin) },
                onSelect = { start -> store.setWorkingHours(start, workingHours.endMin) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            WorkingTimeDropdownRow(
                label = stringResource(R.string.calendar_settings_working_end),
                currentMin = workingHours.endMin,
                options = (WORKING_HOURS_STEP_MIN..24 * 60 step WORKING_HOURS_STEP_MIN).toList(),
                enabled = { end -> isValidWorkingHours(workingHours.startMin, end) },
                onSelect = { end -> store.setWorkingHours(workingHours.startMin, end) },
            )
            Text(
                text = stringResource(R.string.calendar_settings_working_hours_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.SpaceS),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingDropdownRow(
                label = stringResource(R.string.calendar_settings_default_reminder),
                current = reminderLabel(reminderMin.takeIf { it >= 0 }),
                options = (listOf<Int?>(null) + REMINDER_OPTIONS).map { min ->
                    reminderLabel(min) to { store.setCalendarDefaultReminderMin(min ?: -1) }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingRow(label = stringResource(R.string.calendar_settings_dim_past)) {
                Switch(
                    checked = dimPast,
                    onCheckedChange = { store.setCalendarDimPast(it) },
                )
            }
        }
    }
}

private fun formatMinuteOfDay(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkingTimeDropdownRow(
    label: String,
    currentMin: Int,
    options: List<Int>,
    enabled: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingRow(label = label) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            ) {
                Text(formatMinuteOfDay(currentMin))
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                matchTextFieldWidth = false,
            ) {
                options.forEach { minute ->
                    DropdownMenuItem(
                        text = { Text(formatMinuteOfDay(minute), softWrap = false) },
                        enabled = enabled(minute),
                        onClick = {
                            onSelect(minute)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDropdownRow(
    label: String,
    current: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingRow(label = label) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            ) {
                Text(current)
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // 锚点是窄按钮，菜单需按内容自适应宽度，避免选项文字折行
                matchTextFieldWidth = false,
            ) {
                options.forEach { (text, select) ->
                    DropdownMenuItem(
                        text = { Text(text, softWrap = false) },
                        onClick = {
                            select()
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
