package com.we.meet.ui.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val loadFailedText = stringResource(R.string.approval_load_error)
    val retryText = stringResource(R.string.approval_retry)

    // A submit made on the create screen should reflect on return.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    // 催办成功的一次性确认提示。
    LaunchedEffect(ui.urged) {
        if (ui.urged) {
            Toast.makeText(context, R.string.approval_urged, Toast.LENGTH_SHORT).show()
            vm.dismissUrged()
        }
    }

    // 审批/撤回/催办失败的一次性错误提示(此前 actionError 从未被消费)。
    LaunchedEffect(ui.actionError) {
        if (ui.actionError) {
            Toast.makeText(context, R.string.approval_action_failed, Toast.LENGTH_SHORT).show()
            vm.dismissActionError()
        }
    }

    LaunchedEffect(ui.current.error, ui.current.items.isNotEmpty(), ui.tab) {
        if (ui.current.error && ui.current.items.isNotEmpty()) {
            val result = snackbarHostState.showSnackbar(
                message = loadFailedText,
                actionLabel = retryText,
            )
            if (result == SnackbarResult.ActionPerformed) vm.refresh()
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.approval_title),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.approval_create))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        WeMeetLoading()
                    state.error && state.items.isEmpty() -> WeMeetErrorState(
                        onRetry = vm::refresh,
                        message = stringResource(R.string.approval_load_error),
                    )
                    state.items.isEmpty() -> WeMeetEmptyState(
                        title = stringResource(
                            if (ui.tab == ApprovalTab.Pending) {
                                R.string.approval_empty_pending
                            } else {
                                R.string.approval_empty_mine
                            },
                        ),
                        description = stringResource(
                            if (ui.tab == ApprovalTab.Pending) {
                                R.string.approval_empty_pending_description
                            } else {
                                R.string.approval_empty_mine_description
                            },
                        ),
                        icon = if (ui.tab == ApprovalTab.Pending) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.Add
                        },
                        action = if (ui.tab == ApprovalTab.Mine) {
                            {
                                Button(onClick = onCreate) {
                                    Text(stringResource(R.string.approval_create))
                                }
                            }
                        } else {
                            null
                        },
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.SpaceM),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
                    ) {
                        items(state.items, key = { it.id }) { inst ->
                            InstanceCard(
                                inst = inst,
                                tab = ui.tab,
                                acting = ui.actingId == inst.id,
                                onAct = { action, comment -> vm.act(inst.id, action, comment) },
                                onCancel = { vm.cancel(inst.id) },
                                onUrge = { vm.urge(inst.id) },
                            )
                        }
                        if (state.hasMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(Dimens.SpaceS), contentAlignment = Alignment.Center) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(Modifier.padding(Dimens.SpaceS))
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
    acting: Boolean,
    onAct: (action: String, comment: String) -> Unit,
    onCancel: () -> Unit,
    onUrge: () -> Unit,
) {
    Surface(
        tonalElevation = Dimens.ElevationSubtle,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.SpaceL)) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.SpaceXxs),
            )

            // Submitted form values.
            inst.formData.forEach { (k, v) ->
                Row(Modifier.padding(top = Dimens.SpaceXs)) {
                    Text(
                        text = "$k: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = v?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Approver chain — grouped by node (P5b: 会签/跳过/抄送).
            if (inst.tasks.isNotEmpty()) {
                ApproverChain(inst)
            }

            if (inst.status == "needs_assignment") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceS),
                ) {
                    Text(
                        text = stringResource(R.string.approval_needs_assignment_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(Dimens.SpaceS),
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
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceS),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    modifier = Modifier.padding(top = Dimens.SpaceS),
                ) {
                    Button(
                        onClick = { onAct("approved", comment) },
                        enabled = !acting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (acting) {
                            CircularProgressIndicator(
                                strokeWidth = Dimens.BorderEmphasis,
                                modifier = Modifier.size(Dimens.IconSmall),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.approval_act_approve))
                        }
                    }
                    OutlinedButton(
                        onClick = { onAct("rejected", comment) },
                        enabled = !acting,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.approval_act_reject)) }
                }
            }
            if (tab == ApprovalTab.Mine && inst.status == "pending") {
                var confirm by remember { mutableStateOf(false) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    modifier = Modifier.padding(top = Dimens.SpaceXs),
                ) {
                    TextButton(onClick = onUrge, enabled = !acting) {
                        Text(stringResource(R.string.approval_urge))
                    }
                    TextButton(onClick = { confirm = true }, enabled = !acting) {
                        Text(stringResource(R.string.approval_act_cancel))
                    }
                }
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

/**
 * 审批链时间线,按节点分组(P5b):单签一行;会签(≥2)显示「并签/或签 · N/M 已批」
 * + 各审批人;抄送显示「抄送:名单」;条件跳过显示「已跳过」。
 */
@Composable
private fun ApproverChain(inst: ApprovalInstanceDto) {
    val nodeIndexes = inst.tasks.map { it.nodeIndex }.distinct().sorted()
    Column(Modifier.padding(top = Dimens.SpaceS)) {
        nodeIndexes.forEach { idx ->
            val tasks = inst.tasks.filter { it.nodeIndex == idx }
            val cc = tasks.filter { it.kind == "cc" }
            val approvers = tasks.filter { it.kind == "approve" && it.approver != null }
            val skipped = tasks.any {
                it.kind == "approve" && it.approver == null && it.action == "skipped"
            }
            val active = idx == inst.currentNode && inst.status == "pending"
            val nodeLabel = stringResource(R.string.approval_node, idx + 1)
            val activeColor = if (active) {
                MaterialTheme.colorScheme.primary
            } else MaterialTheme.colorScheme.onSurfaceVariant
            val dim = MaterialTheme.colorScheme.outline

            when {
                cc.isNotEmpty() -> {
                    val names = cc
                        .mapNotNull { it.approver?.displayName?.takeIf { n -> n.isNotBlank() } }
                        .joinToString("、")
                    Text(
                        "$nodeLabel · ${stringResource(R.string.approval_cc)}:$names",
                        style = MaterialTheme.typography.labelMedium,
                        color = dim,
                    )
                }
                skipped -> Text(
                    "$nodeLabel:${stringResource(R.string.approval_skipped)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = dim,
                )
                approvers.size <= 1 -> {
                    val task = approvers.firstOrNull() ?: tasks.first()
                    val name = task.approver?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.approval_unassigned)
                    Text(
                        "${taskMark(task.action)} $nodeLabel $name" +
                            if (task.comment.isNotBlank()) " — ${task.comment}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = activeColor,
                    )
                }
                else -> {
                    val mode = inst.nodes.firstOrNull { it.index == idx }?.mode ?: "single"
                    val done = approvers.count { it.action == "approved" }
                    val head = stringResource(
                        if (mode == "or") R.string.approval_countersign_or
                        else R.string.approval_countersign_and,
                    )
                    Text(
                        "$nodeLabel · $head · " +
                            stringResource(R.string.approval_progress, done, approvers.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = activeColor,
                    )
                    approvers.forEach { task ->
                        val name = task.approver?.displayName?.takeIf { it.isNotBlank() }.orEmpty()
                        Text(
                            "    ${taskMark(task.action)} $name" +
                                if (task.comment.isNotBlank()) " — ${task.comment}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun taskMark(action: String): String = when (action) {
    "approved" -> "✓"
    "rejected" -> "✗"
    "skipped" -> "⊘"
    else -> "•"
}

@Composable
private fun StatusBadge(status: String) {
    val labelRes = approvalStatusLabelRes(status)
    val (bg, fg) = when (status) {
        "approved" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "rejected", "needs_assignment" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "cancelled" -> WeMeetTheme.extras.status.neutralContainer to
            WeMeetTheme.extras.status.onNeutralContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = if (labelRes != null) stringResource(labelRes) else status,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXxs),
        )
    }
}
