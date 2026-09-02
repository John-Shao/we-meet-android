package com.we.meet.ui.tasks

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    var pendingAttachment by remember {
        mutableStateOf<Pair<String, TaskAttachmentItem>?>(null)
    }
    val attachmentDownloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = pendingAttachment
        pendingAttachment = null
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            result.data?.data?.let { destination ->
                vm.downloadAttachment(pending.first, pending.second, destination)
            }
        }
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val task = ui.detail?.task
        if (uri != null && task != null) vm.uploadAttachment(task, uri)
    }
    LaunchedEffect(vm) { vm.refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl)
                .padding(bottom = Dimens.SpaceXl),
        ) {
            val detail = ui.detail
            if (detail != null) {
                ConversationTaskDetail(
                    detail = detail,
                    failed = ui.detailFailed,
                    actionRunning = ui.detailActionRunning,
                    actionFailure = ui.detailActionFailure,
                    statusMutating = detail.taskId in ui.mutatingIds,
                    deletingAttachmentIds = ui.deletingAttachmentIds,
                    onBack = vm::closeTask,
                    onRetry = vm::retryTask,
                    onOpenSubtask = vm::openTask,
                    onToggleFollowing = vm::toggleFollowing,
                    onSendComment = vm::sendComment,
                    onToggleCompleted = vm::toggleCompleted,
                    onAddAttachment = { attachmentPicker.launch(arrayOf("*/*")) },
                    onDeleteAttachment = vm::deleteAttachment,
                    onDownloadAttachment = { attachment ->
                        pendingAttachment = detail.taskId to attachment
                        attachmentDownloadPicker.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = attachment.mimeType?.takeIf(String::isNotBlank)
                                    ?: "application/octet-stream"
                                putExtra(Intent.EXTRA_TITLE, attachment.filename)
                            },
                        )
                    },
                )
            } else {
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
                                onOpen = { vm.openTask(task) },
                                onToggleCompleted = { vm.toggleCompleted(task) },
                            )
                        }
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
    onOpen: () -> Unit,
    onToggleCompleted: () -> Unit,
) {
    val done = task.status == TaskStatus.Done
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = Dimens.SpaceM),
        verticalAlignment = Alignment.Top,
    ) {
        if (mutating) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.IconMedium),
                strokeWidth = Dimens.BorderEmphasis,
            )
        } else {
            IconButton(
                onClick = onToggleCompleted,
                enabled = task.canUpdateStatus,
                modifier = Modifier.size(Dimens.MinTouchTarget),
            ) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = stringResource(
                        if (done) R.string.task_mark_incomplete else R.string.task_mark_complete,
                    ),
                    tint = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

@Composable
private fun ConversationTaskDetail(
    detail: TaskDetailItem,
    failed: Boolean,
    actionRunning: Boolean,
    actionFailure: TaskFailure?,
    statusMutating: Boolean,
    deletingAttachmentIds: Set<String>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenSubtask: (TaskItem) -> Unit,
    onToggleFollowing: (TaskItem) -> Unit,
    onSendComment: (TaskItem, String, () -> Unit) -> Unit,
    onToggleCompleted: (TaskItem) -> Unit,
    onAddAttachment: () -> Unit,
    onDeleteAttachment: (TaskItem, TaskAttachmentItem) -> Unit,
    onDownloadAttachment: (TaskAttachmentItem) -> Unit,
) {
    val task = detail.task
    var comment by remember(detail.taskId) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.task_back))
        }
        if (task != null) {
            if (statusMutating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.IconMedium),
                    strokeWidth = Dimens.BorderEmphasis,
                )
            } else {
                IconButton(
                    onClick = { onToggleCompleted(task) },
                    enabled = task.canUpdateStatus,
                ) {
                    Icon(
                        if (task.status == TaskStatus.Done) Icons.Filled.CheckCircle
                        else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = stringResource(
                            if (task.status == TaskStatus.Done) R.string.task_mark_incomplete
                            else R.string.task_mark_complete,
                        ),
                        tint = if (task.status == TaskStatus.Done) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(
            task?.title.orEmpty(),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (task != null) {
            IconButton(
                onClick = { onToggleFollowing(task) },
                enabled = !actionRunning,
            ) {
                Icon(
                    if (task.followed) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (task.followed) R.string.task_unfollow else R.string.task_follow,
                    ),
                    tint = if (task.followed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (detail.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            Text(stringResource(R.string.task_load_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.task_retry)) }
        }
    }
    actionFailure?.let { failure ->
        Text(
            stringResource(
                when (failure) {
                    TaskFailure.Comment -> R.string.task_comment_failed
                    TaskFailure.Attachment -> R.string.task_attachment_failed
                    else -> R.string.task_save_failed
                },
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (task != null) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = Dimens.Chat.SheetListMaxHeight),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            if (task.description.isNotBlank()) {
                item {
                    Text(
                        task.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                ConversationTaskDetailValue(R.string.task_assignee, task.assignee)
                ConversationTaskDetailValue(
                    R.string.task_start_date,
                    taskDateDisplayText(task.startDate),
                )
                ConversationTaskDetailValue(
                    R.string.task_due_date,
                    taskDateDisplayText(task.dueDate),
                )
                ConversationTaskDetailValue(
                    R.string.task_belongs_to_list,
                    task.listName.ifBlank { stringResource(R.string.task_standalone) },
                )
                ConversationTaskDetailValue(
                    R.string.task_belongs_to_group,
                    task.groupName ?: stringResource(R.string.task_ungrouped),
                )
                ConversationTaskDetailValue(R.string.task_priority, task.priority.label())
            }
            if (detail.subtasks.isNotEmpty()) {
                item { ConversationTaskSectionTitle(R.string.task_subtasks, detail.subtasks.size) }
                items(detail.subtasks, key = TaskItem::id) { subtask ->
                    val done = subtask.status == TaskStatus.Done
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onOpenSubtask(subtask)
                        }.padding(vertical = Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (done) Icons.Filled.CheckCircle
                            else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Text(
                            subtask.title,
                            modifier = Modifier.weight(1f),
                            textDecoration = TextDecoration.LineThrough.takeIf { done },
                        )
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (detail.attachments.isNotEmpty() || task.canManageAttachments) {
                item { ConversationTaskSectionTitle(R.string.task_attachments, detail.attachments.size) }
                items(detail.attachments, key = TaskAttachmentItem::id) { attachment ->
                    val downloading = attachment.id in detail.downloadingAttachmentIds
                    val deleting = attachment.id in deletingAttachmentIds
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !downloading && !deleting) {
                                onDownloadAttachment(attachment)
                            }
                            .padding(vertical = Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, null)
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Text(
                            attachment.filename,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (downloading || deleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.IconMedium),
                                strokeWidth = Dimens.BorderEmphasis,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Download,
                                stringResource(
                                    R.string.task_download_attachment,
                                    attachment.filename,
                                ),
                            )
                        }
                        if (task.canManageAttachments) {
                            IconButton(
                                onClick = { onDeleteAttachment(task, attachment) },
                                enabled = !downloading && !deleting,
                            ) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    stringResource(R.string.task_delete_attachment),
                                )
                            }
                        }
                    }
                }
                if (task.canManageAttachments) {
                    item {
                        OutlinedButton(
                            onClick = onAddAttachment,
                            enabled = !detail.uploadingAttachment,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (detail.uploadingAttachment) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(Dimens.IconMedium),
                                    strokeWidth = Dimens.BorderEmphasis,
                                )
                            } else {
                                Icon(Icons.Outlined.AttachFile, null)
                            }
                            Spacer(Modifier.width(Dimens.SpaceS))
                            Text(
                                stringResource(
                                    if (detail.uploadingAttachment) {
                                        R.string.task_uploading_attachment
                                    } else {
                                        R.string.task_add_attachment
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            if (detail.comments.isNotEmpty()) {
                item { ConversationTaskSectionTitle(R.string.task_comments, detail.comments.size) }
                items(detail.comments, key = TaskCommentItem::id) { comment ->
                    Column {
                        Text(comment.author, fontWeight = FontWeight.SemiBold)
                        Text(comment.content, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (task.canComment) {
                item {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.task_comment_hint)) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onSendComment(task, comment) { comment = "" }
                                },
                                enabled = comment.isNotBlank() && !actionRunning,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    stringResource(R.string.task_send),
                                )
                            }
                        },
                        enabled = !actionRunning,
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            }
            if (detail.activities.isNotEmpty()) {
                item { ConversationTaskSectionTitle(R.string.task_activity, detail.activities.size) }
                items(detail.activities, key = TaskActivityItem::id) { activity ->
                    Column {
                        Text(activity.actor, fontWeight = FontWeight.SemiBold)
                        Text(
                            activity.event.replace('_', ' '),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationTaskDetailValue(labelRes: Int, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            stringResource(labelRes),
            modifier = Modifier.weight(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(0.62f))
    }
}

@Composable
private fun ConversationTaskSectionTitle(labelRes: Int, count: Int) {
    HorizontalDivider()
    Text(
        "${stringResource(labelRes)} ($count)",
        modifier = Modifier.padding(top = Dimens.SpaceM),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun TaskPriority.label(): String = stringResource(
    when (this) {
        TaskPriority.None -> R.string.task_priority_none
        TaskPriority.Low -> R.string.task_priority_low
        TaskPriority.Medium -> R.string.task_priority_medium
        TaskPriority.High -> R.string.task_priority_high
        TaskPriority.Urgent -> R.string.task_priority_urgent
    },
)
