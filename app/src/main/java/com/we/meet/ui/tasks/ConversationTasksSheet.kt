package com.we.meet.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationTasksSheet(
    app: WeMeetApp,
    conversationId: String,
    onDismiss: () -> Unit,
) {
    val vm: ConversationTasksViewModel = viewModel(
        key = "conversation-tasks-$conversationId",
        factory = ConversationTasksViewModel.Factory(app, conversationId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl)
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Dimens.SpaceM))
                Text(
                    stringResource(R.string.task_conversation_tasks),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceL))
            when {
                ui.loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                ui.failed -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
                ) {
                    Text(
                        stringResource(R.string.task_load_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = vm::refresh) {
                        Text(stringResource(R.string.task_retry))
                    }
                }
                ui.tasks.isEmpty() -> Text(
                    stringResource(R.string.task_conversation_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXl),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = Dimens.Chat.SheetListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                ) {
                    items(ui.tasks, key = TaskItem::id) { task ->
                        ConversationTaskRow(
                            task = task,
                            mutating = task.id in ui.mutatingIds,
                            onToggleCompleted = { vm.toggleCompleted(task) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationTaskRow(
    task: TaskItem,
    mutating: Boolean,
    onToggleCompleted: () -> Unit,
) {
    val done = task.status == TaskStatus.Done
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(
                enabled = task.canUpdateStatus && !mutating,
                onClick = onToggleCompleted,
            )
            .padding(vertical = Dimens.SpaceM),
        verticalAlignment = Alignment.Top,
    ) {
        if (mutating) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.IconMedium),
                strokeWidth = Dimens.BorderEmphasis,
            )
        } else {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = stringResource(
                    if (done) R.string.task_mark_incomplete else R.string.task_mark_complete,
                ),
                tint = if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Dimens.SpaceM))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.LineThrough.takeIf { done },
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Dimens.SpaceXs))
            Text(
                task.assignee,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.dueLabel.isNotBlank()) {
                Spacer(Modifier.height(Dimens.SpaceXs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        null,
                        modifier = Modifier.size(Dimens.IconSmall),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    Text(
                        task.dueLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
