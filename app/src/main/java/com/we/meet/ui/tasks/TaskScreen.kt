@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.we.meet.ui.tasks

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
import com.we.meet.WeMeetApp
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import java.time.LocalDate

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
    var actionTarget by remember { mutableStateOf<TaskItem?>(null) }
    var sectionMenu by remember { mutableStateOf<String?>(null) }
    val selectedTask = (ui.tasks + ui.searchResults).firstOrNull { it.id == selectedTaskId }
    val snackbar = remember { SnackbarHostState() }
    val failureText = when (ui.failure) {
        TaskFailure.Load -> stringResource(R.string.task_load_failed)
        TaskFailure.Save -> stringResource(R.string.task_save_failed)
        TaskFailure.Delete -> stringResource(R.string.task_delete_failed)
        TaskFailure.Comment -> stringResource(R.string.task_comment_failed)
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
                    onBack = { page = TaskPage.List },
                    onToggleDone = vm::toggleCompleted,
                    onToggleFollow = vm::toggleFollowing,
                    onSendComment = { current, content, onSent ->
                        vm.sendComment(current, content, onSent)
                    },
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
            onDismiss = { showNewGroup = false },
            onCreate = {
                vm.createListGroup(it)
                showNewGroup = false
            },
        )
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
                        IconButton(onClick = onNewGroup) { Icon(Icons.Filled.Add, stringResource(R.string.task_new_group)) }
                    }
                }
                val groupedIds = listGroups.map(TaskListGroupItem::id).toSet()
                listGroups.forEach { group ->
                    item {
                        DrawerGroup(
                            group.name,
                            taskLists.filter { it.groupId == group.id },
                            selectedList,
                            onSelectList,
                        )
                    }
                }
                val ungrouped = taskLists.filter { it.groupId == null || it.groupId !in groupedIds }
                if (ungrouped.isNotEmpty()) {
                    item {
                        DrawerGroup(
                            stringResource(R.string.task_ungrouped),
                            ungrouped,
                            selectedList,
                            onSelectList,
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
) {
    Column(Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(Dimens.SpaceXl))
            Spacer(Modifier.width(Dimens.SpaceS))
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.MoreHoriz, null)
        }
        lists.forEach { list ->
            val selected = selectedList == list.name
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(Dimens.SpaceM),
                modifier = Modifier.fillMaxWidth().clickable { onSelectList(list) },
            ) {
                Row(Modifier.padding(start = Dimens.SpaceXxxl, end = Dimens.SpaceM, top = Dimens.SpaceM, bottom = Dimens.SpaceM)) {
                        Icon(Icons.AutoMirrored.Outlined.ListAlt, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Dimens.SpaceM))
                    Text(list.name, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(list.taskCount.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onBack: () -> Unit,
    onToggleDone: (TaskItem) -> Unit,
    onToggleFollow: (TaskItem) -> Unit,
    onSendComment: (TaskItem, String, () -> Unit) -> Unit,
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
                IconButton(onClick = {}) { Icon(Icons.Outlined.Share, stringResource(R.string.task_share)) }
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
                    Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Text(
                    task.description.ifBlank { stringResource(R.string.task_description_hint) },
                    color = if (task.description.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = Dimens.SpaceXxxl),
                )
            }
            item {
                TaskFormCard {
                    FormValueRow(Icons.Outlined.PersonOutline, R.string.task_assignee, task.assignee)
                    FormValueRow(Icons.Outlined.CalendarMonth, R.string.task_due_time, task.dueLabel)
                    FormValueRow(Icons.AutoMirrored.Outlined.ListAlt, R.string.task_list, task.listName)
                    FormValueRow(Icons.Outlined.Flag, R.string.task_priority, priorityText(task.priority))
                }
            }
            item {
                DetailSectionTitle(R.string.task_subtasks, "${task.subtaskProgress?.first ?: 0}/${task.subtaskProgress?.second ?: 0}")
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(Dimens.SpaceM), modifier = Modifier.fillMaxWidth()) {
                    ActionRow(Icons.Filled.Add, R.string.task_add_subtask)
                }
            }
            item {
                DetailSectionTitle(R.string.task_attachments, null)
                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AttachFile, null)
                    Spacer(Modifier.width(Dimens.SpaceS))
                    Text(stringResource(R.string.task_add_attachment))
                }
            }
            item {
                DetailSectionTitle(R.string.task_followers, null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(task.assignee, Dimens.AvatarS)
                    Spacer(Modifier.width(Dimens.SpaceM))
                    Text(task.assignee)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onToggleFollow(task) }) {
                        Text(if (task.followed) stringResource(R.string.task_unfollow) else stringResource(R.string.task_follow))
                    }
                }
            }
            item {
                DetailSectionTitle(R.string.task_activity, null)
                Text(
                    stringResource(R.string.task_created_activity, task.assignee),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
private fun ActionRow(icon: ImageVector, labelRes: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable {}.padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceL),
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
private fun TaskActionSheet(task: TaskItem, onDismiss: () -> Unit, onDelete: () -> Unit) {
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
            SheetAction(Icons.Outlined.Share, R.string.task_share, onDismiss)
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
private fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
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
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.task_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.task_cancel)) } },
    )
}
