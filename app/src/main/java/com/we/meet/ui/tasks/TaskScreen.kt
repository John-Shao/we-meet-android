@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.we.meet.ui.tasks

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.BuildConfig
import com.we.meet.WeMeetApp
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.ui.chat.ForwardCreateGroupFlow
import com.we.meet.feature.im.ui.chat.ForwardPicker
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

private enum class TaskPage { List, Create, Detail, Search, Settings }

private data class TaskGroupInsertion(val list: TaskListItem, val index: Int)

private data class TaskGroupEditTarget(val list: TaskListItem, val group: TaskGroupItem)

private data class TaskParentMove(
    val task: TaskItem,
    val parentId: String?,
    val subtreeNodeCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(ownerName: String, app: WeMeetApp) {
    val owner = ownerName.ifBlank { stringResource(R.string.task_demo_owner) }
    val vm: TaskViewModel = viewModel(factory = TaskViewModel.Factory(app))
    val ui by vm.ui.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(TaskPage.List) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var detailBackStack by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var showDrawer by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var showNewList by remember { mutableStateOf(false) }
    var showArchivedLists by remember { mutableStateOf(false) }
    var groupActionTarget by remember { mutableStateOf<TaskListGroupItem?>(null) }
    var listActionTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var shareListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var memberPickerListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var renameGroupTarget by remember { mutableStateOf<TaskListGroupItem?>(null) }
    var renameListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<TaskListGroupItem?>(null) }
    var deleteListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var archiveListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var leaveListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var editContentTarget by remember { mutableStateOf<TaskItem?>(null) }
    var dueDateTarget by remember { mutableStateOf<TaskItem?>(null) }
    var priorityTarget by remember { mutableStateOf<TaskItem?>(null) }
    var recurrenceTarget by remember { mutableStateOf<TaskItem?>(null) }
    var parentPickerTarget by remember { mutableStateOf<TaskItem?>(null) }
    var pendingParentMove by remember { mutableStateOf<TaskParentMove?>(null) }
    var assigneeTarget by remember { mutableStateOf<TaskItem?>(null) }
    var followerTarget by remember { mutableStateOf<TaskItem?>(null) }
    var showNewSubtask by remember { mutableStateOf(false) }
    var subtaskActionTarget by remember { mutableStateOf<TaskItem?>(null) }
    var shareTarget by remember { mutableStateOf<TaskItem?>(null) }
    var showShareGroup by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<TaskItem?>(null) }
    var sectionMenu by remember { mutableStateOf<TaskGroupItem?>(null) }
    var taskGroupInsertion by remember { mutableStateOf<TaskGroupInsertion?>(null) }
    var renameTaskGroupTarget by remember { mutableStateOf<TaskGroupEditTarget?>(null) }
    var deleteTaskGroupTarget by remember { mutableStateOf<TaskGroupEditTarget?>(null) }
    var orderTaskGroupsFor by remember { mutableStateOf<TaskListItem?>(null) }
    var pendingAttachmentDownload by remember { mutableStateOf<TaskAttachmentItem?>(null) }
    val selectedTask = ui.detail?.task?.takeIf { it.id == selectedTaskId }
        ?: (ui.tasks + ui.searchResults).firstOrNull { it.id == selectedTaskId }
        ?: ui.detail?.subtasks?.firstOrNull { it.id == selectedTaskId }
        ?: detailBackStack.lastOrNull { it.id == selectedTaskId }
    val snackbar = remember { SnackbarHostState() }
    val imSession = remember(app) { ImSession.get(app) }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val task = selectedTask
        if (uri != null && task != null) vm.uploadAttachment(task, uri)
    }
    val attachmentDownloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val attachment = pendingAttachmentDownload
        pendingAttachmentDownload = null
        if (result.resultCode == Activity.RESULT_OK && attachment != null) {
            result.data?.data?.let { uri -> vm.downloadAttachment(attachment, uri) }
        }
    }
    val failureText = when (ui.failure) {
        TaskFailure.Load -> stringResource(R.string.task_load_failed)
        TaskFailure.Save -> stringResource(R.string.task_save_failed)
        TaskFailure.Delete -> stringResource(R.string.task_delete_failed)
        TaskFailure.Comment -> stringResource(R.string.task_comment_failed)
        TaskFailure.Attachment -> stringResource(R.string.task_attachment_failed)
        TaskFailure.Share -> stringResource(R.string.task_share_failed)
        TaskFailure.Navigation -> stringResource(R.string.task_navigation_update_failed)
        null -> ""
    }
    val retryText = stringResource(R.string.task_retry)
    LaunchedEffect(ui.failure) {
        if (ui.failure != null) {
            val failedOperation = ui.failure
            val result = snackbar.showSnackbar(
                message = failureText,
                actionLabel = retryText.takeIf { failedOperation == TaskFailure.Load },
            )
            vm.clearFailure()
            if (failedOperation == TaskFailure.Load && result == SnackbarResult.ActionPerformed) {
                vm.refresh()
            }
        }
    }
    LaunchedEffect(page, selectedTaskId) {
        if (page == TaskPage.Detail) selectedTaskId?.let(vm::loadDetail)
    }

    Box(Modifier.fillMaxSize()) {
        when (page) {
            TaskPage.List -> TaskListPage(
                tasks = ui.tasks,
                view = ui.view,
                selectedList = ui.selectedList,
                includeDone = ui.includeDone,
                loading = ui.loading,
                owner = owner,
                onViewChange = vm::setView,
                onOpenDrawer = {
                    vm.refreshNavigation()
                    showDrawer = true
                },
                onSearch = { page = TaskPage.Search },
                onSettings = { page = TaskPage.Settings },
                onFilter = { showFilter = true },
                onCreate = { page = TaskPage.Create },
                onTaskClick = {
                    selectedTaskId = it.id
                    detailBackStack = listOf(it)
                    page = TaskPage.Detail
                },
                onToggleDone = vm::toggleCompleted,
                onTaskAction = { actionTarget = it },
                onSectionAction = { sectionMenu = it },
                onNewTaskGroup = { list ->
                    taskGroupInsertion = TaskGroupInsertion(list, list.groups.size)
                },
            )

            TaskPage.Create -> CreateTaskPage(
                owner = owner,
                taskLists = ui.taskLists.filter(TaskListItem::canCreateTasks),
                creating = ui.creating,
                onClose = { page = TaskPage.List },
                onCreate = { title, description, dueDate, listId ->
                    vm.createTask(title, description, dueDate, listId) { created ->
                        selectedTaskId = created.id
                        detailBackStack = listOf(created)
                        page = TaskPage.Detail
                    }
                },
            )

            TaskPage.Detail -> selectedTask?.let { task ->
                TaskDetailPage(
                    task = task,
                    detail = ui.detail?.takeIf { it.taskId == task.id },
                    onBack = {
                        if (detailBackStack.size > 1) {
                            detailBackStack = detailBackStack.dropLast(1)
                            selectedTaskId = detailBackStack.last().id
                        } else {
                            detailBackStack = emptyList()
                            page = TaskPage.List
                        }
                    },
                    onToggleDone = vm::toggleCompleted,
                    onToggleFollow = vm::toggleFollowing,
                    onSendComment = { current, content, onSent ->
                        vm.sendComment(current, content, onSent)
                    },
                    onAddSubtask = { showNewSubtask = true },
                    onToggleSubtask = vm::toggleCompleted,
                    onOpenSubtask = { subtask ->
                        detailBackStack = detailBackStack + subtask
                        selectedTaskId = subtask.id
                    },
                    onSubtaskAction = { subtaskActionTarget = it },
                    onEditContent = { editContentTarget = task },
                    onEditDueDate = { dueDateTarget = task },
                    onEditPriority = { priorityTarget = task },
                    canManageRecurrence = task.recurrence?.canManage
                        ?: (task.creatorId == app.tokenStore.userId),
                    onEditRecurrence = { recurrenceTarget = task },
                    canEditParent = task.canEdit && task.recurrence?.active != true,
                    onEditParent = { parentPickerTarget = task },
                    onEditAssignees = { assigneeTarget = task },
                    onAddFollowers = { followerTarget = task },
                    onRemoveFollower = { follower -> vm.removeFollower(task, follower.id) },
                    onAddAttachment = { attachmentPicker.launch(arrayOf("*/*")) },
                    onDownloadAttachment = { attachment ->
                        pendingAttachmentDownload = attachment
                        attachmentDownloadPicker.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = attachment.mimeType?.takeIf(String::isNotBlank)
                                    ?: "application/octet-stream"
                                putExtra(Intent.EXTRA_TITLE, attachment.filename)
                            },
                        )
                    },
                    onDeleteAttachment = { attachment ->
                        vm.deleteAttachment(task.id, attachment.id)
                    },
                    onShare = { shareTarget = task },
                    onMore = { actionTarget = it },
                )
            } ?: run { page = TaskPage.List }

            TaskPage.Search -> TaskSearchPage(
                tasks = ui.searchResults,
                searching = ui.searching,
                query = ui.searchQuery,
                filter = ui.searchFilter,
                canFilterSelf = !app.tokenStore.userId.isNullOrBlank(),
                onBack = { page = TaskPage.List },
                onQueryChange = vm::search,
                onFilterChange = vm::setSearchFilter,
                onTaskClick = {
                    selectedTaskId = it.id
                    detailBackStack = listOf(it)
                    page = TaskPage.Detail
                },
            )

            TaskPage.Settings -> TaskSettingsPage(onBack = { page = TaskPage.List })
        }

        AnimatedVisibility(
            visible = showDrawer && page == TaskPage.List,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TaskNavigationDrawer(
                selectedList = ui.selectedList?.name,
                taskLists = ui.taskLists,
                listGroups = ui.listGroups,
                assignedCount = ui.navigationCounts.assigned,
                followingCount = ui.navigationCounts.following,
                createdCount = ui.navigationCounts.created,
                allCount = ui.navigationCounts.all,
                completedCount = ui.navigationCounts.completed,
                standaloneCount = ui.navigationCounts.standalone,
                onDismiss = { showDrawer = false },
                onSelectView = {
                    vm.setView(it)
                    showDrawer = false
                },
                onSelectList = { list ->
                    vm.selectList(list.id)
                    showDrawer = false
                },
                onNewGroup = { showNewGroup = true },
                onNewList = { showNewList = true },
                onOpenArchivedLists = {
                    showDrawer = false
                    showArchivedLists = true
                    vm.loadArchivedTaskLists()
                },
                onGroupAction = { groupActionTarget = it },
                onListAction = { listActionTarget = it },
            )
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showFilter) {
        FilterSheet(
            includeDone = ui.includeDone,
            onIncludeDoneChange = vm::setIncludeDone,
            onDismiss = { showFilter = false },
        )
    }

    actionTarget?.let { target ->
        TaskActionSheet(
            task = target,
            onDismiss = { actionTarget = null },
            onShare = {
                actionTarget = null
                shareTarget = target
            },
            onDelete = {
                vm.deleteTask(target) {
                    actionTarget = null
                    if (page == TaskPage.Detail) {
                        detailBackStack = emptyList()
                        page = TaskPage.List
                    }
                }
            },
        )
    }

    subtaskActionTarget?.let { subtask ->
        val parent = selectedTask
        val subtasks = ui.detail?.takeIf { it.taskId == parent?.id }?.subtasks.orEmpty()
        val index = subtasks.indexOfFirst { it.id == subtask.id }
        SubtaskActionSheet(
            task = subtask,
            moving = parent?.id?.let { it in ui.mutatingIds } == true,
            canMoveUp = index > 0,
            canMoveDown = index >= 0 && index < subtasks.lastIndex,
            onDismiss = { subtaskActionTarget = null },
            onMoveUp = {
                parent?.let { vm.moveSubtask(it, subtask.id, -1) }
                subtaskActionTarget = null
            },
            onMoveDown = {
                parent?.let { vm.moveSubtask(it, subtask.id, 1) }
                subtaskActionTarget = null
            },
        )
    }

    sectionMenu?.let { group ->
        ui.selectedList?.takeIf(TaskListItem::canManage)?.let { list ->
            val ordered = list.groups.sortedBy(TaskGroupItem::sortOrder)
            val index = ordered.indexOfFirst { it.id == group.id }.coerceAtLeast(0)
            SectionActionSheet(
                section = group.name,
                canDelete = group.canDelete,
                onDismiss = { sectionMenu = null },
                onRename = {
                    sectionMenu = null
                    renameTaskGroupTarget = TaskGroupEditTarget(list, group)
                },
                onCreateAbove = {
                    sectionMenu = null
                    taskGroupInsertion = TaskGroupInsertion(list, index)
                },
                onCreateBelow = {
                    sectionMenu = null
                    taskGroupInsertion = TaskGroupInsertion(list, index + 1)
                },
                onManageOrder = {
                    sectionMenu = null
                    orderTaskGroupsFor = list
                },
                onDelete = {
                    sectionMenu = null
                    deleteTaskGroupTarget = TaskGroupEditTarget(list, group)
                },
            )
        }
    }
    taskGroupInsertion?.let { target ->
        NewGroupDialog(
            saving = ui.navigationMutating,
            onDismiss = { taskGroupInsertion = null },
            onCreate = { name ->
                vm.createTaskGroup(target.list, name, target.index) {
                    taskGroupInsertion = null
                }
            },
        )
    }
    renameTaskGroupTarget?.let { target ->
        RenameNavigationDialog(
            initialName = target.group.name,
            saving = ui.navigationMutating,
            onDismiss = { renameTaskGroupTarget = null },
            onConfirm = { name ->
                vm.renameTaskGroup(target.list, target.group, name)
                renameTaskGroupTarget = null
            },
        )
    }
    deleteTaskGroupTarget?.let { target ->
        DeleteNavigationDialog(
            message = stringResource(R.string.task_delete_task_group_confirm, target.group.name),
            deleting = ui.navigationMutating,
            onDismiss = { deleteTaskGroupTarget = null },
            onConfirm = {
                vm.deleteTaskGroup(target.list, target.group)
                deleteTaskGroupTarget = null
            },
        )
    }
    orderTaskGroupsFor?.let { list ->
        TaskGroupOrderDialog(
            groups = list.groups,
            saving = ui.navigationMutating,
            onDismiss = { orderTaskGroupsFor = null },
            onSave = { ordered ->
                vm.reorderTaskGroups(list, ordered)
                orderTaskGroupsFor = null
            },
        )
    }

    if (showNewGroup) {
        NewGroupDialog(
            saving = ui.navigationMutating,
            onDismiss = { showNewGroup = false },
            onCreate = {
                vm.createListGroup(it) { showNewGroup = false }
            },
        )
    }
    if (showNewList) {
        NewTaskListDialog(
            groups = ui.listGroups,
            saving = ui.navigationMutating,
            onDismiss = { showNewList = false },
            onCreate = { name, groupId ->
                vm.createTaskList(name, groupId) { showNewList = false }
            },
        )
    }
    groupActionTarget?.let { group ->
        NavigationActionSheet(
            title = group.name,
            canDelete = group.canManage,
            onDismiss = { groupActionTarget = null },
            onRename = {
                groupActionTarget = null
                renameGroupTarget = group
            },
            onDelete = {
                groupActionTarget = null
                deleteGroupTarget = group
            },
        )
    }
    listActionTarget?.let { list ->
        NavigationActionSheet(
            title = list.name,
            canDelete = list.canDelete,
            onDismiss = { listActionTarget = null },
            onRename = if (list.canManage) {
                {
                    listActionTarget = null
                    renameListTarget = list
                }
            } else {
                null
            },
            onShare = if (list.canShare) {
                {
                    listActionTarget = null
                    shareListTarget = list
                    vm.loadTaskListMembers(list)
                }
            } else {
                null
            },
            onArchive = if (list.canArchive) {
                {
                    listActionTarget = null
                    archiveListTarget = list
                }
            } else {
                null
            },
            onLeave = if (list.canRemove) {
                {
                    listActionTarget = null
                    leaveListTarget = list
                }
            } else {
                null
            },
            onDelete = {
                listActionTarget = null
                deleteListTarget = list
            },
        )
    }
    archiveListTarget?.let { list ->
        ArchiveTaskListDialog(
            listName = list.name,
            saving = ui.navigationMutating,
            onDismiss = { archiveListTarget = null },
            onConfirm = {
                vm.archiveTaskList(list)
                archiveListTarget = null
            },
        )
    }
    if (showArchivedLists) {
        ArchivedTaskListsSheet(
            lists = ui.archivedTaskLists,
            loading = ui.archivedListsLoading,
            restoring = ui.navigationMutating,
            onDismiss = { showArchivedLists = false },
            onRestore = vm::restoreTaskList,
        )
    }
    shareListTarget?.takeIf { memberPickerListTarget == null }?.let { list ->
        TaskListSharingSheet(
            list = list,
            members = ui.taskListMembers.takeIf { ui.taskListMembersFor == list.id }.orEmpty(),
            loading = ui.taskListMembersLoading,
            mutating = ui.navigationMutating,
            onDismiss = { shareListTarget = null },
            onAddMember = { memberPickerListTarget = list },
            onRoleChange = { member, role ->
                vm.updateTaskListMemberRole(list, member, role)
            },
            onRemove = { member -> vm.removeTaskListMember(list, member) },
        )
    }
    memberPickerListTarget?.let { list ->
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Single,
            enabled = !ui.navigationMutating,
            excludeSelf = true,
            excludeUserIds = ui.taskListMembers.mapTo(mutableSetOf()) { it.userId },
            onConfirm = { picked ->
                picked.firstOrNull()?.let { vm.addTaskListMember(list, it.userId) }
                memberPickerListTarget = null
            },
            onDismiss = { memberPickerListTarget = null },
        )
    }
    leaveListTarget?.let { list ->
        LeaveTaskListDialog(
            listName = list.name,
            leaving = ui.navigationMutating,
            onDismiss = { leaveListTarget = null },
            onConfirm = {
                vm.leaveTaskList(list)
                leaveListTarget = null
            },
        )
    }
    renameGroupTarget?.let { group ->
        RenameNavigationDialog(
            initialName = group.name,
            saving = ui.navigationMutating,
            onDismiss = { renameGroupTarget = null },
            onConfirm = { name ->
                vm.renameListGroup(group, name)
                renameGroupTarget = null
            },
        )
    }
    renameListTarget?.let { list ->
        RenameNavigationDialog(
            initialName = list.name,
            saving = ui.navigationMutating,
            onDismiss = { renameListTarget = null },
            onConfirm = { name ->
                vm.renameTaskList(list, name)
                renameListTarget = null
            },
        )
    }
    deleteGroupTarget?.let { group ->
        DeleteNavigationDialog(
            message = stringResource(R.string.task_delete_group_confirm, group.name),
            deleting = ui.navigationMutating,
            onDismiss = { deleteGroupTarget = null },
            onConfirm = {
                vm.deleteListGroup(group)
                deleteGroupTarget = null
            },
        )
    }
    deleteListTarget?.let { list ->
        DeleteNavigationDialog(
            message = stringResource(R.string.task_delete_list_confirm, list.name),
            deleting = ui.navigationMutating,
            onDismiss = { deleteListTarget = null },
            onConfirm = {
                vm.deleteTaskList(list)
                deleteListTarget = null
            },
        )
    }
    editContentTarget?.let { task ->
        EditTaskContentDialog(
            task = task,
            saving = task.id in ui.mutatingIds,
            onDismiss = { editContentTarget = null },
            onConfirm = { title, description ->
                vm.updateContent(task, title, description)
                editContentTarget = null
            },
        )
    }
    dueDateTarget?.let { task ->
        TaskDueDateDialog(
            initialDate = task.dueDate,
            onDismiss = { dueDateTarget = null },
            onConfirm = { date ->
                vm.updateDueDate(task, date)
                dueDateTarget = null
            },
        )
    }
    priorityTarget?.let { task ->
        TaskPrioritySheet(
            selected = task.priority,
            onDismiss = { priorityTarget = null },
            onSelect = { priority ->
                vm.updatePriority(task, priority)
                priorityTarget = null
            },
        )
    }
    recurrenceTarget?.let { task ->
        TaskRecurrenceSheet(
            task = task,
            saving = task.id in ui.mutatingIds,
            onDismiss = { recurrenceTarget = null },
            onSelect = { frequency ->
                vm.setRecurrence(task, frequency)
                recurrenceTarget = null
            },
        )
    }
    parentPickerTarget?.let { task ->
        val detail = ui.detail?.takeIf { it.taskId == task.id }
        TaskParentSheet(
            task = task,
            candidates = detail?.parentCandidates.orEmpty(),
            saving = task.id in ui.mutatingIds,
            onDismiss = { parentPickerTarget = null },
            onSelect = { parentId ->
                parentPickerTarget = null
                val move = TaskParentMove(
                    task = task,
                    parentId = parentId,
                    subtreeNodeCount = detail?.subtreeNodeCount ?: 1,
                )
                if (move.subtreeNodeCount > 1) {
                    pendingParentMove = move
                } else {
                    vm.moveTask(move.task, move.parentId, move.subtreeNodeCount)
                }
            },
        )
    }
    pendingParentMove?.let { move ->
        MoveTaskTreeDialog(
            nodeCount = move.subtreeNodeCount,
            moving = move.task.id in ui.mutatingIds,
            onDismiss = { pendingParentMove = null },
            onConfirm = {
                vm.moveTask(move.task, move.parentId, move.subtreeNodeCount)
                pendingParentMove = null
            },
        )
    }
    assigneeTarget?.let { task ->
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Multi,
            enabled = task.id !in ui.mutatingIds,
            excludeSelf = false,
            preselectUserIds = task.assignees.mapTo(mutableSetOf()) { it.id },
            onConfirm = { picked ->
                vm.updateAssignees(task, picked.map { it.userId })
                assigneeTarget = null
            },
            onDismiss = { assigneeTarget = null },
        )
    }
    followerTarget?.let { task ->
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Multi,
            enabled = task.id !in ui.mutatingIds,
            excludeSelf = false,
            excludeUserIds = task.followers.mapTo(mutableSetOf()) { it.id },
            onConfirm = { picked ->
                vm.addFollowers(task, picked.map { it.userId })
                followerTarget = null
            },
            onDismiss = { followerTarget = null },
        )
    }
    if (showNewSubtask && selectedTask != null) {
        NewSubtaskDialog(
            onDismiss = { showNewSubtask = false },
            onCreate = { title ->
                vm.createSubtask(selectedTask, title)
                showNewSubtask = false
            },
        )
    }
    shareTarget?.let { task ->
        val cardTitle = stringResource(R.string.task_share_card_title)
        val assigneeLabel = stringResource(R.string.task_assignee)
        val dueLabel = stringResource(R.string.task_due_time)
        val followLabel = stringResource(R.string.task_follow)
        val viewLabel = stringResource(R.string.task_view_details)
        ForwardPicker(
            deps = app,
            targets = imSession.allForwardTargets(),
            onForward = { cids ->
                vm.shareTask(task, cids) { granted ->
                    granted.forEach { cid ->
                        imSession.sendMessageAsync(
                            cid,
                            buildTaskCardBody(
                                task,
                                cid,
                                cardTitle,
                                assigneeLabel,
                                dueLabel,
                                followLabel,
                                viewLabel,
                            ),
                            "rich-card",
                        )
                    }
                    shareTarget = null
                }
            },
            onCreateGroupForward = { showShareGroup = true },
            onDismiss = { shareTarget = null },
        )
        if (showShareGroup) {
            ForwardCreateGroupFlow(
                deps = app,
                onCreated = { cid ->
                    vm.shareTask(task, listOf(cid)) { granted ->
                        granted.forEach { grantedCid ->
                            imSession.sendMessageAsync(
                                grantedCid,
                                buildTaskCardBody(
                                    task,
                                    grantedCid,
                                    cardTitle,
                                    assigneeLabel,
                                    dueLabel,
                                    followLabel,
                                    viewLabel,
                                ),
                                "rich-card",
                            )
                        }
                        showShareGroup = false
                        shareTarget = null
                    }
                },
                onCancel = { showShareGroup = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskListPage(
    tasks: List<TaskItem>,
    view: TaskView,
    selectedList: TaskListItem?,
    includeDone: Boolean,
    loading: Boolean,
    owner: String,
    onViewChange: (TaskView) -> Unit,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onFilter: () -> Unit,
    onCreate: () -> Unit,
    onTaskClick: (TaskItem) -> Unit,
    onToggleDone: (TaskItem) -> Unit,
    onTaskAction: (TaskItem) -> Unit,
    onSectionAction: (TaskGroupItem) -> Unit,
    onNewTaskGroup: (TaskListItem) -> Unit,
) {
    val visible = tasks.visibleFor(view, TaskFilter(includeDone = includeDone), selectedList?.name)
    val standaloneLabel = stringResource(R.string.task_standalone)
    val sections = if (view == TaskView.Standalone) {
        listOf(TaskDisplaySection(standaloneLabel, visible))
    } else if (selectedList == null) {
        visible.groupBy(TaskItem::listName).map { (title, groupedTasks) ->
            TaskDisplaySection(title, groupedTasks)
        }
    } else {
        val knownGroupIds = selectedList.groups.mapTo(mutableSetOf(), TaskGroupItem::id)
        buildList {
            selectedList.groups.sortedBy(TaskGroupItem::sortOrder).forEach { group ->
                add(
                    TaskDisplaySection(
                        title = group.name,
                        tasks = visible.filter { it.groupId == group.id },
                        group = group,
                    ),
                )
            }
            val ungrouped = visible.filter { it.groupId == null || it.groupId !in knownGroupIds }
            if (ungrouped.isNotEmpty()) {
                add(TaskDisplaySection(stringResource(R.string.task_ungrouped), ungrouped))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreate,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_create)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Dimens.Calendar.FabClearance),
        ) {
            item {
                TaskHomeHeader(
                    owner = owner,
                    selectedList = selectedList?.name
                        ?: standaloneLabel.takeIf { view == TaskView.Standalone },
                    onOpenDrawer = onOpenDrawer,
                    onSearch = onSearch,
                    onSettings = onSettings,
                )
                if (
                    selectedList == null &&
                    (view == TaskView.Assigned || view == TaskView.Following)
                ) {
                    TaskSegmentedControl(selected = view, onSelected = onViewChange)
                }
                TaskFilterBar(view = view, includeDone = includeDone, onFilter = onFilter)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (!loading && sections.isEmpty()) {
                item { TaskEmptyState(onCreate) }
            } else {
                sections.forEach { section ->
                    item {
                        TaskSectionHeader(
                            title = section.title,
                            count = section.tasks.size,
                            onMore = section.group?.takeIf { selectedList?.canManage == true }
                                ?.let { group -> { onSectionAction(group) } },
                        )
                    }
                    items(section.tasks, key = { it.id }) { task ->
                        SwipeTaskRow(
                            task = task,
                            onClick = { onTaskClick(task) },
                            onToggleDone = { onToggleDone(task) },
                            onAction = { onTaskAction(task) },
                        )
                    }
                }
            }
            selectedList?.takeIf(TaskListItem::canManage)?.let { manageableList ->
                item {
                    TextButton(
                        onClick = { onNewTaskGroup(manageableList) },
                        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(Dimens.SpaceS))
                        Text(stringResource(R.string.task_new_task_group))
                    }
                }
            }
        }
    }
}

private data class TaskDisplaySection(
    val title: String,
    val tasks: List<TaskItem>,
    val group: TaskGroupItem? = null,
)

@Composable
private fun TaskHomeHeader(
    owner: String,
    selectedList: String?,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(Dimens.ActionTile),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(owner, Dimens.AvatarM)
            Spacer(Modifier.width(Dimens.SpaceM))
            Column(Modifier.weight(1f)) {
                Text(
                    text = selectedList ?: stringResource(R.string.task_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (selectedList != null) {
                    Text(
                        stringResource(R.string.task_list_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, stringResource(R.string.task_search))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.task_settings))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(Dimens.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Outlined.Menu, stringResource(R.string.task_navigation))
            }
            if (selectedList != null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(Dimens.SpaceXl)) {
                    Row(
                        modifier = Modifier.padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ListAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.IconSmall))
                        Spacer(Modifier.width(Dimens.SpaceS))
                        Text(selectedList, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskSegmentedControl(selected: TaskView, onSelected: (TaskView) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(Dimens.SpaceL),
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS).fillMaxWidth(),
    ) {
        Row(Modifier.padding(Dimens.SpaceXs)) {
            listOf(
                TaskView.Assigned to R.string.task_assigned_to_me,
                TaskView.Following to R.string.task_following,
            ).forEach { (view, label) ->
                val active = view == selected
                Surface(
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shape = RoundedCornerShape(Dimens.SpaceM),
                    shadowElevation = if (active) Dimens.ElevationSubtle else Dimens.SpaceNone,
                    modifier = Modifier.weight(1f).clickable { onSelected(view) },
                ) {
                    Text(
                        stringResource(label),
                        modifier = Modifier.padding(vertical = Dimens.SpaceM),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskFilterBar(view: TaskView, includeDone: Boolean, onFilter: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FilterList, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceS))
        Text(
            when (view) {
                TaskView.All -> stringResource(R.string.task_all_statuses)
                TaskView.Completed -> stringResource(R.string.task_completed)
                TaskView.Standalone -> if (includeDone) {
                    stringResource(R.string.task_all_statuses)
                } else {
                    stringResource(R.string.task_incomplete)
                }
                else -> if (includeDone) stringResource(R.string.task_all_statuses)
                else stringResource(R.string.task_incomplete)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.task_group_by_list),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (view != TaskView.All && view != TaskView.Completed) {
            IconButton(onClick = onFilter) {
                Icon(Icons.Outlined.Tune, stringResource(R.string.task_filter))
            }
        }
    }
}

@Composable
private fun TaskSectionHeader(title: String, count: Int, onMore: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = Dimens.ScreenPadding, top = Dimens.SpaceM, bottom = Dimens.SpaceM, end = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceS))
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onMore != null) {
            IconButton(onClick = onMore) {
                Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.task_more))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeTaskRow(
    task: TaskItem,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    onAction: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) onAction()
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = Dimens.SpaceXl),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Share, null, tint = MaterialTheme.colorScheme.onError)
                Spacer(Modifier.width(Dimens.SpaceXl))
                Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.onError)
            }
        },
    ) {
        TaskRow(task, onClick, onToggleDone, onAction)
    }
}

@Composable
private fun TaskRow(
    task: TaskItem,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    onLongClick: () -> Unit,
) {
    val done = task.status == TaskStatus.Done
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceL),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = Dimens.SpaceXxs).size(Dimens.SpaceXl).clip(CircleShape)
                .border(Dimens.BorderEmphasis, if (done) WeMeetTheme.extras.status.success else MaterialTheme.colorScheme.outline, CircleShape)
                .background(if (done) WeMeetTheme.extras.status.successContainer else Color.Transparent)
                .clickable(onClick = onToggleDone),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, null, tint = WeMeetTheme.extras.status.onSuccessContainer, modifier = Modifier.size(Dimens.SpaceL))
        }
        Spacer(Modifier.width(Dimens.SpaceM))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.priority == TaskPriority.High) {
                    Box(Modifier.size(Dimens.SpaceS).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(Dimens.SpaceS))
                }
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceS))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    null,
                    modifier = Modifier.size(Dimens.SpaceL),
                    tint = if (task.priority == TaskPriority.High && !done) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Dimens.SpaceXs))
                Text(
                    task.dueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (task.priority == TaskPriority.High && !done) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                task.subtaskProgress?.let {
                    Spacer(Modifier.width(Dimens.SpaceM))
                    Icon(Icons.Outlined.AccountTree, null, modifier = Modifier.size(Dimens.SpaceL))
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    Text("${it.first}/${it.second}", style = MaterialTheme.typography.labelMedium)
                }
                if (task.commentCount > 0) {
                    Spacer(Modifier.width(Dimens.SpaceM))
                    Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(Dimens.SpaceL))
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    Text(task.commentCount.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.width(Dimens.SpaceS))
        Avatar(task.assignee, Dimens.SpaceXxl)
    }
    HorizontalDivider(thickness = Dimens.DividerThin, modifier = Modifier.padding(start = Dimens.ButtonHeight))
}

@Composable
private fun TaskEmptyState(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXxxl, vertical = Dimens.ActionTile),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(Dimens.ActionTile)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.AvatarS))
            }
        }
        Spacer(Modifier.height(Dimens.SpaceXl))
        Text(stringResource(R.string.task_empty_title), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.SpaceS))
        Text(
            stringResource(R.string.task_empty_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(Dimens.SpaceXl))
        OutlinedButton(onClick = onCreate) { Text(stringResource(R.string.task_create)) }
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.takeLast(1),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TaskNavigationDrawer(
    selectedList: String?,
    taskLists: List<TaskListItem>,
    listGroups: List<TaskListGroupItem>,
    assignedCount: Int,
    followingCount: Int,
    createdCount: Int,
    allCount: Int,
    completedCount: Int,
    standaloneCount: Int,
    onDismiss: () -> Unit,
    onSelectView: (TaskView) -> Unit,
    onSelectList: (TaskListItem) -> Unit,
    onNewGroup: () -> Unit,
    onNewList: () -> Unit,
    onOpenArchivedLists: () -> Unit,
    onGroupAction: (TaskListGroupItem) -> Unit,
    onListAction: (TaskListItem) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)).clickable(onClick = onDismiss)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f).fillMaxHeight().clickable(enabled = false) {},
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = Dimens.SpaceM,
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = Dimens.SpaceXl)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceXl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = RoundedCornerShape(Dimens.SpaceM), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.ListLeadingIcon)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Text(stringResource(R.string.task_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.task_close)) }
                    }
                    DrawerItem(Icons.Outlined.PersonOutline, R.string.task_assigned_to_me, assignedCount.toString()) {
                        onSelectView(TaskView.Assigned)
                    }
                    DrawerItem(Icons.Outlined.BookmarkBorder, R.string.task_following, followingCount.toString()) {
                        onSelectView(TaskView.Following)
                    }
                    DrawerItem(Icons.Outlined.History, R.string.task_activity, null) {}
                    HorizontalDivider(Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM))
                    Text(
                        stringResource(R.string.task_quick_access),
                        modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DrawerItem(Icons.Outlined.TaskAlt, R.string.task_all_tasks, allCount.toString()) {
                        onSelectView(TaskView.All)
                    }
                    DrawerItem(
                        Icons.Outlined.PersonOutline,
                        R.string.task_created_by_me,
                        createdCount.toString(),
                    ) {
                        onSelectView(TaskView.Created)
                    }
                    DrawerItem(
                        Icons.Outlined.Checklist,
                        R.string.task_completed,
                        completedCount.toString(),
                    ) {
                        onSelectView(TaskView.Completed)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Text(stringResource(R.string.task_lists), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onNewList) {
                            Icon(Icons.Filled.Add, stringResource(R.string.task_new_list))
                        }
                    }
                }
                val groupedIds = listGroups.map(TaskListGroupItem::id).toSet()
                listGroups.forEach { group ->
                    item {
                        DrawerGroup(
                            title = group.name,
                            lists = taskLists.filter { it.groupId == group.id },
                            selectedList = selectedList,
                            onSelectList = onSelectList,
                            onGroupAction = { onGroupAction(group) }.takeIf {
                                group.canManage
                            },
                            onListAction = onListAction,
                        )
                    }
                }
                val ungrouped = taskLists.filter { it.groupId == null || it.groupId !in groupedIds }
                if (ungrouped.isNotEmpty()) {
                    item {
                        DrawerGroup(
                            title = stringResource(R.string.task_ungrouped),
                            lists = ungrouped,
                            selectedList = selectedList,
                            onSelectList = onSelectList,
                            onListAction = onListAction,
                        )
                    }
                }
                if (standaloneCount > 0) {
                    item {
                        DrawerItem(
                            Icons.AutoMirrored.Outlined.ListAlt,
                            R.string.task_standalone,
                            standaloneCount.toString(),
                        ) {
                            onSelectView(TaskView.Standalone)
                        }
                    }
                }
                item {
                    DrawerItem(
                        Icons.Outlined.Archive,
                        R.string.task_archived_lists,
                        null,
                        onOpenArchivedLists,
                    )
                }
                item {
                    TextButton(onClick = onNewGroup, modifier = Modifier.padding(horizontal = Dimens.SpaceM)) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(Dimens.SpaceS))
                        Text(stringResource(R.string.task_new_group))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, labelRes: Int, count: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceL))
        Text(stringResource(labelRes), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (count != null) Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DrawerGroup(
    title: String,
    lists: List<TaskListItem>,
    selectedList: String?,
    onSelectList: (TaskListItem) -> Unit,
    onGroupAction: (() -> Unit)? = null,
    onListAction: (TaskListItem) -> Unit,
) {
    Column(Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(Dimens.SpaceXl))
            Spacer(Modifier.width(Dimens.SpaceS))
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onGroupAction != null) {
                IconButton(onClick = onGroupAction) {
                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.task_more))
                }
            }
        }
        lists.forEach { list ->
            val selected = selectedList == list.name
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(Dimens.SpaceM),
                modifier = Modifier.fillMaxWidth().clickable { onSelectList(list) },
            ) {
                Row(
                    Modifier.padding(
                        start = Dimens.SpaceXxxl,
                        end = Dimens.SpaceS,
                        top = Dimens.SpaceS,
                        bottom = Dimens.SpaceS,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ListAlt, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Dimens.SpaceM))
                    Text(list.name, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(list.taskCount.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (list.canManage || list.canShare || list.canRemove || list.canDelete) {
                        IconButton(onClick = { onListAction(list) }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.task_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateTaskPage(
    owner: String,
    taskLists: List<TaskListItem>,
    creating: Boolean,
    onClose: () -> Unit,
    onCreate: (String, String, String?, String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var selectedListId by remember(taskLists) { mutableStateOf(taskLists.firstOrNull()?.id) }
    val selectedList = taskLists.firstOrNull { it.id == selectedListId }
    val dueLabel = if (dueDate == LocalDate.now().toString()) stringResource(R.string.task_today)
    else stringResource(R.string.task_tomorrow)

    Scaffold(
        topBar = { TaskPageTopBar(stringResource(R.string.task_create), onClose) },
        bottomBar = {
            Surface(shadowElevation = Dimens.SpaceS) {
                Button(
                    onClick = { onCreate(title.trim(), description.trim(), dueDate, selectedListId) },
                    enabled = title.isNotBlank() && !creating,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM).height(Dimens.ButtonHeight),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text(stringResource(R.string.task_create_action)) }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.task_title_hint)) },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text(stringResource(R.string.task_description_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    modifier = Modifier.fillMaxWidth().height(Dimens.Task.DescriptionFieldHeight),
                )
            }
            item {
                TaskFormCard {
                    FormValueRow(Icons.Outlined.PersonOutline, R.string.task_assignee, owner)
                    HorizontalDivider(Modifier.padding(start = Dimens.ListLeadingIcon))
                    FormValueRow(Icons.Outlined.CalendarMonth, R.string.task_due_time, dueLabel) {
                        dueDate = if (dueDate == LocalDate.now().toString()) LocalDate.now().plusDays(1).toString()
                        else LocalDate.now().toString()
                    }
                    HorizontalDivider(Modifier.padding(start = Dimens.ListLeadingIcon))
                    FormValueRow(
                        Icons.AutoMirrored.Outlined.ListAlt,
                        R.string.task_add_to_list,
                        selectedList?.name ?: stringResource(R.string.task_ungrouped),
                    ) {
                        val current = taskLists.indexOfFirst { it.id == selectedListId }
                        selectedListId = taskLists.getOrNull((current + 1).mod(taskLists.size.coerceAtLeast(1)))?.id
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    AssistChip(onClick = { dueDate = LocalDate.now().toString() }, label = { Text(stringResource(R.string.task_today)) }, leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(Dimens.IconSmall)) })
                    AssistChip(onClick = { dueDate = LocalDate.now().plusDays(1).toString() }, label = { Text(stringResource(R.string.task_tomorrow)) }, leadingIcon = { Icon(Icons.Outlined.Alarm, null, Modifier.size(Dimens.IconSmall)) })
                }
            }
            item {
                TaskFormCard {
                    ActionRow(Icons.Outlined.AccountTree, R.string.task_add_subtask)
                    ActionRow(Icons.Outlined.AttachFile, R.string.task_add_attachment)
                    ActionRow(Icons.Outlined.BookmarkBorder, R.string.task_add_follower)
                }
            }
        }
    }
}

@Composable
private fun TaskDetailPage(
    task: TaskItem,
    detail: TaskDetailItem?,
    onBack: () -> Unit,
    onToggleDone: (TaskItem) -> Unit,
    onToggleFollow: (TaskItem) -> Unit,
    onSendComment: (TaskItem, String, () -> Unit) -> Unit,
    onAddSubtask: () -> Unit,
    onToggleSubtask: (TaskItem) -> Unit,
    onOpenSubtask: (TaskItem) -> Unit,
    onSubtaskAction: (TaskItem) -> Unit,
    onEditContent: () -> Unit,
    onEditDueDate: () -> Unit,
    onEditPriority: () -> Unit,
    canManageRecurrence: Boolean,
    onEditRecurrence: () -> Unit,
    canEditParent: Boolean,
    onEditParent: () -> Unit,
    onEditAssignees: () -> Unit,
    onAddFollowers: () -> Unit,
    onRemoveFollower: (TaskPersonItem) -> Unit,
    onAddAttachment: () -> Unit,
    onDownloadAttachment: (TaskAttachmentItem) -> Unit,
    onDeleteAttachment: (TaskAttachmentItem) -> Unit,
    onShare: () -> Unit,
    onMore: (TaskItem) -> Unit,
) {
    var comment by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().height(Dimens.Task.TopBarHeight).padding(horizontal = Dimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.task_back)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onToggleFollow(task) }) {
                    Icon(
                        if (task.followed) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        stringResource(R.string.task_follow),
                        tint = if (task.followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, stringResource(R.string.task_share)) }
                IconButton(onClick = {}) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.task_copy)) }
                IconButton(onClick = { onMore(task) }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.task_more)) }
            }
        },
        bottomBar = {
            Surface(shadowElevation = Dimens.SpaceS) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Dimens.SpaceM),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        placeholder = { Text(stringResource(R.string.task_comment_hint)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardActions = KeyboardActions(
                            onDone = { onSendComment(task, comment) { comment = "" } },
                        ),
                    )
                    IconButton(
                        onClick = { onSendComment(task, comment) { comment = "" } },
                        enabled = comment.isNotBlank() && task.canComment,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.task_send), tint = if (comment.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.IconSmall),
        ) {
            if (detail?.loading == true) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier.padding(top = Dimens.SpaceXs).size(Dimens.IconLarge).clip(CircleShape)
                            .border(Dimens.BorderEmphasis, if (task.status == TaskStatus.Done) WeMeetTheme.extras.status.success else MaterialTheme.colorScheme.outline, CircleShape)
                            .background(if (task.status == TaskStatus.Done) WeMeetTheme.extras.status.successContainer else Color.Transparent)
                            .clickable { onToggleDone(task) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (task.status == TaskStatus.Done) Icon(Icons.Filled.Check, null, tint = WeMeetTheme.extras.status.onSuccessContainer, modifier = Modifier.size(Dimens.IconSmall))
                    }
                    Spacer(Modifier.width(Dimens.SpaceM))
                    Text(
                        task.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.then(
                            if (task.canEdit) Modifier.clickable(onClick = onEditContent)
                            else Modifier,
                        ),
                    )
                }
            }
            item {
                Text(
                    task.description.ifBlank { stringResource(R.string.task_description_hint) },
                    color = if (task.description.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = Dimens.SpaceXxxl).then(
                        if (task.canEdit) Modifier.clickable(onClick = onEditContent)
                        else Modifier,
                    ),
                )
            }
            item {
                TaskFormCard {
                    FormValueRow(
                        Icons.Outlined.PersonOutline,
                        R.string.task_assignee,
                        task.assignee,
                        onEditAssignees.takeIf { task.canEdit },
                    )
                    FormValueRow(
                        Icons.Outlined.CalendarMonth,
                        R.string.task_due_time,
                        task.dueLabel,
                        onEditDueDate.takeIf { task.canEdit },
                    )
                    FormValueRow(Icons.AutoMirrored.Outlined.ListAlt, R.string.task_list, task.listName)
                    FormValueRow(
                        Icons.Outlined.Flag,
                        R.string.task_priority,
                        priorityText(task.priority),
                        onEditPriority.takeIf { task.canEdit },
                    )
                    if (task.parentId == null) {
                        FormValueRow(
                            Icons.Outlined.Repeat,
                            R.string.calendar_field_repeat,
                            recurrenceText(task.recurrence),
                            onEditRecurrence.takeIf { canManageRecurrence },
                        )
                    }
                    FormValueRow(
                        Icons.Outlined.AccountTree,
                        R.string.task_parent,
                        task.parentTitle ?: stringResource(R.string.task_no_parent),
                        onEditParent.takeIf { canEditParent },
                    )
                }
            }
            item {
                val subtasks = detail?.subtasks.orEmpty()
                val canReorderSubtasks = task.canEdit && subtasks.all(TaskItem::canEdit)
                DetailSectionTitle(
                    R.string.task_subtasks,
                    "${subtasks.count { it.status == TaskStatus.Done }}/${subtasks.size}",
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(Dimens.SpaceM), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        subtasks.forEach { subtask ->
                            TaskRow(
                                task = subtask,
                                onClick = { onOpenSubtask(subtask) },
                                onToggleDone = { onToggleSubtask(subtask) },
                                onLongClick = {
                                    if (canReorderSubtasks) onSubtaskAction(subtask)
                                },
                            )
                        }
                        if (task.canCreateSubtasks) {
                            ActionRow(
                                Icons.Filled.Add,
                                R.string.task_add_subtask,
                                onClick = onAddSubtask,
                            )
                        }
                    }
                }
            }
            item {
                DetailSectionTitle(R.string.task_attachments, null)
                detail?.attachments.orEmpty().forEach { attachment ->
                    val downloading = attachment.id in detail?.downloadingAttachmentIds.orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !downloading) {
                                onDownloadAttachment(attachment)
                            }
                            .padding(vertical = Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, null)
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Column(Modifier.weight(1f)) {
                            Text(attachment.filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            attachment.size?.let { size ->
                                Text(
                                    formatFileSize(size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.IconMedium),
                                strokeWidth = Dimens.BorderEmphasis,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Download,
                                stringResource(R.string.task_download_attachment, attachment.filename),
                            )
                        }
                        if (task.canManageAttachments) {
                            IconButton(
                                onClick = { onDeleteAttachment(attachment) },
                                enabled = !downloading,
                            ) {
                                Icon(Icons.Filled.DeleteOutline, stringResource(R.string.task_delete_attachment))
                            }
                        }
                    }
                }
                if (task.canManageAttachments) {
                    OutlinedButton(
                        onClick = onAddAttachment,
                        enabled = detail?.uploadingAttachment != true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.AttachFile, null)
                        Spacer(Modifier.width(Dimens.SpaceS))
                        Text(
                            stringResource(
                                if (detail?.uploadingAttachment == true) R.string.task_uploading_attachment
                                else R.string.task_add_attachment,
                            ),
                        )
                    }
                }
            }
            item {
                DetailSectionTitle(R.string.task_followers, null)
                task.followers.forEach { follower ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(follower.name, Dimens.AvatarS)
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Text(follower.name, modifier = Modifier.weight(1f))
                        if (task.canManageFollowers) {
                            IconButton(onClick = { onRemoveFollower(follower) }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    stringResource(R.string.task_remove_follower),
                                )
                            }
                        }
                    }
                }
                if (task.canManageFollowers) {
                    OutlinedButton(onClick = onAddFollowers, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(Dimens.SpaceS))
                        Text(stringResource(R.string.task_add_follower))
                    }
                }
                TextButton(onClick = { onToggleFollow(task) }) {
                    Text(
                        if (task.followed) stringResource(R.string.task_unfollow)
                        else stringResource(R.string.task_follow),
                    )
                }
            }
            item {
                DetailSectionTitle(R.string.task_activity, null)
                detail?.activities.orEmpty().forEach { activity ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS)) {
                        Text(
                            activityText(activity),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (activity.createdAt.isNotBlank()) {
                            Text(
                                activity.createdAt.take(16).replace('T', ' '),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (!detail?.comments.isNullOrEmpty()) {
                item { DetailSectionTitle(R.string.task_comments, detail?.comments?.size.toString()) }
                items(detail?.comments.orEmpty(), key = { it.id }) { taskComment ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Avatar(taskComment.author, Dimens.AvatarS)
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Column(Modifier.weight(1f)) {
                            Text(taskComment.author, fontWeight = FontWeight.SemiBold)
                            Text(taskComment.content)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskSearchPage(
    tasks: List<TaskItem>,
    searching: Boolean,
    query: String,
    filter: TaskSearchFilter,
    canFilterSelf: Boolean,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (TaskSearchFilter) -> Unit,
    onTaskClick: (TaskItem) -> Unit,
) {
    var statusMenu by remember { mutableStateOf(false) }
    var dueMenu by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.task_back)) }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.task_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {{
                    IconButton(onClick = {
                        onQueryChange("")
                    }) { Icon(Icons.Filled.Close, null) }
                }} else null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.SpaceL),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        ) {
            item {
                AssistChip(
                    onClick = { onFilterChange(filter.copy(creatorSelf = !filter.creatorSelf)) },
                    enabled = canFilterSelf,
                    label = {
                        Text(
                            if (filter.creatorSelf) {
                                stringResource(R.string.task_search_filter_me, stringResource(R.string.task_creator))
                            } else {
                                stringResource(R.string.task_creator)
                            },
                        )
                    },
                    leadingIcon = filter.creatorSelf.takeIf { it }?.let {{
                        Icon(Icons.Filled.Check, null, Modifier.size(Dimens.IconSmall))
                    }},
                )
            }
            item {
                AssistChip(
                    onClick = { onFilterChange(filter.copy(assigneeSelf = !filter.assigneeSelf)) },
                    enabled = canFilterSelf,
                    label = {
                        Text(
                            if (filter.assigneeSelf) {
                                stringResource(R.string.task_search_filter_me, stringResource(R.string.task_assignee))
                            } else {
                                stringResource(R.string.task_assignee)
                            },
                        )
                    },
                    leadingIcon = filter.assigneeSelf.takeIf { it }?.let {{
                        Icon(Icons.Filled.Check, null, Modifier.size(Dimens.IconSmall))
                    }},
                )
            }
            item {
                Box {
                    AssistChip(
                        onClick = { statusMenu = true },
                        label = { Text(searchStatusText(filter.status)) },
                    )
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        TaskSearchStatus.entries.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(searchStatusText(status)) },
                                onClick = {
                                    statusMenu = false
                                    onFilterChange(filter.copy(status = status))
                                },
                                leadingIcon = selectedFilterIcon(status == filter.status),
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    AssistChip(
                        onClick = { dueMenu = true },
                        label = { Text(searchDueText(filter.due)) },
                    )
                    DropdownMenu(expanded = dueMenu, onDismissRequest = { dueMenu = false }) {
                        TaskSearchDue.entries.forEach { due ->
                            DropdownMenuItem(
                                text = { Text(searchDueText(due)) },
                                onClick = {
                                    dueMenu = false
                                    onFilterChange(filter.copy(due = due))
                                },
                                leadingIcon = selectedFilterIcon(due == filter.due),
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    AssistChip(
                        onClick = { priorityMenu = true },
                        label = {
                            Text(
                                if (filter.priority == null) {
                                    stringResource(R.string.task_search_all_priorities)
                                } else {
                                    priorityText(filter.priority)
                                },
                            )
                        },
                    )
                    DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_search_all_priorities)) },
                            onClick = {
                                priorityMenu = false
                                onFilterChange(filter.copy(priority = null))
                            },
                            leadingIcon = selectedFilterIcon(filter.priority == null),
                        )
                        TaskPriority.entries.forEach { priority ->
                            DropdownMenuItem(
                                text = { Text(priorityText(priority)) },
                                onClick = {
                                    priorityMenu = false
                                    onFilterChange(filter.copy(priority = priority))
                                },
                                leadingIcon = selectedFilterIcon(priority == filter.priority),
                            )
                        }
                    }
                }
            }
            if (filter.isActive) {
                item {
                    TextButton(onClick = { onFilterChange(TaskSearchFilter()) }) {
                        Text(stringResource(R.string.task_clear_filters))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.task_search_results, tasks.size),
            modifier = Modifier.padding(Dimens.SpaceL),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn {
            items(tasks, key = { it.id }) { task ->
                TaskRow(task, { onTaskClick(task) }, {}, {})
            }
        }
    }
}

@Composable
private fun searchStatusText(status: TaskSearchStatus): String = when (status) {
    TaskSearchStatus.All -> stringResource(R.string.task_all_statuses)
    TaskSearchStatus.Open -> stringResource(R.string.task_search_open)
    TaskSearchStatus.Completed -> stringResource(R.string.task_completed)
}

@Composable
private fun searchDueText(due: TaskSearchDue): String = when (due) {
    TaskSearchDue.All -> stringResource(R.string.task_search_all_due)
    TaskSearchDue.Today -> stringResource(R.string.task_today)
    TaskSearchDue.Tomorrow -> stringResource(R.string.task_tomorrow)
    TaskSearchDue.ThisWeek -> stringResource(R.string.task_search_this_week)
    TaskSearchDue.Overdue -> stringResource(R.string.task_search_overdue)
    TaskSearchDue.NoDate -> stringResource(R.string.task_search_no_due)
}

private fun selectedFilterIcon(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        { Icon(Icons.Filled.Check, null, Modifier.size(Dimens.IconSmall)) }
    } else {
        null
    }

@Composable
private fun TaskSettingsPage(onBack: () -> Unit) {
    var dailyReminder by remember { mutableStateOf(true) }
    var overdueDots by remember { mutableStateOf(true) }
    Scaffold(topBar = { TaskPageTopBar(stringResource(R.string.task_settings), onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(Dimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.task_daily_reminder),
                    subtitle = stringResource(R.string.task_daily_reminder_desc),
                    checked = dailyReminder,
                    onCheckedChange = { dailyReminder = it },
                )
            }
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.task_overdue_marker),
                    subtitle = stringResource(R.string.task_overdue_marker_desc),
                    checked = overdueDots,
                    onCheckedChange = { overdueDots = it },
                )
            }
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().clickable {}.padding(Dimens.SpaceL),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.task_default_reminder), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.task_default_reminder_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.task_30_minutes_before), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun TaskPageTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.Task.TopBarHeight).padding(horizontal = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.Close, stringResource(R.string.task_close)) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TaskFormCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.SpaceL),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = { Column(content = content) },
    )
}

@Composable
private fun FormValueRow(icon: ImageVector, labelRes: Int, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceM))
        Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(Dimens.LabelColumnWidth))
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, labelRes: Int, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceM))
        Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailSectionTitle(labelRes: Int, value: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(Dimens.SpaceS))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(Dimens.SpaceL), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(Dimens.SpaceL), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Dimens.SpaceXs))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun priorityText(priority: TaskPriority): String = when (priority) {
    TaskPriority.None -> stringResource(R.string.task_priority_none)
    TaskPriority.Low -> stringResource(R.string.task_priority_low)
    TaskPriority.Medium -> stringResource(R.string.task_priority_medium)
    TaskPriority.High -> stringResource(R.string.task_priority_high)
    TaskPriority.Urgent -> stringResource(R.string.task_priority_urgent)
}

@Composable
private fun taskListRoleText(role: TaskListRole): String = when (role) {
    TaskListRole.Viewer -> stringResource(R.string.task_role_viewer)
    TaskListRole.Editor -> stringResource(R.string.task_role_editor)
    TaskListRole.Owner -> stringResource(R.string.task_role_owner)
}

@Composable
private fun recurrenceFrequencyText(frequency: TaskRecurrenceFrequency?): String =
    when (frequency) {
        null -> stringResource(R.string.calendar_repeat_none)
        TaskRecurrenceFrequency.Daily -> stringResource(R.string.calendar_repeat_daily)
        TaskRecurrenceFrequency.Weekly -> stringResource(R.string.calendar_repeat_weekly)
        TaskRecurrenceFrequency.Monthly -> stringResource(R.string.calendar_repeat_monthly)
    }

@Composable
private fun recurrenceText(recurrence: TaskRecurrenceItem?): String =
    recurrenceFrequencyText(recurrence?.takeIf { it.active }?.frequency)

@Composable
private fun activityText(activity: TaskActivityItem): String {
    val actor = activity.actor.ifBlank { stringResource(R.string.task_unknown_actor) }
    val resource = when (activity.event) {
        "created" -> R.string.task_activity_created
        "dates_changed" -> R.string.task_activity_dates
        "assignee_changed" -> R.string.task_activity_assignee
        "status_changed" -> R.string.task_activity_status
        "priority_changed" -> R.string.task_activity_priority
        "placement_changed" -> R.string.task_activity_placement
        "attachment_removed" -> R.string.task_activity_attachment
        else -> R.string.task_activity_updated
    }
    return stringResource(resource, actor)
}

@Composable
private fun EditTaskContentDialog(
    task: TaskItem,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_edit_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.task_title_field)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.task_description_field)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, description) },
                enabled = title.isNotBlank() && !saving &&
                    (title.trim() != task.title || description.trim() != task.description),
            ) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun TaskDueDateDialog(
    initialDate: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initialMillis = runCatching {
        LocalDate.parse(initialDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onConfirm(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                .toString(),
                        )
                    }
                },
                enabled = state.selectedDateMillis != null,
            ) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.task_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun TaskPrioritySheet(
    selected: TaskPriority,
    onDismiss: () -> Unit,
    onSelect: (TaskPriority) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(
                stringResource(R.string.task_priority),
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            TaskPriority.entries.filterNot { it == TaskPriority.None }.forEach { priority ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(priority) }
                        .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceL),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(priorityText(priority), modifier = Modifier.weight(1f))
                    if (priority == selected) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRecurrenceSheet(
    task: TaskItem,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSelect: (TaskRecurrenceFrequency?) -> Unit,
) {
    val selected = task.recurrence?.takeIf { it.active }?.frequency
    val choices = listOf<TaskRecurrenceFrequency?>(
        null,
        TaskRecurrenceFrequency.Daily,
        TaskRecurrenceFrequency.Weekly,
        TaskRecurrenceFrequency.Monthly,
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(
                stringResource(R.string.calendar_field_repeat),
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            choices.forEach { frequency ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !saving) {
                        if (frequency == selected) onDismiss() else onSelect(frequency)
                    }.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceL),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(recurrenceFrequencyText(frequency), modifier = Modifier.weight(1f))
                    if (frequency == selected) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskParentSheet(
    task: TaskItem,
    candidates: List<TaskParentCandidateItem>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(
                stringResource(R.string.task_parent),
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ParentChoiceRow(
                label = stringResource(R.string.task_no_parent),
                selected = task.parentId == null,
                enabled = !saving,
                onClick = {
                    if (task.parentId == null) onDismiss() else onSelect(null)
                },
            )
            candidates.forEach { candidate ->
                ParentChoiceRow(
                    label = buildString {
                        repeat(candidate.depth) { append("— ") }
                        append(candidate.title)
                    },
                    selected = task.parentId == candidate.id,
                    enabled = !saving,
                    onClick = {
                        if (task.parentId == candidate.id) onDismiss()
                        else onSelect(candidate.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ParentChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MoveTaskTreeDialog(
    nodeCount: Int,
    moving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_move_subtree_title)) },
        text = { Text(stringResource(R.string.task_move_subtree_message, nodeCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !moving) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !moving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun FilterSheet(includeDone: Boolean, onIncludeDoneChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl).padding(bottom = Dimens.IconLarge)) {
            Text(stringResource(R.string.task_filter_and_sort), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Dimens.SpaceXl))
            Text(stringResource(R.string.task_status), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Dimens.SpaceS))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                AssistChip(onClick = { onIncludeDoneChange(false) }, label = { Text(stringResource(R.string.task_incomplete)) })
                AssistChip(onClick = { onIncludeDoneChange(true) }, label = { Text(stringResource(R.string.task_all_statuses)) })
            }
            Spacer(Modifier.height(Dimens.IconSmall))
            Text(stringResource(R.string.task_grouping), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Dimens.SpaceS))
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.task_group_by_list)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ListAlt, null, Modifier.size(Dimens.IconSmall)) })
            Spacer(Modifier.height(Dimens.IconSmall))
            Text(stringResource(R.string.task_sorting), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Dimens.SpaceS))
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.task_sort_due_time)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(Dimens.IconSmall)) })
            Spacer(Modifier.height(Dimens.SpaceXl))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text(stringResource(R.string.task_apply))
            }
        }
    }
}

@Composable
private fun TaskActionSheet(
    task: TaskItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(
                task.title,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            SheetAction(Icons.Outlined.Share, R.string.task_share, onShare)
            SheetAction(Icons.Outlined.ContentCopy, R.string.task_duplicate, onDismiss)
            SheetAction(Icons.Outlined.CalendarMonth, R.string.task_set_milestone, onDismiss)
            SheetAction(Icons.Filled.DeleteOutline, R.string.task_delete, onDelete, danger = true)
        }
    }
}

@Composable
private fun SubtaskActionSheet(
    task: TaskItem,
    moving: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(
                task.title,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (canMoveUp && !moving) {
                SheetAction(Icons.Outlined.ArrowUpward, R.string.task_move_up, onMoveUp)
            }
            if (canMoveDown && !moving) {
                SheetAction(Icons.Outlined.ArrowDownward, R.string.task_move_down, onMoveDown)
            }
        }
    }
}

@Composable
private fun SectionActionSheet(
    section: String,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onCreateAbove: () -> Unit,
    onCreateBelow: () -> Unit,
    onManageOrder: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(section, Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SheetAction(Icons.Outlined.Edit, R.string.task_rename, onRename)
            SheetAction(Icons.Filled.Add, R.string.task_new_group_above, onCreateAbove)
            SheetAction(Icons.Filled.Add, R.string.task_new_group_below, onCreateBelow)
            SheetAction(Icons.AutoMirrored.Outlined.Sort, R.string.task_manage_group_order, onManageOrder)
            if (canDelete) {
                SheetAction(
                    Icons.Filled.DeleteOutline,
                    R.string.task_delete_navigation_item,
                    onDelete,
                    danger = true,
                )
            }
        }
    }
}

@Composable
private fun TaskGroupOrderDialog(
    groups: List<TaskGroupItem>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<TaskGroupItem>) -> Unit,
) {
    var ordered by remember(groups) {
        mutableStateOf(groups.sortedBy(TaskGroupItem::sortOrder))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_manage_group_order)) },
        text = {
            Column {
                ordered.forEachIndexed { index, group ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.name, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                ordered = ordered.toMutableList().also {
                                    val moved = it.removeAt(index)
                                    it.add(index - 1, moved)
                                }
                            },
                            enabled = index > 0 && !saving,
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.task_move_up))
                        }
                        IconButton(
                            onClick = {
                                ordered = ordered.toMutableList().also {
                                    val moved = it.removeAt(index)
                                    it.add(index + 1, moved)
                                }
                            },
                            enabled = index < ordered.lastIndex && !saving,
                        ) {
                            Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.task_move_down))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(ordered) },
                enabled = !saving && ordered.map(TaskGroupItem::id) !=
                    groups.sortedBy(TaskGroupItem::sortOrder).map(TaskGroupItem::id),
            ) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun SheetAction(icon: ImageVector, labelRes: Int, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(Dimens.SpaceL))
        Text(stringResource(labelRes), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun NewGroupDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_new_group)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.task_group_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank() && !saving,
            ) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun NewTaskListDialog(
    groups: List<TaskListGroupItem>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedGroupId by remember(groups) { mutableStateOf<String?>(null) }
    var groupMenuExpanded by remember { mutableStateOf(false) }
    val selectedGroupName = groups.firstOrNull { it.id == selectedGroupId }?.name
        ?: stringResource(R.string.task_ungrouped)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_new_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.task_list_name_hint)) },
                    singleLine = true,
                )
                Box {
                    OutlinedButton(onClick = { groupMenuExpanded = true }) {
                        Text(stringResource(R.string.task_choose_group, selectedGroupName))
                        Spacer(Modifier.width(Dimens.SpaceS))
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(
                        expanded = groupMenuExpanded,
                        onDismissRequest = { groupMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_ungrouped)) },
                            onClick = {
                                selectedGroupId = null
                                groupMenuExpanded = false
                            },
                        )
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = {
                                    selectedGroupId = group.id
                                    groupMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, selectedGroupId) },
                enabled = name.isNotBlank() && !saving,
            ) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun NavigationActionSheet(
    title: String,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)?,
    onShare: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            onRename?.let {
                SheetAction(Icons.Outlined.Edit, R.string.task_rename, it)
            }
            onShare?.let {
                SheetAction(Icons.Outlined.Groups, R.string.task_list_share, it)
            }
            onArchive?.let {
                SheetAction(Icons.Outlined.Archive, R.string.task_archive, it)
            }
            onLeave?.let {
                SheetAction(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    R.string.task_leave_list,
                    it,
                    danger = true,
                )
            }
            if (canDelete) {
                SheetAction(
                    Icons.Filled.DeleteOutline,
                    R.string.task_delete_navigation_item,
                    onDelete,
                    danger = true,
                )
            }
        }
    }
}

@Composable
private fun TaskListSharingSheet(
    list: TaskListItem,
    members: List<TaskListMemberItem>,
    loading: Boolean,
    mutating: Boolean,
    onDismiss: () -> Unit,
    onAddMember: () -> Unit,
    onRoleChange: (TaskListMemberItem, TaskListRole) -> Unit,
    onRemove: (TaskListMemberItem) -> Unit,
) {
    var roleMenuFor by remember(list.id) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl)
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Text(
                stringResource(R.string.task_list_share_title, list.name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Dimens.SpaceM))
            OutlinedButton(
                onClick = onAddMember,
                enabled = !loading && !mutating,
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(Dimens.SpaceS))
                Text(stringResource(R.string.task_add_collaborator))
            }
            Spacer(Modifier.height(Dimens.SpaceM))
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                members.isEmpty() -> Text(
                    stringResource(R.string.task_collaborators_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Dimens.SpaceXl),
                )
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = Dimens.Chat.SheetListMaxHeight),
                ) {
                    items(members, key = TaskListMemberItem::id) { member ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(member.name, Dimens.AvatarS)
                            Spacer(Modifier.width(Dimens.SpaceM))
                            Text(
                                member.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (member.role == TaskListRole.Owner || member.isSelf) {
                                Text(
                                    taskListRoleText(member.role),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Box {
                                    TextButton(
                                        onClick = { roleMenuFor = member.userId },
                                        enabled = !mutating,
                                    ) {
                                        Text(taskListRoleText(member.role))
                                        Icon(Icons.Outlined.ExpandMore, null)
                                    }
                                    DropdownMenu(
                                        expanded = roleMenuFor == member.userId,
                                        onDismissRequest = { roleMenuFor = null },
                                    ) {
                                        listOf(TaskListRole.Viewer, TaskListRole.Editor)
                                            .forEach { role ->
                                                DropdownMenuItem(
                                                    text = { Text(taskListRoleText(role)) },
                                                    onClick = {
                                                        roleMenuFor = null
                                                        onRoleChange(member, role)
                                                    },
                                                    trailingIcon = {
                                                        if (role == member.role) {
                                                            Icon(Icons.Filled.Check, null)
                                                        }
                                                    },
                                                )
                                            }
                                    }
                                }
                                IconButton(
                                    onClick = { onRemove(member) },
                                    enabled = !mutating,
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        stringResource(
                                            R.string.task_remove_collaborator,
                                            member.name,
                                        ),
                                    )
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
private fun LeaveTaskListDialog(
    listName: String,
    leaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_leave_list)) },
        text = { Text(stringResource(R.string.task_leave_list_confirm, listName)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !leaving) {
                Text(
                    stringResource(R.string.task_leave_list),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !leaving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun ArchiveTaskListDialog(
    listName: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_archive_list_title)) },
        text = { Text(stringResource(R.string.task_archive_list_confirm, listName)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                Text(stringResource(R.string.task_archive))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun ArchivedTaskListsSheet(
    lists: List<TaskListItem>,
    loading: Boolean,
    restoring: Boolean,
    onDismiss: () -> Unit,
    onRestore: (TaskListItem) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl)
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Text(
                stringResource(R.string.task_archived_lists),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Dimens.SpaceL))
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                lists.isEmpty() -> Text(
                    stringResource(R.string.task_archived_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Dimens.SpaceXl),
                )
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = Dimens.SheetContentMaxHeight),
                ) {
                    items(lists, key = TaskListItem::id) { list ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ListAlt, null)
                            Spacer(Modifier.width(Dimens.SpaceM))
                            Text(
                                list.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (list.canArchive) {
                                TextButton(
                                    onClick = { onRestore(list) },
                                    enabled = !restoring,
                                ) {
                                    Text(stringResource(R.string.task_restore))
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
private fun RenameNavigationDialog(
    initialName: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != initialName && !saving,
            ) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun DeleteNavigationDialog(
    message: String,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_delete_navigation_item)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(
                    stringResource(R.string.task_delete_navigation_item),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(R.string.task_cancel))
            }
        },
    )
}

@Composable
private fun NewSubtaskDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_add_subtask)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text(stringResource(R.string.task_title_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.task_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.task_cancel)) }
        },
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private fun buildTaskCardBody(
    task: TaskItem,
    conversationId: String,
    cardTitle: String,
    assigneeLabel: String,
    dueLabel: String,
    followLabel: String,
    viewLabel: String,
): String {
    val detailUrl = "${BuildConfig.WE_MEET_BASE_URL.trimEnd('/')}/tasks" +
        "?task=${Uri.encode(task.id)}&shared_via=${Uri.encode(conversationId)}"
    val fields = JSONArray()
        .put(JSONObject().put("label", assigneeLabel).put("value", task.assignee))
        .put(JSONObject().put("label", dueLabel).put("value", task.dueLabel))
    val buttons = JSONArray()
        .put(
            JSONObject()
                .put("id", "follow-task:${task.id}:$conversationId")
                .put("text", followLabel)
                .put("style", "default")
                .put("action", "url")
                .put("url", detailUrl),
        )
        .put(
            JSONObject()
                .put("id", "view-task:${task.id}")
                .put("text", viewLabel)
                .put("style", "primary")
                .put("action", "url")
                .put("url", detailUrl),
        )
    val blocks = JSONArray()
        .put(
            JSONObject()
                .put("type", "text")
                .put(
                    "spans",
                    JSONArray().put(
                        JSONObject().put("tag", "text").put("text", task.title).put("b", true),
                    ),
                ),
        )
        .put(JSONObject().put("type", "fields").put("items", fields))
        .put(JSONObject().put("type", "divider"))
        .put(
            JSONObject()
                .put("type", "actions")
                .put("resolve", "each")
                .put("buttons", buttons),
        )
    return JSONObject()
        .put("plain", "$cardTitle ${task.title}")
        .put("v", 1)
        .put("header", JSONObject().put("title", cardTitle).put("theme", "info"))
        .put("blocks", blocks)
        .toString()
}
