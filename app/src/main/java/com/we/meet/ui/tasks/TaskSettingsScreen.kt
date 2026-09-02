package com.we.meet.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

/**
 * Task preferences reached from both the system settings page and the task tab.
 * The caller supplies the HOME-scoped view model so changes are reflected in the
 * task list immediately after navigating back.
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
                transparent = true,
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
    var reminderMenu by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Dimens.SpaceL),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        SettingsCard {
            SettingsSwitchRow(
                title = stringResource(R.string.task_overdue_marker),
                subtitle = stringResource(R.string.task_overdue_marker_desc),
                checked = settings.overdueMarkerEnabled,
                enabled = !loading && !saving,
                onCheckedChange = onOverdueMarkerChange,
            )
        }
        SettingsCard {
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !loading && !saving) { reminderMenu = true }
                        .padding(Dimens.SpaceL),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.task_default_reminder),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.task_default_reminder_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (settings.dailyReminderEnabled) {
                            defaultTaskReminderText(settings.defaultReminderMinutes)
                        } else {
                            stringResource(R.string.task_reminder_none)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                }
                DropdownMenu(
                    expanded = reminderMenu,
                    onDismissRequest = { reminderMenu = false },
                ) {
                    (listOf<Int?>(null) + TASK_REMINDER_OPTIONS).forEach { minutes ->
                        val selected = if (minutes == null) {
                            !settings.dailyReminderEnabled
                        } else {
                            settings.dailyReminderEnabled &&
                                minutes == settings.defaultReminderMinutes
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    minutes?.let { defaultTaskReminderText(it) }
                                        ?: stringResource(R.string.task_reminder_none),
                                )
                            },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = {
                                reminderMenu = false
                                onDefaultReminderChange(minutes != null, minutes)
                            },
                        )
                    }
                }
            }
        }
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

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.SpaceL),
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(Dimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Dimens.SpaceXs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}
