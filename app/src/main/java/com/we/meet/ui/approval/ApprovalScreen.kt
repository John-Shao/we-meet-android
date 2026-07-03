package com.we.meet.ui.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.data.api.dto.ApprovalInstanceDto

/** 审批 main screen (route `approval`): two tabs, paged lists, inline act/cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    val vm: ApprovalViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    // A submit made on the create screen should reflect on return.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.approval_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.approval_create))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = ui.tab.ordinal) {
                Tab(
                    selected = ui.tab == ApprovalTab.Pending,
                    onClick = { vm.selectTab(ApprovalTab.Pending) },
                    text = {
                        BadgedBox(badge = {
                            if (ui.pendingCount > 0) Badge { Text("${ui.pendingCount}") }
                        }) {
                            Text(stringResource(R.string.approval_tab_pending))
                        }
                    },
                )
                Tab(
                    selected = ui.tab == ApprovalTab.Mine,
                    onClick = { vm.selectTab(ApprovalTab.Mine) },
                    text = { Text(stringResource(R.string.approval_tab_mine)) },
                )
            }

            val state = ui.current
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.items.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error && state.items.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.approval_load_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { vm.refresh() }) {
                            Text(stringResource(R.string.approval_retry))
                        }
                    }
                    state.items.isEmpty() -> Text(
                        stringResource(R.string.approval_empty),
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.items, key = { it.id }) { inst ->
                            InstanceCard(
                                inst = inst,
                                tab = ui.tab,
                                onAct = { action, comment -> vm.act(inst.id, action, comment) },
                                onCancel = { vm.cancel(inst.id) },
                            )
                        }
                        if (state.hasMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(Modifier.padding(8.dp))
                                    } else {
                                        OutlinedButton(onClick = { vm.loadMore() }) {
                                            Text(stringResource(R.string.approval_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(
    inst: ApprovalInstanceDto,
    tab: ApprovalTab,
    onAct: (action: String, comment: String) -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = inst.templateName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(inst.status)
            }
            Text(
                text = "${inst.applicant?.displayName.orEmpty()} · ${inst.createdAt?.take(10).orEmpty()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )

            // Submitted form values.
            inst.formData.forEach { (k, v) ->
                Row(Modifier.padding(top = 6.dp)) {
                    Text(
                        text = "$k: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = v?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Approver chain.
            if (inst.tasks.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp)) {
                    inst.tasks.forEach { task ->
                        val name = task.approver?.displayName?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.approval_unassigned)
                        val mark = when (task.action) {
                            "approved" -> "✓"
                            "rejected" -> "✗"
                            else -> "•"
                        }
                        Text(
                            text = "$mark ${stringResource(R.string.approval_node, task.nodeIndex + 1)} $name" +
                                if (task.comment.isNotBlank()) " — ${task.comment}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (task.nodeIndex == inst.currentNode && inst.status == "pending") {
                                MaterialTheme.colorScheme.primary
                            } else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (inst.status == "needs_assignment") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.approval_needs_assignment_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // Actions.
            if (tab == ApprovalTab.Pending && inst.status == "pending") {
                var comment by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    placeholder = { Text(stringResource(R.string.approval_comment_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Button(
                        onClick = { onAct("approved", comment) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.approval_act_approve)) }
                    OutlinedButton(
                        onClick = { onAct("rejected", comment) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.approval_act_reject)) }
                }
            }
            if (tab == ApprovalTab.Mine && inst.status == "pending") {
                var confirm by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { confirm = true },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(stringResource(R.string.approval_act_cancel)) }
                if (confirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirm = false },
                        confirmButton = {
                            TextButton(onClick = { confirm = false; onCancel() }) {
                                Text(stringResource(R.string.approval_act_cancel))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirm = false }) {
                                Text(stringResource(R.string.approval_dialog_dismiss))
                            }
                        },
                        text = { Text(stringResource(R.string.approval_cancel_confirm)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val labelRes = approvalStatusLabelRes(status)
    val (bg, fg) = when (status) {
        "approved" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "rejected", "needs_assignment" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "cancelled" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = if (labelRes != null) stringResource(labelRes) else status,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
