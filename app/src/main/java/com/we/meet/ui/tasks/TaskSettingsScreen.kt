package com.we.meet.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.ui.components.SettingsGroup
import com.we.meet.ui.components.SettingsPickerOption
import com.we.meet.ui.components.SettingsPickerSheet
import com.we.meet.ui.components.SettingsRow
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

/**
 * Task preferences reached from both the system settings page and the task tab.
 * The caller supplies the HOME-scoped view model so changes are reflected in the
 * task list immediately after navigating back.
 *
 * 版式与设置总页同一套(共享 `SettingsList`)。改之前这一页与别处都不一样:
 * 底色是 `surfaceContainerLow`(别的设置页是页面 `background`)、卡片圆角借的是
 * **间距** token `SpaceL`(= 16dp,别的页是 `CornerM` 12dp)、行标题压了
 * `SemiBold`、箭头是 outlined 的 `ChevronRight`,行与行之间还差一档间距。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSettingsScreen(
    viewModel: TaskViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var backPending by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        if (!viewModel.ui.value.settingsLoading) viewModel.loadSettings()
    }
    val failureMessage = stringResource(R.string.task_settings_failed)
    LaunchedEffect(ui.failure, failureMessage) {
        if (ui.failure == TaskFailure.Settings) {
            snackbar.showSnackbar(message = failureMessage)
            viewModel.clearFailure()
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.task_settings),
                onBack = { if (!backPending) { backPending = true; onBack() } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        TaskSettingsContent(
            settings = ui.settings,
            loading = ui.settingsLoading,
            saving = ui.settingsSaving,
            onOverdueMarkerChange = viewModel::setOverdueMarker,
            onDefaultReminderChange = viewModel::setDefaultReminder,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun TaskSettingsContent(
    settings: TaskSettingsItem,
    loading: Boolean,
    saving: Boolean,
    onOverdueMarkerChange: (Boolean) -> Unit,
    onDefaultReminderChange: (Boolean, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingReminder by remember { mutableStateOf(false) }
    val busy = loading || saving

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(Dimens.SpaceL))

        SettingsGroup {
            SettingsRow(
                label = stringResource(R.string.task_overdue_marker),
                subtitle = stringResource(R.string.task_overdue_marker_desc),
                enabled = !busy,
                trailing = {
                    Switch(
                        checked = settings.overdueMarkerEnabled,
                        enabled = !busy,
                        onCheckedChange = onOverdueMarkerChange,
                    )
                },
            )
        }
        Spacer(Modifier.height(Dimens.SpaceL))

        SettingsGroup {
            SettingsRow(
                label = stringResource(R.string.task_default_reminder),
                subtitle = stringResource(R.string.task_default_reminder_desc),
                value = if (settings.dailyReminderEnabled) {
                    defaultTaskReminderText(settings.defaultReminderMinutes)
                } else {
                    stringResource(R.string.task_reminder_none)
                },
                enabled = !busy,
                onClick = { pickingReminder = true },
            )
        }
        Spacer(Modifier.height(Dimens.SpaceXl))
    }

    if (pickingReminder) {
        SettingsPickerSheet(
            title = stringResource(R.string.task_default_reminder),
            options = (listOf<Int?>(null) + TASK_REMINDER_OPTIONS).map { minutes ->
                SettingsPickerOption(
                    label = minutes?.let { defaultTaskReminderText(it) }
                        ?: stringResource(R.string.task_reminder_none),
                    selected = if (minutes == null) {
                        !settings.dailyReminderEnabled
                    } else {
                        settings.dailyReminderEnabled &&
                            minutes == settings.defaultReminderMinutes
                    },
                    onSelect = { onDefaultReminderChange(minutes != null, minutes) },
                )
            },
            onDismissRequest = { pickingReminder = false },
        )
    }
}

@Composable
private fun defaultTaskReminderText(minutes: Int): String = when (minutes) {
    TASK_REMINDER_DUE_DATE_1800 -> stringResource(R.string.task_reminder_due_date_1800)
    TASK_REMINDER_ONE_DAY_0900 -> stringResource(R.string.task_reminder_one_day_0900)
    TASK_REMINDER_TWO_DAYS_0900 -> stringResource(R.string.task_reminder_two_days_0900)
    TASK_REMINDER_THREE_DAYS_0900 -> stringResource(R.string.task_reminder_three_days_0900)
    else -> stringResource(R.string.task_reminder_due_date_0900)
}
