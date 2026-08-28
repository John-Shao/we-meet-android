@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.we.meet.ui.tasks

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(ownerName: String, app: WeMeetApp) {
    val owner = ownerName.ifBlank { stringResource(R.string.task_demo_owner) }
    val vm: TaskViewModel = viewModel(factory = TaskViewModel.Factory(app))
    val ui by vm.ui.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(TaskPage.List) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var showDrawer by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var showNewList by remember { mutableStateOf(false) }
    var groupActionTarget by remember { mutableStateOf<TaskListGroupItem?>(null) }
    var listActionTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var renameGroupTarget by remember { mutableStateOf<TaskListGroupItem?>(null) }
    var renameListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<TaskListGroupItem?>(null) }
    var deleteListTarget by remember { mutableStateOf<TaskListItem?>(null) }
    var editContentTarget by remember { mutableStateOf<TaskItem?>(null) }
    var dueDateTarget by remember { mutableStateOf<TaskItem?>(null) }
    var priorityTarget by remember { mutableStateOf<TaskItem?>(null) }
    var assigneeTarget by remember { mutableStateOf<TaskItem?>(null) }
    var followerTarget by remember { mutableStateOf<TaskItem?>(null) }
    var showNewSubtask by remember { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<TaskItem?>(null) }
    var showShareGroup by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<TaskItem?>(null) }
    var sectionMenu by remember { mutableStateOf<String?>(null) }
    val selectedTask = (ui.tasks + ui.searchResults).firstOrNull { it.id == selectedTaskId }
    val snackbar = remember { SnackbarHostState() }
    val imSession = remember(app) { ImSession.get(app) }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val task = selectedTask
        if (uri != null && task != null) vm.uploadAttachment(task, uri)
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
                selectedList = ui.selectedList?.name,
                includeDone = ui.includeDone,
                loading = ui.loading,
                owner = owner,
                onViewChange = vm::setView,
                onOpenDrawer = { showDrawer = true },
                onSearch = { page = TaskPage.Search },
                onSettings = { page = TaskPage.Settings },
                onFilter = { showFilter = true },
                onCreate = { page = TaskPage.Create },
                onTaskClick = {
                    selectedTaskId = it.id
                    page = TaskPage.Detail
                },
                onToggleDone = vm::toggleCompleted,
                onTaskAction = { actionTarget = it },
                onSectionAction = { sectionMenu = it },
            )

            TaskPage.Create -> CreateTaskPage(
                owner = owner,
                taskLists = ui.taskLists.filter(TaskListItem::canCreateTasks),
                creating = ui.creating,
                onClose = { page = TaskPage.List },
                onCreate = { title, description, dueDate, listId ->
                    vm.createTask(title, description, dueDate, listId) { created ->
                        selectedTaskId = created.id
                        page = TaskPage.Detail
                    }
                },
            )

            TaskPage.Detail -> selectedTask?.let { task ->
                TaskDetailPage(
                    task = task,
                    detail = ui.detail?.takeIf { it.taskId == task.id },
                    onBack = { page = TaskPage.List },
                    onToggleDone = vm::toggleCompleted,
                    onToggleFollow = vm::toggleFollowing,
                    onSendComment = { current, content, onSent ->
                        vm.sendComment(current, content, onSent)
                    },
                    onAddSubtask = { showNewSubtask = true },
                    onToggleSubtask = vm::toggleCompleted,
                    onEditContent = { editContentTarget = task },
                    onEditDueDate = { dueDateTarget = task },
                    onEditPriority = { priorityTarget = task },
                    onEditAssignees = { assigneeTarget = task },
                    onAddFollowers = { followerTarget = task },
                    onRemoveFollower = { follower -> vm.removeFollower(task, follower.id) },
                    onAddAttachment = { attachmentPicker.launch(arrayOf("*/*")) },
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
                onBack = { page = TaskPage.List },
                onQueryChange = vm::search,
                onTaskClick = {
                    selectedTaskId = it.id
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
                assignedCount = ui.tasks.count { it.status != TaskStatus.Done },
                followingCount = ui.tasks.count(TaskItem::followed),
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
                    if (page == TaskPage.Detail) page = TaskPage.List
                }
            },
        )
    }

    sectionMenu?.let {
        SectionActionSheet(section = it, onDismiss = { sectionMenu = null })
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
            onRename = {
                listActionTarget = null
                renameListTarget = list
            },
            onDelete = {
                listActionTarget = null
                deleteListTarget = list
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
    selectedList: String?,
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
    onSectionAction: (String) -> Unit,
) {
    val visible = tasks.visibleFor(view, TaskFilter(includeDone = includeDone), selectedList)
    val groups = visible.groupBy { if (selectedList == null) it.listName else it.section }

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
                    selectedList = selectedList,
                    onOpenDrawer = onOpenDrawer,
                    onSearch = onSearch,
                    onSettings = onSettings,
                )
                if (selectedList == null) {
                    TaskSegmentedControl(selected = view, onSelected = onViewChange)
                }
                TaskFilterBar(includeDone = includeDone, onFilter = onFilter)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (!loading && groups.isEmpty()) {
                item { TaskEmptyState(onCreate) }
            } else {
                groups.forEach { (group, groupedTasks) ->
                    item {
                        TaskSectionHeader(
                            title = group,
                            count = groupedTasks.size,
                            onMore = { onSectionAction(group) },
                        )
                    }
                    items(groupedTasks, key = { it.id }) { task ->
                        SwipeTaskRow(
                            task = task,
                            onClick = { onTaskClick(task) },
                            onToggleDone = { onToggleDone(task) },
                            onAction = { onTaskAction(task) },
                        )
                    }
                }
            }
        }
    }
}

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
private fun TaskFilterBar(includeDone: Boolean, onFilter: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FilterList, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceS))
        Text(
            if (includeDone) stringResource(R.string.task_all_statuses)
            else stringResource(R.string.task_incomplete),
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
        IconButton(onClick = onFilter) { Icon(Icons.Outlined.Tune, stringResource(R.string.task_filter)) }
    }
}

@Composable
private fun TaskSectionHeader(title: String, count: Int, onMore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = Dimens.ScreenPadding, top = Dimens.SpaceM, bottom = Dimens.SpaceM, end = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceS))
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.task_more)) }
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
    onDismiss: () -> Unit,
    onSelectView: (TaskView) -> Unit,
    onSelectList: (TaskListItem) -> Unit,
    onNewGroup: () -> Unit,
    onNewList: () -> Unit,
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
                    DrawerItem(Icons.Outlined.TaskAlt, R.string.task_all_tasks, null) {
                        onSelectView(TaskView.Assigned)
                    }
                    DrawerItem(Icons.Outlined.Checklist, R.string.task_completed, null) {}
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
                    if (list.canManage || list.canDelete) {
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
    onEditContent: () -> Unit,
    onEditDueDate: () -> Unit,
    onEditPriority: () -> Unit,
    onEditAssignees: () -> Unit,
    onAddFollowers: () -> Unit,
    onRemoveFollower: (TaskPersonItem) -> Unit,
    onAddAttachment: () -> Unit,
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
                }
            }
            item {
                DetailSectionTitle(
                    R.string.task_subtasks,
                    "${detail?.subtasks?.count { it.status == TaskStatus.Done } ?: 0}/${detail?.subtasks?.size ?: 0}",
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(Dimens.SpaceM), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        detail?.subtasks.orEmpty().forEach { subtask ->
                            TaskRow(
                                task = subtask,
                                onClick = {},
                                onToggleDone = { onToggleSubtask(subtask) },
                                onLongClick = {},
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
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
                        if (task.canManageAttachments) {
                            IconButton(onClick = { onDeleteAttachment(attachment) }) {
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
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTaskClick: (TaskItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.task_back)) }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                placeholder = { Text(stringResource(R.string.task_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {{
                    IconButton(onClick = {
                        query = ""
                        onQueryChange("")
                    }) { Icon(Icons.Filled.Close, null) }
                }} else null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceL),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        ) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.task_creator)) })
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.task_assignee)) })
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.task_status)) })
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
private fun SectionActionSheet(section: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceXl)) {
            Text(section, Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SheetAction(Icons.Outlined.Edit, R.string.task_rename, onDismiss)
            SheetAction(Icons.Filled.Add, R.string.task_new_group_above, onDismiss)
            SheetAction(Icons.Filled.Add, R.string.task_new_group_below, onDismiss)
            SheetAction(Icons.AutoMirrored.Outlined.Sort, R.string.task_manage_group_order, onDismiss)
        }
    }
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
    onRename: () -> Unit,
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
            SheetAction(Icons.Outlined.Edit, R.string.task_rename, onRename)
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
