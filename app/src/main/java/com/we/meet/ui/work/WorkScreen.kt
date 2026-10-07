package com.we.meet.ui.work

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.delay

fun workStatus(status: String, local: Boolean): Int = when (status) {
    "queued" -> if (local) R.string.work_status_desktop_queued else R.string.work_status_queued
    "running" -> if (local) R.string.work_status_desktop_running else R.string.work_status_running
    "disconnected" -> R.string.work_status_disconnected
    "succeeded" -> R.string.work_status_succeeded
    "canceled" -> R.string.work_status_canceled
    "failed" -> R.string.work_status_failed
    else -> R.string.work_status_unknown
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkScreen(vm: WorkViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var goal by rememberSaveable { mutableStateOf(vm.pendingGoal) }
    var folder by rememberSaveable { mutableStateOf(vm.pendingWorkspace) }
    LaunchedEffect(ui.selected?.id, ui.tasks) {
        while (true) {
            delay(10000)
            if (ui.tasks.any { t -> t.runs.any { it.status in setOf("queued", "running", "disconnected") } } || ui.selected?.runs?.lastOrNull()?.status in setOf("queued", "running", "disconnected")) vm.refresh()
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        WeMeetTopBar(
            title = stringResource(R.string.work_title), onBack = onBack,
            actions = { TextButton(onClick = vm::refresh, enabled = !ui.loading && !ui.acting) { Text(stringResource(R.string.work_refresh)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.error.isNotEmpty()) Text(stringResource(R.string.work_request_failed), color = MaterialTheme.colorScheme.error)
            val task = ui.selected
            if (task != null) {
                TextButton(onClick = vm::closeDetail) { Text(stringResource(R.string.work_back_list)) }
                Text(task.goal, style = MaterialTheme.typography.titleLarge)
                val run = task.runs.lastOrNull()
                if (run != null) {
                    Text(stringResource(workStatus(run.status, run.executionTarget == "local")))
                    if (run.executionTarget == "local") Text(stringResource(R.string.work_execution_workspace, run.workspaceLabel))
                    if (run.status in setOf("queued", "running", "disconnected")) OutlinedButton(onClick = vm::cancel, enabled = !ui.acting) { Text(stringResource(R.string.work_cancel)) }
                    if (run.status == "succeeded" && ui.files.isEmpty()) Text(stringResource(R.string.work_local_results))
                    ui.files.forEach { file -> OutlinedButton(onClick = { vm.preview(file) }, enabled = !ui.acting) { Text(stringResource(R.string.work_view_file, file.name)) } }
                    if (ui.previewName.isNotEmpty()) {
                        Text(ui.previewName, style = MaterialTheme.typography.titleMedium)
                        Text(ui.preview, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                Text(stringResource(R.string.work_dispatch_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.work_dispatch_help))
                if (!ui.enabled && !ui.loading) Text(stringResource(R.string.work_remote_disabled))
                ui.workspaces.filter { it.enabled }.forEach { item ->
                    Row {
                        RadioButton(selected = folder == item.id, enabled = !ui.uncertain && !ui.acting, onClick = { folder = item.id })
                        Column { Text(item.label); Text(stringResource(R.string.work_device_state, item.deviceName, stringResource(if (item.online) R.string.work_online else R.string.work_offline)), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (ui.enabled && ui.workspaces.none { it.enabled }) Text(stringResource(R.string.work_no_workspace))
                OutlinedTextField(value = goal, onValueChange = { if (it.length <= 2000) goal = it }, enabled = !ui.uncertain && !ui.acting, label = { Text(stringResource(R.string.work_goal)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                if (ui.uncertain) Text(stringResource(R.string.work_dispatch_unknown))
                Button(onClick = { vm.dispatch(folder, goal) }, enabled = ui.enabled && !ui.acting && (ui.uncertain || (folder.isNotEmpty() && goal.isNotBlank()))) { Text(stringResource(if (ui.uncertain) R.string.work_confirm_request else R.string.work_dispatch)) }
                HorizontalDivider()
                Text(stringResource(R.string.work_my_tasks), style = MaterialTheme.typography.titleLarge)
                ui.tasks.forEach { entry ->
                    OutlinedButton(onClick = { vm.select(entry.id) }, enabled = !ui.acting, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(entry.goal)
                            entry.runs.lastOrNull()?.let {
                                Text(stringResource(workStatus(it.status, it.executionTarget == "local")), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (ui.hasMore) TextButton(onClick = vm::more, enabled = !ui.acting) { Text(stringResource(R.string.work_more)) }
            }
        }
    }
}
