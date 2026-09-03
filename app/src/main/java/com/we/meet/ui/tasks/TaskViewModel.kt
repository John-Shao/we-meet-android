package com.we.meet.ui.tasks

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskActivityDto
import com.we.meet.data.api.dto.TaskListDto
import com.we.meet.data.api.dto.TaskListAccessDto
import com.we.meet.data.api.dto.TaskListGroupDto
import com.we.meet.data.api.dto.PatchTaskRequest
import com.we.meet.data.api.dto.PatchTaskSettingsRequest
import com.we.meet.data.api.dto.TaskGroupDto
import com.we.meet.data.api.dto.TaskSettingsDto
import com.we.meet.data.api.dto.CreateTaskSavedViewRequest
import com.we.meet.data.api.dto.PatchTaskSavedViewRequest
import com.we.meet.data.api.dto.TaskSavedViewConfigDto
import com.we.meet.data.api.dto.TaskSavedViewDto
import com.we.meet.data.api.dto.DEFAULT_TASK_SAVED_VIEW_COLUMNS
import com.we.meet.data.api.dto.DEFAULT_TASK_SAVED_VIEW_COLUMN_ORDER
import com.we.meet.data.repository.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TaskFailure { Load, Activity, Settings, Save, Delete, Comment, Attachment, Share, Navigation }

data class TaskUiState(
    val tasks: List<TaskItem> = emptyList(),
    val taskLists: List<TaskListItem> = emptyList(),
    val taskGroups: List<TaskGroupItem> = emptyList(),
    val archivedTaskLists: List<TaskListItem> = emptyList(),
    val taskListMembers: List<TaskListMemberItem> = emptyList(),
    val taskListMembersFor: String? = null,
    val listGroups: List<TaskListGroupItem> = emptyList(),
    val savedViews: List<TaskSavedViewItem> = emptyList(),
    val navigationCounts: TaskNavigationCounts = TaskNavigationCounts(),
    val view: TaskView = TaskView.Assigned,
    val status: TaskListStatus = TaskListStatus.Open,
    val time: TaskTimeFilter = TaskTimeFilter.All,
    val priorityFilter: TaskPriority? = null,
    val grouping: TaskGrouping = TaskGrouping.None,
    val ordering: TaskOrdering = TaskOrdering.Smart,
    val selectedListId: String? = null,
    val selectedGroupId: String? = null,
    val activeSavedViewId: String? = null,
    val invalidGroupSelection: Boolean = false,
    val loading: Boolean = true,
    val creating: Boolean = false,
    val navigationMutating: Boolean = false,
    val archivedListsLoading: Boolean = false,
    val taskListMembersLoading: Boolean = false,
    val mutatingIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchFilter: TaskSearchFilter = TaskSearchFilter(),
    val searchResults: List<TaskItem> = emptyList(),
    val searching: Boolean = false,
    val activityFeed: List<TaskActivityItem> = emptyList(),
    val activityLoading: Boolean = false,
    val activityLoadingMore: Boolean = false,
    val activityPage: Int = 0,
    val activityHasMore: Boolean = false,
    val settings: TaskSettingsItem = TaskSettingsItem(),
    val settingsLoading: Boolean = false,
    val settingsSaving: Boolean = false,
    val detail: TaskDetailItem? = null,
    val failure: TaskFailure? = null,
) {
    val selectedList: TaskListItem?
        get() = taskLists.firstOrNull { it.id == selectedListId }

    val selectedGroup: TaskGroupItem?
        get() = taskGroups.firstOrNull { it.id == selectedGroupId }

    val activeSavedView: TaskSavedViewItem?
        get() = savedViews.firstOrNull { it.id == activeSavedViewId }
}

internal fun TaskUiState.forCustomGroup(groupId: String): TaskUiState = copy(
    view = TaskView.All,
    selectedListId = null,
    selectedGroupId = groupId,
    activeSavedViewId = null,
    invalidGroupSelection = false,
    status = TaskListStatus.Open,
    time = TaskTimeFilter.All,
    priorityFilter = null,
    grouping = TaskGrouping.None,
    ordering = TaskOrdering.Smart,
)

class TaskViewModel(
    private val repository: TaskRepository,
    private val selfUserId: String?,
) : ViewModel() {
    private val _ui = MutableStateFlow(TaskUiState())
    val ui: StateFlow<TaskUiState> = _ui.asStateFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var taskListMembersJob: Job? = null
    private var activityJob: Job? = null
    private var detailLoadJob: Job? = null
    private val viewPreferences = mutableMapOf<String, TaskViewPreferences>()
    private var initialDefaultViewApplied = false

    init {
        loadSettings()
        refreshNavigation()
        refresh()
    }

    fun setView(view: TaskView) {
        if (
            _ui.value.view == view &&
            _ui.value.selectedListId == null &&
            _ui.value.selectedGroupId == null &&
            _ui.value.activeSavedViewId == null
        ) return
        rememberCurrentViewPreferences()
        val preferences = viewPreferences[view.preferenceKey] ?: TaskViewPreferences()
        _ui.update {
            it.copy(
                view = view,
                selectedListId = null,
                selectedGroupId = null,
                activeSavedViewId = null,
                invalidGroupSelection = false,
                status = preferences.status,
                time = preferences.time,
                priorityFilter = preferences.priority,
                grouping = preferences.grouping,
                ordering = preferences.ordering,
            )
        }
        refresh()
    }

    fun selectList(listId: String?) {
        if (
            _ui.value.selectedListId == listId &&
            _ui.value.selectedGroupId == null &&
            _ui.value.activeSavedViewId == null
        ) return
        rememberCurrentViewPreferences()
        val targetKey = listId?.let { "task-list:$it" } ?: _ui.value.view.preferenceKey
        val preferences = viewPreferences[targetKey] ?: TaskViewPreferences()
        _ui.update {
            it.copy(
                selectedListId = listId,
                selectedGroupId = null,
                activeSavedViewId = null,
                invalidGroupSelection = false,
                view = if (listId != null && it.view == TaskView.Standalone) {
                    TaskView.Assigned
                } else {
                    it.view
                },
                status = preferences.status,
                time = preferences.time,
                priorityFilter = preferences.priority,
                grouping = preferences.grouping,
                ordering = preferences.ordering,
            )
        }
        refresh()
    }

    fun selectGroup(groupId: String) {
        if (_ui.value.selectedGroupId == groupId && _ui.value.activeSavedViewId == null) return
        rememberCurrentViewPreferences()
        _ui.update { it.forCustomGroup(groupId) }
        refresh()
    }

    fun clearInvalidGroupSelection() {
        _ui.update { it.copy(invalidGroupSelection = false) }
    }

    private fun rememberCurrentViewPreferences() {
        val state = _ui.value
        if (state.activeSavedViewId != null) return
        viewPreferences[state.preferenceKey] = state.preferences
    }

    fun openSavedView(savedView: TaskSavedViewItem) {
        rememberCurrentViewPreferences()
        val configuredListId = savedView.taskListId.takeUnless { savedView.invalidTaskList }
        val standalone = configuredListId == "unassigned"
        _ui.update {
            it.copy(
                view = if (standalone) TaskView.Standalone else savedView.scope,
                selectedListId = configuredListId?.takeUnless { id -> id == "unassigned" },
                selectedGroupId = savedView.groupId,
                activeSavedViewId = savedView.id,
                invalidGroupSelection = savedView.invalidTaskGroup,
                status = savedView.preferences.status,
                time = savedView.preferences.time,
                priorityFilter = savedView.preferences.priority,
                grouping = savedView.preferences.grouping,
                ordering = savedView.preferences.ordering,
            )
        }
        refresh()
    }

    fun createSavedView(name: String, onCreated: () -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _ui.value.navigationMutating) return
        val state = _ui.value
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.createSavedView(
                CreateTaskSavedViewRequest(trimmed, state.toSavedViewConfig()),
            ).fold(
                onSuccess = { created ->
                    _ui.update {
                        it.copy(
                            navigationMutating = false,
                            activeSavedViewId = created.id,
                            savedViews = (it.savedViews + created.toItem())
                                .distinctBy(TaskSavedViewItem::id)
                                .sortedWith(savedViewComparator),
                        )
                    }
                    onCreated()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun updateActiveSavedView(onUpdated: () -> Unit = {}) {
        val state = _ui.value
        val savedView = state.activeSavedView ?: return
        if (state.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.updateSavedView(
                savedView.id,
                PatchTaskSavedViewRequest(config = state.toSavedViewConfig(savedView)),
            ).fold(
                onSuccess = { updated ->
                    _ui.update {
                        it.copy(
                            navigationMutating = false,
                            savedViews = it.savedViews.map { item ->
                                if (item.id == updated.id) updated.toItem() else item
                            }.sortedWith(savedViewComparator),
                        )
                    }
                    onUpdated()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun deleteActiveSavedView(onDeleted: () -> Unit = {}) {
        val state = _ui.value
        val savedViewId = state.activeSavedViewId ?: return
        if (state.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.deleteSavedView(savedViewId).fold(
                onSuccess = {
                    val preferences = viewPreferences[TaskView.All.preferenceKey]
                        ?: TaskViewPreferences()
                    _ui.update {
                        it.copy(
                            navigationMutating = false,
                            savedViews = it.savedViews.filterNot { view -> view.id == savedViewId },
                            activeSavedViewId = null,
                            selectedListId = null,
                            selectedGroupId = null,
                            view = TaskView.All,
                            status = preferences.status,
                            time = preferences.time,
                            priorityFilter = preferences.priority,
                            grouping = preferences.grouping,
                            ordering = preferences.ordering,
                        )
                    }
                    refresh()
                    onDeleted()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun applyListFilter(
        status: TaskListStatus,
        time: TaskTimeFilter,
        priority: TaskPriority?,
        grouping: TaskGrouping,
        ordering: TaskOrdering,
    ) {
        val previous = _ui.value
        if (
            previous.status == status &&
            previous.time == time &&
            previous.priorityFilter == priority &&
            previous.grouping == grouping &&
            previous.ordering == ordering
        ) return
        _ui.update {
            it.copy(
                status = status,
                time = time,
                priorityFilter = priority,
                grouping = grouping,
                ordering = ordering,
            )
        }
        rememberCurrentViewPreferences()
        if (
            previous.status != status || previous.time != time ||
            previous.priorityFilter != priority || previous.ordering != ordering
        ) refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val snapshot = _ui.value
            _ui.update { it.copy(loading = true, failure = null) }
            repository.loadTasks(
                scope = snapshot.view.apiScope,
                status = snapshot.status.apiValue,
                taskListId = if (snapshot.view == TaskView.Standalone) {
                    "unassigned"
                } else {
                    snapshot.selectedListId
                },
                groupId = snapshot.selectedGroupId,
                ordering = snapshot.ordering.apiValue,
                time = snapshot.time.apiValue,
                priority = snapshot.priorityFilter?.name?.lowercase() ?: "all",
            ).fold(
                onSuccess = { tasks ->
                    _ui.update { it.copy(tasks = tasks.map(TaskDto::toItem), loading = false) }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update { it.copy(loading = false, failure = TaskFailure.Load) }
                },
            )
        }
    }

    fun refreshNavigation() {
        viewModelScope.launch {
            repository.loadNavigation().onSuccess { navigation ->
                val customGroups = navigation.taskGroups.sortedBy { it.sortOrder }
                    .map(TaskGroupDto::toItem)
                var invalidSelection = false
                _ui.update { state ->
                    invalidSelection = state.selectedGroupId != null &&
                        customGroups.none { it.id == state.selectedGroupId }
                    val fallbackPreferences = viewPreferences[TaskView.All.preferenceKey]
                        ?: TaskViewPreferences()
                    state.copy(
                        taskLists = navigation.lists.map(TaskListDto::toItem),
                        taskGroups = customGroups,
                        listGroups = navigation.groups.sortedBy { it.sortOrder }
                            .map(TaskListGroupDto::toItem),
                        savedViews = navigation.savedViews.map(TaskSavedViewDto::toItem)
                            .sortedWith(savedViewComparator),
                        navigationCounts = TaskNavigationCounts(
                            assigned = navigation.counts.assigned,
                            following = navigation.counts.following,
                            created = navigation.counts.created,
                            all = navigation.counts.all,
                            completed = navigation.counts.completed,
                            standalone = navigation.counts.standalone,
                        ),
                        selectedGroupId = state.selectedGroupId.takeUnless { invalidSelection },
                        activeSavedViewId = state.activeSavedViewId.takeUnless { invalidSelection },
                        invalidGroupSelection = state.invalidGroupSelection || invalidSelection,
                        view = if (invalidSelection) TaskView.All else state.view,
                        selectedListId = if (invalidSelection) null else state.selectedListId,
                        status = if (invalidSelection) fallbackPreferences.status else state.status,
                        time = if (invalidSelection) fallbackPreferences.time else state.time,
                        priorityFilter = if (invalidSelection) {
                            fallbackPreferences.priority
                        } else {
                            state.priorityFilter
                        },
                        grouping = if (invalidSelection) {
                            fallbackPreferences.grouping
                        } else {
                            state.grouping
                        },
                        ordering = if (invalidSelection) {
                            fallbackPreferences.ordering
                        } else {
                            state.ordering
                        },
                    )
                }
                if (invalidSelection) refresh()
                if (!initialDefaultViewApplied) {
                    initialDefaultViewApplied = true
                    navigation.savedViews.firstOrNull(TaskSavedViewDto::isDefault)
                        ?.toItem()
                        ?.let(::openSavedView)
                }
            }
        }
    }

    fun refreshActivityFeed() = loadActivityFeed(reset = true)

    fun loadMoreActivityFeed() = loadActivityFeed(reset = false)

    fun loadSettings() {
        _ui.update { it.copy(settingsLoading = true, failure = null) }
        viewModelScope.launch {
            repository.loadSettings().fold(
                onSuccess = { settings ->
                    _ui.update {
                        it.copy(settings = settings.toItem(), settingsLoading = false)
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update {
                        it.copy(settingsLoading = false, failure = TaskFailure.Settings)
                    }
                },
            )
        }
    }

    fun setOverdueMarker(enabled: Boolean) = updateSettings(
        PatchTaskSettingsRequest(overdueMarkerEnabled = enabled),
    )

    fun setDefaultReminder(enabled: Boolean, minutes: Int?) = updateSettings(
        PatchTaskSettingsRequest(
            dailyReminderEnabled = enabled,
            defaultReminderMinutes = minutes,
        ),
    )

    private fun updateSettings(request: PatchTaskSettingsRequest) {
        if (_ui.value.settingsSaving) return
        _ui.update { it.copy(settingsSaving = true, failure = null) }
        viewModelScope.launch {
            repository.updateSettings(request).fold(
                onSuccess = { settings ->
                    _ui.update {
                        it.copy(settings = settings.toItem(), settingsSaving = false)
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update {
                        it.copy(settingsSaving = false, failure = TaskFailure.Settings)
                    }
                },
            )
        }
    }

    private fun loadActivityFeed(reset: Boolean) {
        val snapshot = _ui.value
        if (!reset && (snapshot.activityLoadingMore || !snapshot.activityHasMore)) return
        activityJob?.cancel()
        val page = if (reset) 1 else snapshot.activityPage + 1
        _ui.update {
            it.copy(
                activityLoading = reset,
                activityLoadingMore = !reset,
                failure = null,
            )
        }
        activityJob = viewModelScope.launch {
            repository.loadActivityFeed(page).fold(
                onSuccess = { response ->
                    val mapped = response.results.map(TaskActivityDto::toItem)
                    _ui.update { state ->
                        state.copy(
                            activityFeed = if (reset) {
                                mapped
                            } else {
                                (state.activityFeed + mapped).distinctBy(TaskActivityItem::id)
                            },
                            activityLoading = false,
                            activityLoadingMore = false,
                            activityPage = page,
                            activityHasMore = response.next != null,
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update {
                        it.copy(
                            activityLoading = false,
                            activityLoadingMore = false,
                            failure = TaskFailure.Activity,
                        )
                    }
                },
            )
        }
    }

    fun loadDetail(taskId: String) {
        detailLoadJob?.cancel()
        val snapshot = _ui.value
        val cachedTask = buildList {
            addAll(snapshot.tasks)
            addAll(snapshot.searchResults)
            snapshot.detail?.task?.let(::add)
            addAll(snapshot.detail?.subtasks.orEmpty())
        }.firstOrNull { it.id == taskId }
        _ui.update {
            it.copy(
                detail = TaskDetailItem(taskId = taskId, task = cachedTask, loading = true),
                failure = null,
            )
        }
        detailLoadJob = viewModelScope.launch {
            repository.loadDetail(taskId).fold(
                onSuccess = { detail ->
                    val mappedDetail = detail.toItem(taskId)
                    val task = requireNotNull(mappedDetail.task)
                    _ui.update { state ->
                        // Ignore a stale response that arrives after the user has
                        // moved to a different task.
                        if (state.detail?.taskId != taskId) return@update state
                        state.copy(
                            tasks = state.tasks.replace(taskId, task),
                            searchResults = state.searchResults.replace(taskId, task),
                            detail = mappedDetail,
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update { state ->
                        if (state.detail?.taskId != taskId) return@update state
                        state.copy(
                            detail = state.detail?.copy(loading = false),
                            failure = TaskFailure.Load,
                        )
                    }
                },
            )
        }
    }

    fun createTask(
        title: String,
        description: String,
        startDate: String?,
        dueDate: String?,
        taskListId: String?,
        groupId: String?,
        assigneeIds: List<String>?,
        followerIds: List<String>,
        priority: TaskPriority,
        reminderEnabled: Boolean,
        reminderMinutes: Int?,
        onCreated: (TaskItem) -> Unit,
    ) {
        if (_ui.value.creating) return
        _ui.update { it.copy(creating = true, failure = null) }
        viewModelScope.launch {
            repository.createTask(
                title = title,
                description = description,
                assigneeIds = assigneeIds ?: selfUserId?.let(::listOf),
                followerIds = followerIds,
                startDate = startDate,
                dueDate = dueDate,
                priority = priority.takeUnless { it == TaskPriority.None }?.name?.lowercase(),
                taskListId = taskListId,
                groupId = groupId,
                reminderEnabled = reminderEnabled,
                reminderMinutes = reminderMinutes,
            ).fold(
                onSuccess = { dto ->
                    val item = dto.toItem()
                    _ui.update { it.copy(creating = false, tasks = listOf(item) + it.tasks) }
                    refreshNavigation()
                    onCreated(item)
                },
                onFailure = { _ui.update { it.copy(creating = false, failure = TaskFailure.Save) } },
            )
        }
    }

    fun duplicateTask(item: TaskItem, copyTitle: String, onCreated: (TaskItem) -> Unit) {
        if (item.id in _ui.value.mutatingIds) return
        val list = item.listId?.let { listId ->
            _ui.value.taskLists.firstOrNull { it.id == listId && it.canCreateTasks }
        }
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.duplicateTask(
                title = copyTitle,
                description = item.description,
                assigneeIds = item.assignees.map(TaskPersonItem::id),
                startDate = item.startDate,
                dueDate = item.dueDate,
                priority = item.priority.takeUnless { it == TaskPriority.None }
                    ?.name?.lowercase(),
                taskListId = list?.id,
                groupId = item.groupId,
            ).fold(
                onSuccess = { created ->
                    val copy = created.toItem()
                    _ui.update {
                        it.copy(
                            tasks = listOf(copy) + it.tasks,
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                    refreshNavigation()
                    onCreated(copy)
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun toggleCompleted(item: TaskItem) {
        if (!item.canUpdateStatus || item.id in _ui.value.mutatingIds) return
        val completed = item.status != TaskStatus.Done
        mutateOptimistically(
            item,
            item.copy(status = if (completed) TaskStatus.Done else TaskStatus.Todo),
        ) { repository.setCompleted(item.id, completed) }
    }

    fun toggleFollowing(item: TaskItem) {
        if (item.id in _ui.value.mutatingIds) return
        val following = !item.followed
        mutateOptimistically(item, item.copy(followed = following)) {
            repository.setFollowing(item.id, following)
        }
    }

    fun setTaskReminder(item: TaskItem, enabled: Boolean, minutes: Int?) {
        updateTaskReminder(
            item,
            enabled = enabled,
            reminderMinutes = minutes,
            updateMinutes = enabled,
        )
    }

    private fun updateTaskReminder(
        item: TaskItem,
        enabled: Boolean? = null,
        reminderMinutes: Int? = null,
        updateMinutes: Boolean = false,
    ) {
        val detail = _ui.value.detail?.takeIf { it.taskId == item.id } ?: return
        if (!item.canManageReminder || detail.reminderSaving) return
        _ui.update { it.copy(detail = detail.copy(reminderSaving = true), failure = null) }
        viewModelScope.launch {
            repository.updateTaskReminder(
                taskId = item.id,
                enabled = enabled,
                reminderMinutes = reminderMinutes,
                updateMinutes = updateMinutes,
            ).fold(
                onSuccess = { preference ->
                    _ui.update { state ->
                        val current = state.detail?.takeIf { it.taskId == item.id }
                            ?: return@update state
                        state.copy(
                            detail = current.copy(
                                reminder = TaskReminderItem(
                                    enabled = preference.enabled,
                                    reminderMinutes = preference.reminderMinutes,
                                    effectiveReminderMinutes = preference.effectiveReminderMinutes,
                                    globalRemindersEnabled = preference.globalRemindersEnabled,
                                ),
                                reminderSaving = false,
                            ),
                        )
                    }
                },
                onFailure = {
                    _ui.update { state ->
                        val current = state.detail?.takeIf { it.taskId == item.id }
                            ?: return@update state
                        state.copy(
                            detail = current.copy(reminderSaving = false),
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun updateTitle(item: TaskItem, title: String) {
        val normalizedTitle = title.trim()
        if (!item.canEdit || normalizedTitle.isBlank() || normalizedTitle == item.title) return
        updateTask(
            item,
            PatchTaskRequest(
                title = normalizedTitle,
                recurrenceScope = "one",
            ),
        )
    }

    fun updateDescription(item: TaskItem, description: String) {
        val normalizedDescription = description.trim()
        if (!item.canEdit || normalizedDescription == item.description) return
        updateTask(
            item,
            PatchTaskRequest(
                description = normalizedDescription,
                recurrenceScope = "one",
            ),
        )
    }

    fun updateSchedule(item: TaskItem, startDate: String?, dueDate: String?) {
        if (!item.canEdit) return
        if (item.startDate == startDate && item.dueDate == dueDate) return
        if (startDate != null && dueDate != null && startDate > dueDate) return
        if (item.id in _ui.value.mutatingIds) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.updateSchedule(item.id, startDate, dueDate).fold(
                onSuccess = { updated ->
                    val confirmed = updated.toItem()
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, confirmed),
                            searchResults = it.searchResults.replace(item.id, confirmed),
                            detail = it.detail?.replace(item.id, confirmed),
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun updatePriority(item: TaskItem, priority: TaskPriority) {
        if (!item.canEdit || priority == TaskPriority.None) return
        updateTask(
            item,
            PatchTaskRequest(
                priority = priority.name.lowercase(),
                recurrenceScope = "one",
            ),
        )
    }

    fun updateAssignees(item: TaskItem, userIds: List<String>) {
        if (!item.canEdit || userIds.isEmpty()) return
        updateTask(
            item,
            PatchTaskRequest(assigneeIds = userIds, recurrenceScope = "one"),
        )
    }

    fun updatePlacement(item: TaskItem, taskListId: String?, groupId: String?) {
        if (!item.canEdit || item.id in _ui.value.mutatingIds ||
            (item.listId == taskListId && item.groupId == groupId)
        ) {
            return
        }
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.updatePlacement(item.id, taskListId, groupId).fold(
                onSuccess = { updated ->
                    val confirmed = updated.toItem()
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, confirmed),
                            searchResults = it.searchResults.replace(item.id, confirmed),
                            detail = it.detail?.replace(item.id, confirmed),
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                    refreshNavigation()
                    loadDetail(item.id)
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    private fun updateTask(item: TaskItem, patch: PatchTaskRequest) {
        if (item.id in _ui.value.mutatingIds) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.updateTask(item.id, patch).fold(
                onSuccess = { updated ->
                    val confirmed = updated.toItem()
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, confirmed),
                            searchResults = it.searchResults.replace(item.id, confirmed),
                            detail = it.detail?.replace(item.id, confirmed),
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                    loadDetail(item.id)
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun updateFollowers(item: TaskItem, userIds: List<String>) {
        if (!item.canManageFollowers || item.id in _ui.value.mutatingIds) return
        val selectedIds = userIds.distinct()
        val currentIds = item.followers.map(TaskPersonItem::id)
        val toAdd = selectedIds.filterNot(currentIds::contains)
        val toRemove = currentIds.filterNot(selectedIds::contains)
        if (toAdd.isEmpty() && toRemove.isEmpty()) return

        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            var confirmed = item
            if (toAdd.isNotEmpty()) {
                val addResult = repository.addFollowers(item.id, toAdd)
                if (addResult.isFailure) {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                    loadDetail(item.id)
                    return@launch
                }
                confirmed = requireNotNull(addResult.getOrNull()).toItem()
            }
            for (followerId in toRemove) {
                if (repository.removeFollower(item.id, followerId).isFailure) {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                    loadDetail(item.id)
                    return@launch
                }
                confirmed = confirmed.copy(
                    followers = confirmed.followers.filterNot { it.id == followerId },
                    followed = confirmed.followed && followerId != selfUserId,
                )
            }
            _ui.update {
                it.copy(
                    tasks = it.tasks.replace(item.id, confirmed),
                    searchResults = it.searchResults.replace(item.id, confirmed),
                    detail = it.detail?.replace(item.id, confirmed),
                    mutatingIds = it.mutatingIds - item.id,
                )
            }
            loadDetail(item.id)
        }
    }

    private fun mutateOptimistically(
        previous: TaskItem,
        optimistic: TaskItem,
        request: suspend () -> Result<TaskDto>,
    ) {
        val id = previous.id
        _ui.update {
            it.copy(
                tasks = it.tasks.replace(id, optimistic),
                searchResults = it.searchResults.replace(id, optimistic),
                detail = it.detail?.replace(id, optimistic),
                mutatingIds = it.mutatingIds + id,
                failure = null,
            )
        }
        viewModelScope.launch {
            request().fold(
                onSuccess = { server ->
                    val confirmed = server.toItem()
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(id, confirmed),
                            searchResults = it.searchResults.replace(id, confirmed),
                            detail = it.detail?.replace(id, confirmed),
                            mutatingIds = it.mutatingIds - id,
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(id, previous),
                            searchResults = it.searchResults.replace(id, previous),
                            detail = it.detail?.replace(id, previous),
                            mutatingIds = it.mutatingIds - id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun prepareDelete(item: TaskItem, onReady: (Int) -> Unit) {
        if (!item.canDelete || item.id in _ui.value.mutatingIds) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.loadSubtreeImpact(item.id).fold(
                onSuccess = { impact ->
                    _ui.update { it.copy(mutatingIds = it.mutatingIds - item.id) }
                    onReady(impact.nodeCount.coerceAtLeast(1))
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Delete,
                        )
                    }
                },
            )
        }
    }

    fun deleteTask(item: TaskItem, confirmedNodeCount: Int, onDeleted: () -> Unit) {
        if (!item.canDelete || item.id in _ui.value.mutatingIds) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.deleteTask(item.id, confirmedNodeCount).fold(
                onSuccess = {
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.filterNot { task -> task.id == item.id },
                            searchResults = it.searchResults.filterNot { task -> task.id == item.id },
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                    refreshNavigation()
                    refresh()
                    onDeleted()
                },
                onFailure = {
                    _ui.update { it.copy(mutatingIds = it.mutatingIds - item.id, failure = TaskFailure.Delete) }
                },
            )
        }
    }

    fun sendComment(item: TaskItem, content: String, onSent: () -> Unit) {
        if (!item.canComment || content.isBlank()) return
        viewModelScope.launch {
            repository.createComment(item.id, content.trim()).fold(
                onSuccess = { comment ->
                    val updated = item.copy(commentCount = item.commentCount + 1)
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, updated),
                            detail = it.detail?.copy(
                                comments = it.detail.comments + TaskCommentItem(
                                    id = comment.id,
                                    author = comment.author?.displayName.orEmpty(),
                                    authorId = comment.author?.id.orEmpty(),
                                    authorAvatarUrl = comment.author?.avatarUrl.orEmpty(),
                                    content = comment.content,
                                    createdAt = comment.createdAt,
                                ),
                            ),
                        )
                    }
                    onSent()
                },
                onFailure = { _ui.update { it.copy(failure = TaskFailure.Comment) } },
            )
        }
    }

    fun createSubtask(parent: TaskItem, title: String) {
        if (!parent.canCreateSubtasks || title.isBlank()) return
        viewModelScope.launch {
            repository.createTask(
                title = title.trim(),
                description = "",
                assigneeIds = selfUserId?.let(::listOf),
                followerIds = null,
                startDate = null,
                dueDate = null,
                priority = null,
                taskListId = parent.listId,
                groupId = parent.groupId,
                parentId = parent.id,
            ).fold(
                onSuccess = { created ->
                    _ui.update {
                        it.copy(
                            detail = it.detail?.copy(
                                subtasks = it.detail.subtasks + created.toItem(),
                            ),
                        )
                    }
                },
                onFailure = { _ui.update { it.copy(failure = TaskFailure.Save) } },
            )
        }
    }

    fun moveSubtask(parent: TaskItem, subtaskId: String, offset: Int) {
        val detail = _ui.value.detail?.takeIf { it.taskId == parent.id } ?: return
        if (
            parent.id in _ui.value.mutatingIds ||
            !parent.canEdit ||
            detail.subtasks.any { !it.canEdit }
        ) return
        val fromIndex = detail.subtasks.indexOfFirst { it.id == subtaskId }
        val toIndex = fromIndex + offset
        if (fromIndex < 0 || toIndex !in detail.subtasks.indices) return
        val previous = detail.subtasks
        val reordered = previous.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        _ui.update {
            it.copy(
                detail = it.detail?.copy(subtasks = reordered),
                mutatingIds = it.mutatingIds + parent.id,
                failure = null,
            )
        }
        viewModelScope.launch {
            repository.reorderSubtasks(parent.id, reordered.map(TaskItem::id)).fold(
                onSuccess = { confirmed ->
                    _ui.update {
                        it.copy(
                            detail = it.detail?.takeIf { current -> current.taskId == parent.id }
                                ?.copy(subtasks = confirmed.map(TaskDto::toItem)) ?: it.detail,
                            mutatingIds = it.mutatingIds - parent.id,
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            detail = it.detail?.takeIf { current -> current.taskId == parent.id }
                                ?.copy(subtasks = previous) ?: it.detail,
                            mutatingIds = it.mutatingIds - parent.id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun setRecurrence(item: TaskItem, settings: TaskRecurrenceSettings?) {
        val canManage = item.recurrence?.canManage ?: (item.creatorId == selfUserId)
        if (!canManage || item.parentId != null || item.id in _ui.value.mutatingIds) return
        if (settings == null && item.recurrence?.active != true) return
        if (settings != null && (
                settings.interval !in 1..365 ||
                    settings.endDate != null && settings.maxOccurrences != null ||
                    settings.maxOccurrences != null && settings.maxOccurrences !in 1..1000
                )
        ) {
            return
        }
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            val request = if (settings == null) {
                repository.stopRecurrence(item.id)
            } else {
                repository.setRecurrence(
                    taskId = item.id,
                    frequency = settings.frequency.apiValue,
                    interval = settings.interval,
                    endDate = settings.endDate,
                    maxOccurrences = settings.maxOccurrences,
                )
            }
            request.fold(
                onSuccess = { updated ->
                    val confirmed = updated.toItem()
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, confirmed),
                            searchResults = it.searchResults.replace(item.id, confirmed),
                            detail = it.detail?.replace(item.id, confirmed),
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun moveTask(item: TaskItem, parentId: String?, subtreeNodeCount: Int) {
        if (
            !item.canEdit ||
            item.recurrence?.active == true ||
            item.parentId == parentId ||
            item.id in _ui.value.mutatingIds
        ) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.moveTask(item.id, parentId, subtreeNodeCount).fold(
                onSuccess = { updated ->
                    val confirmed = updated.toItem()
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, confirmed),
                            searchResults = it.searchResults.replace(item.id, confirmed),
                            detail = it.detail?.replace(item.id, confirmed),
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                    loadDetail(item.id)
                    refresh()
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            mutatingIds = it.mutatingIds - item.id,
                            failure = TaskFailure.Save,
                        )
                    }
                },
            )
        }
    }

    fun uploadAttachment(task: TaskItem, uri: Uri) {
        if (ui.value.detail?.uploadingAttachment == true) return
        _ui.update {
            it.copy(
                detail = it.detail?.copy(uploadingAttachment = true),
                failure = null,
            )
        }
        viewModelScope.launch {
            repository.uploadAttachment(task.id, uri).fold(
                onSuccess = { attachment ->
                    _ui.update {
                        it.copy(
                            detail = it.detail?.copy(
                                uploadingAttachment = false,
                                attachments = it.detail.attachments + TaskAttachmentItem(
                                    id = attachment.id,
                                    filename = attachment.filename,
                                    mimeType = attachment.mimetype,
                                    downloadUrl = attachment.url,
                                    size = attachment.size,
                                    uploader = attachment.uploader?.displayName.orEmpty(),
                                ),
                            ),
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            detail = it.detail?.copy(uploadingAttachment = false),
                            failure = TaskFailure.Attachment,
                        )
                    }
                },
            )
        }
    }

    fun deleteAttachment(taskId: String, attachmentId: String) {
        viewModelScope.launch {
            repository.deleteAttachment(taskId, attachmentId).fold(
                onSuccess = {
                    _ui.update {
                        it.copy(
                            detail = it.detail?.copy(
                                attachments = it.detail.attachments.filterNot { attachment ->
                                    attachment.id == attachmentId
                                },
                            ),
                        )
                    }
                },
                onFailure = { _ui.update { it.copy(failure = TaskFailure.Attachment) } },
            )
        }
    }

    fun downloadAttachment(attachment: TaskAttachmentItem, destination: Uri) {
        if (attachment.id in ui.value.detail?.downloadingAttachmentIds.orEmpty()) return
        if (attachment.downloadUrl.isBlank()) {
            _ui.update { it.copy(failure = TaskFailure.Attachment) }
            return
        }
        _ui.update {
            it.copy(
                detail = it.detail?.copy(
                    downloadingAttachmentIds = it.detail.downloadingAttachmentIds + attachment.id,
                ),
                failure = null,
            )
        }
        viewModelScope.launch {
            repository.downloadAttachment(attachment.downloadUrl, destination).fold(
                onSuccess = {
                    _ui.update {
                        it.copy(
                            detail = it.detail?.copy(
                                downloadingAttachmentIds =
                                    it.detail.downloadingAttachmentIds - attachment.id,
                            ),
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            detail = it.detail?.copy(
                                downloadingAttachmentIds =
                                    it.detail.downloadingAttachmentIds - attachment.id,
                            ),
                            failure = TaskFailure.Attachment,
                        )
                    }
                },
            )
        }
    }

    fun shareTask(item: TaskItem, conversationIds: List<String>, onShared: (List<String>) -> Unit) {
        if (!item.canEdit || conversationIds.isEmpty()) return
        viewModelScope.launch {
            repository.shareTask(item.id, conversationIds).fold(
                onSuccess = onShared,
                onFailure = { _ui.update { it.copy(failure = TaskFailure.Share) } },
            )
        }
    }

    fun search(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        scheduleSearch()
    }

    fun setSearchFilter(filter: TaskSearchFilter) {
        if (_ui.value.searchFilter == filter) return
        _ui.update { it.copy(searchFilter = filter) }
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val snapshot = _ui.value
        val query = snapshot.searchQuery.trim()
        if (
            (query.isNotEmpty() && query.length < 2) ||
            (query.isEmpty() && !snapshot.searchFilter.isActive)
        ) {
            _ui.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _ui.update { it.copy(searching = true) }
            val filter = _ui.value.searchFilter
            repository.searchTasks(
                query = _ui.value.searchQuery,
                creatorId = selfUserId?.takeIf { filter.creatorSelf && it.isNotBlank() },
                assigneeId = selfUserId?.takeIf { filter.assigneeSelf && it.isNotBlank() },
                status = filter.status.apiValue,
                due = filter.due.apiValue,
                priority = filter.priority?.name?.lowercase() ?: "all",
            ).fold(
                onSuccess = { results ->
                    _ui.update { it.copy(searchResults = results.map(TaskDto::toItem), searching = false) }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update { it.copy(searching = false, failure = TaskFailure.Load) }
                },
            )
        }
    }

    fun createListGroup(
        name: String,
        insertIndex: Int? = null,
        onCreated: () -> Unit = {},
    ) {
        if (name.isBlank() || _ui.value.navigationMutating) return
        val existing = _ui.value.listGroups.sortedBy(TaskListGroupItem::sortOrder)
        val targetIndex = insertIndex?.coerceIn(0, existing.size)
        if (targetIndex != null && existing.any { !it.canManage }) return
        val requestedSortOrder = targetIndex
            ?: (existing.maxOfOrNull(TaskListGroupItem::sortOrder)?.plus(1) ?: 0)
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.createListGroup(name.trim(), requestedSortOrder).fold(
                onSuccess = { created ->
                    if (targetIndex == null) {
                        _ui.update {
                            it.copy(
                                navigationMutating = false,
                                listGroups = (it.listGroups + created.toItem()).sortedBy(
                                    TaskListGroupItem::sortOrder,
                                ),
                            )
                        }
                        onCreated()
                    } else {
                        val ordered = existing.toMutableList().also {
                            it.add(targetIndex, created.toItem())
                        }
                        repository.reorderListGroups(
                            ordered.map(TaskListGroupItem::id),
                        ).fold(
                            onSuccess = { serverGroups ->
                                _ui.update {
                                    it.copy(
                                        navigationMutating = false,
                                        listGroups = serverGroups.sortedBy { group ->
                                            group.sortOrder
                                        }.map(TaskListGroupDto::toItem),
                                    )
                                }
                                onCreated()
                            },
                            onFailure = {
                                navigationMutationFailed()
                                refreshNavigation()
                                onCreated()
                            },
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
                    }
                },
            )
        }
    }

    fun renameListGroup(group: TaskListGroupItem, name: String) {
        if (!group.canManage || name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.renameListGroup(group.id, name.trim()).fold(
                onSuccess = { updated ->
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            listGroups = state.listGroups.map {
                                if (it.id == group.id) updated.toItem() else it
                            },
                            taskLists = state.taskLists.map {
                                if (it.groupId == group.id) it.copy(groupName = updated.name) else it
                            },
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
                    }
                },
            )
        }
    }

    fun reorderListGroups(groups: List<TaskListGroupItem>) {
        if (
            groups.size < 2 ||
            groups.any { !it.canManage } ||
            _ui.value.navigationMutating
        ) {
            return
        }
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.reorderListGroups(groups.map(TaskListGroupItem::id)).fold(
                onSuccess = { serverGroups ->
                    _ui.update {
                        it.copy(
                            navigationMutating = false,
                            listGroups = serverGroups.sortedBy { group -> group.sortOrder }
                                .map(TaskListGroupDto::toItem),
                        )
                    }
                },
                onFailure = {
                    navigationMutationFailed()
                    refreshNavigation()
                },
            )
        }
    }

    fun deleteListGroup(group: TaskListGroupItem) {
        if (!group.canManage || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.deleteListGroup(group.id).fold(
                onSuccess = {
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            listGroups = state.listGroups.filterNot { it.id == group.id },
                            taskLists = state.taskLists.map {
                                if (it.groupId == group.id) {
                                    it.copy(groupId = null, groupName = null)
                                } else {
                                    it
                                }
                            },
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
                    }
                },
            )
        }
    }

    fun createTaskList(
        name: String,
        description: String,
        color: TaskListColor,
        groupId: String?,
        onCreated: () -> Unit,
    ) {
        if (name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.createTaskList(
                name = name.trim(),
                description = description.trim(),
                color = color.apiValue,
                listGroupId = groupId,
            ).fold(
                onSuccess = { created ->
                    _ui.update {
                        it.copy(
                            navigationMutating = false,
                            taskLists = it.taskLists + created.toItem(),
                        )
                    }
                    onCreated()
                },
                onFailure = {
                    _ui.update {
                        it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
                    }
                },
            )
        }
    }

    fun updateTaskListDetails(
        list: TaskListItem,
        name: String,
        description: String,
        color: TaskListColor,
    ) {
        if (!list.canManage || name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.updateTaskListDetails(
                id = list.id,
                name = name.trim(),
                description = description.trim(),
                color = color.apiValue,
            ).fold(
                onSuccess = { updated ->
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskLists = state.taskLists.map {
                                if (it.id == list.id) updated.toItem() else it
                            },
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
                    }
                },
            )
        }
    }

    fun moveTaskList(list: TaskListItem, listGroupId: String?) {
        if (
            !list.canManage || list.groupId == listGroupId ||
            _ui.value.navigationMutating
        ) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.moveTaskList(list.id, listGroupId).fold(
                onSuccess = { updated ->
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskLists = state.taskLists.map {
                                if (it.id == list.id) updated.toItem() else it
                            },
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
                    }
                },
            )
        }
    }

    fun loadArchivedTaskLists() {
        if (_ui.value.archivedListsLoading) return
        _ui.update { it.copy(archivedListsLoading = true, failure = null) }
        viewModelScope.launch {
            repository.loadArchivedTaskLists().fold(
                onSuccess = { lists ->
                    _ui.update {
                        it.copy(
                            archivedListsLoading = false,
                            archivedTaskLists = lists.map(TaskListDto::toItem),
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            archivedListsLoading = false,
                            failure = TaskFailure.Navigation,
                        )
                    }
                },
            )
        }
    }

    fun archiveTaskList(list: TaskListItem) {
        if (!list.canArchive || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.setTaskListArchived(list.id, true).fold(
                onSuccess = { archived ->
                    val wasSelected = _ui.value.selectedListId == list.id
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            selectedListId = state.selectedListId.takeUnless { it == list.id },
                            taskLists = state.taskLists.filterNot { it.id == list.id },
                            archivedTaskLists = state.archivedTaskLists + archived.toItem(),
                        )
                    }
                    if (wasSelected) refresh()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun restoreTaskList(list: TaskListItem) {
        if (!list.canArchive || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.setTaskListArchived(list.id, false).fold(
                onSuccess = { restored ->
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            archivedTaskLists = state.archivedTaskLists.filterNot {
                                it.id == list.id
                            },
                            taskLists = state.taskLists + restored.toItem(),
                        )
                    }
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun loadTaskListMembers(list: TaskListItem) {
        if (!list.canShare) return
        if (_ui.value.taskListMembersFor == list.id && _ui.value.taskListMembersLoading) return
        taskListMembersJob?.cancel()
        _ui.update {
            it.copy(
                taskListMembersFor = list.id,
                taskListMembers = emptyList(),
                taskListMembersLoading = true,
                failure = null,
            )
        }
        taskListMembersJob = viewModelScope.launch {
            repository.loadTaskListMembers(list.id).fold(
                onSuccess = { members ->
                    _ui.update {
                        if (it.taskListMembersFor != list.id) it else {
                            it.copy(
                                taskListMembersLoading = false,
                                taskListMembers = members.map { member ->
                                    member.toItem(selfUserId)
                                },
                            )
                        }
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update {
                        if (it.taskListMembersFor != list.id) it else {
                            it.copy(
                                taskListMembersLoading = false,
                                failure = TaskFailure.Navigation,
                            )
                        }
                    }
                },
            )
        }
    }

    fun addTaskListMember(list: TaskListItem, userId: String) {
        if (!list.canShare || userId.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.shareTaskList(list.id, userId).fold(
                onSuccess = { access ->
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskListMembers = state.taskListMembers
                                .filterNot { it.userId == userId } + access.toItem(selfUserId),
                        )
                    }
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun updateTaskListMemberRole(
        list: TaskListItem,
        member: TaskListMemberItem,
        role: TaskListRole,
    ) {
        if (!list.canShare || member.role == TaskListRole.Owner || member.isSelf ||
            role == TaskListRole.Owner || role == member.role || _ui.value.navigationMutating
        ) {
            return
        }
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.updateTaskListMemberRole(list.id, member.userId, role.apiValue).fold(
                onSuccess = { access ->
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskListMembers = state.taskListMembers.map {
                                if (it.userId == member.userId) access.toItem(selfUserId) else it
                            },
                        )
                    }
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun removeTaskListMember(list: TaskListItem, member: TaskListMemberItem) {
        if (!list.canShare || member.role == TaskListRole.Owner || member.isSelf ||
            _ui.value.navigationMutating
        ) {
            return
        }
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.removeTaskListMember(list.id, member.userId).fold(
                onSuccess = {
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskListMembers = state.taskListMembers.filterNot {
                                it.userId == member.userId
                            },
                        )
                    }
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun leaveTaskList(list: TaskListItem) {
        if (!list.canRemove || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.leaveTaskList(list.id).fold(
                onSuccess = {
                    val wasSelected = _ui.value.selectedListId == list.id
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            selectedListId = state.selectedListId.takeUnless { it == list.id },
                            taskLists = state.taskLists.filterNot { it.id == list.id },
                        )
                    }
                    refreshNavigation()
                    if (wasSelected) refresh()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun deleteTaskList(list: TaskListItem, deleteUnassigned: Boolean) {
        if (!list.canDelete || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.deleteTaskList(list.id, deleteUnassigned).fold(
                onSuccess = {
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            selectedListId = state.selectedListId.takeUnless { it == list.id },
                            taskLists = state.taskLists.filterNot { it.id == list.id },
                        )
                    }
                    refreshNavigation()
                    refresh()
                },
                onFailure = {
                    _ui.update {
                        it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
                    }
                },
            )
        }
    }

    fun createTaskGroup(
        list: TaskListItem,
        name: String,
        insertIndex: Int = list.groups.size,
        onCreated: () -> Unit = {},
    ) {
        if (!list.canManage || name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.createTaskGroup(list.id, name.trim(), insertIndex).fold(
                onSuccess = { created ->
                    val ordered = list.groups.sortedBy(TaskGroupItem::sortOrder).toMutableList()
                    ordered.add(insertIndex.coerceIn(0, ordered.size), created.toItem())
                    repository.reorderTaskGroups(ordered.map(TaskGroupItem::id)).fold(
                        onSuccess = { serverGroups ->
                            updateTaskGroups(
                                list.id,
                                serverGroups.sortedBy { it.sortOrder }.map(TaskGroupDto::toItem),
                            )
                            onCreated()
                        },
                        onFailure = { navigationMutationFailed() },
                    )
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun renameTaskGroup(group: TaskGroupItem, name: String) {
        if (!group.canManage || name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.renameTaskGroup(group.id, name.trim()).fold(
                onSuccess = { updated ->
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskGroups = state.taskGroups.map {
                                if (it.id == group.id) updated.toItem() else it
                            },
                            tasks = state.tasks.map {
                                if (it.groupId == group.id) {
                                    it.copy(groupName = updated.name)
                                } else {
                                    it
                                }
                            },
                        )
                    }
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun moveTaskGroup(group: TaskGroupItem, direction: Int) {
        val groups = _ui.value.taskGroups.sortedBy(TaskGroupItem::sortOrder)
        val index = groups.indexOfFirst { it.id == group.id }
        val target = groups.getOrNull(index + direction) ?: return
        if (!group.canManage || !target.canManage || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.swapTaskGroups(
                group.id,
                group.sortOrder,
                target.id,
                target.sortOrder,
            ).fold(
                onSuccess = { serverGroups ->
                    val updated = serverGroups.associateBy(TaskGroupDto::id)
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskGroups = state.taskGroups.map { item ->
                                updated[item.id]?.toItem() ?: item
                            }.sortedBy(TaskGroupItem::sortOrder),
                        )
                    }
                },
                onFailure = {
                    navigationMutationFailed()
                    refreshNavigation()
                },
            )
        }
    }

    fun deleteTaskGroup(group: TaskGroupItem) {
        if (!group.canManage || !group.canDelete || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.deleteTaskGroup(group.id).fold(
                onSuccess = {
                    val wasSelected = _ui.value.selectedGroupId == group.id
                    val fallbackPreferences = viewPreferences[TaskView.All.preferenceKey]
                        ?: TaskViewPreferences()
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            taskGroups = state.taskGroups.filterNot { it.id == group.id },
                            selectedGroupId = state.selectedGroupId.takeUnless { wasSelected },
                            activeSavedViewId = state.activeSavedViewId.takeUnless { wasSelected },
                            invalidGroupSelection = state.invalidGroupSelection || wasSelected,
                            view = if (wasSelected) TaskView.All else state.view,
                            selectedListId = if (wasSelected) null else state.selectedListId,
                            status = if (wasSelected) fallbackPreferences.status else state.status,
                            time = if (wasSelected) fallbackPreferences.time else state.time,
                            priorityFilter = if (wasSelected) {
                                fallbackPreferences.priority
                            } else {
                                state.priorityFilter
                            },
                            grouping = if (wasSelected) {
                                fallbackPreferences.grouping
                            } else {
                                state.grouping
                            },
                            ordering = if (wasSelected) {
                                fallbackPreferences.ordering
                            } else {
                                state.ordering
                            },
                        )
                    }
                    refreshNavigation()
                    if (wasSelected) refresh()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun createTaskGroup(
        name: String,
        insertIndex: Int = _ui.value.taskGroups.size,
        onCreated: () -> Unit = {},
    ) {
        if (name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.createTaskGroup(name.trim(), insertIndex).fold(
                onSuccess = { created ->
                    _ui.update { state ->
                        val groups = state.taskGroups.toMutableList()
                        groups.add(insertIndex.coerceIn(0, groups.size), created.toItem())
                        state.copy(
                            navigationMutating = false,
                            taskGroups = groups,
                        )
                    }
                    onCreated()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun renameTaskGroup(list: TaskListItem, group: TaskGroupItem, name: String) {
        if (!list.canManage || name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.renameTaskGroup(group.id, name.trim()).fold(
                onSuccess = { updated ->
                    updateTaskGroups(
                        list.id,
                        list.groups.map { if (it.id == group.id) updated.toItem() else it },
                    )
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun reorderTaskGroups(list: TaskListItem, groups: List<TaskGroupItem>) {
        if (!list.canManage || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.reorderTaskGroups(groups.map(TaskGroupItem::id)).fold(
                onSuccess = { serverGroups ->
                    updateTaskGroups(
                        list.id,
                        serverGroups.sortedBy { it.sortOrder }.map(TaskGroupDto::toItem),
                    )
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun deleteTaskGroup(list: TaskListItem, group: TaskGroupItem) {
        if (!list.canManage || !group.canDelete || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.deleteTaskGroup(group.id).fold(
                onSuccess = {
                    updateTaskGroups(list.id, list.groups.filterNot { it.id == group.id })
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    private fun updateTaskGroups(listId: String, groups: List<TaskGroupItem>) {
        _ui.update { state ->
            state.copy(
                navigationMutating = false,
                taskLists = state.taskLists.map { list ->
                    if (list.id == listId) list.copy(groups = groups) else list
                },
            )
        }
    }

    private fun navigationMutationFailed() {
        _ui.update {
            it.copy(navigationMutating = false, failure = TaskFailure.Navigation)
        }
        refreshNavigation()
    }

    fun clearFailure() = _ui.update { it.copy(failure = null) }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TaskViewModel(app.taskRepository, app.tokenStore.userId) as T
    }
}

private val TaskView.preferenceKey: String
    get() = if (this == TaskView.Standalone) "standalone" else "quick:${apiScope}"

private val TaskUiState.preferenceKey: String
    get() = selectedGroupId?.let { "task-group:$it" }
        ?: selectedListId?.let { "task-list:$it" }
        ?: view.preferenceKey

private val TaskUiState.preferences: TaskViewPreferences
    get() = TaskViewPreferences(status, time, priorityFilter, grouping, ordering)

private val savedViewComparator = compareByDescending<TaskSavedViewItem> { it.isPinned }
    .thenBy(TaskSavedViewItem::position)
    .thenBy(TaskSavedViewItem::name)

private val TaskView.apiScope: String
    get() = when (this) {
        TaskView.Assigned -> "assigned"
        TaskView.Following -> "following"
        TaskView.Created -> "created"
        TaskView.All, TaskView.Standalone -> "all"
    }

private val TaskGrouping.apiValue: String
    get() = when (this) {
        TaskGrouping.None -> "none"
        TaskGrouping.Custom -> "custom"
        TaskGrouping.List -> "task_list"
        TaskGrouping.StartDate -> "start_date"
        TaskGrouping.DueDate -> "due_date"
        TaskGrouping.Creator -> "creator"
    }

private fun String.toTaskView(): TaskView = when (this) {
    "assigned" -> TaskView.Assigned
    "following" -> TaskView.Following
    "created" -> TaskView.Created
    else -> TaskView.All
}

private fun String.toTaskListStatus(): TaskListStatus =
    TaskListStatus.entries.firstOrNull { it.apiValue == this } ?: TaskListStatus.Open

private fun String.toTaskTimeFilter(): TaskTimeFilter =
    TaskTimeFilter.entries.firstOrNull { it.apiValue == this } ?: TaskTimeFilter.All

private fun String.toTaskPriorityFilter(): TaskPriority? = when (this) {
    "none" -> TaskPriority.None
    "low" -> TaskPriority.Low
    "medium" -> TaskPriority.Medium
    "high" -> TaskPriority.High
    "urgent" -> TaskPriority.Urgent
    else -> null
}

private fun String.toTaskGrouping(): TaskGrouping = when (this) {
    "custom" -> TaskGrouping.Custom
    "task_list" -> TaskGrouping.List
    "start_date" -> TaskGrouping.StartDate
    "due_date" -> TaskGrouping.DueDate
    "creator" -> TaskGrouping.Creator
    else -> TaskGrouping.None
}

internal fun TaskSavedViewDto.toItem(): TaskSavedViewItem = TaskSavedViewItem(
    id = id,
    name = name,
    scope = config.scope.toTaskView(),
    preferences = TaskViewPreferences(
        status = config.status.toTaskListStatus(),
        time = config.time.toTaskTimeFilter(),
        priority = config.priority.toTaskPriorityFilter(),
        grouping = config.grouping.toTaskGrouping(),
        ordering = TaskOrdering.fromApiValue(config.ordering),
    ),
    taskListId = config.taskList.takeUnless { it == "all" },
    groupId = config.group.takeUnless { it == "all" },
    position = position,
    isPinned = isPinned,
    isDefault = isDefault,
    invalidTaskList = invalidTaskList,
    invalidTaskGroup = invalidTaskGroup,
    version = config.version,
    columns = config.columns,
    columnOrder = config.columnOrder,
)

internal fun TaskUiState.toSavedViewConfig(
    existing: TaskSavedViewItem? = null,
): TaskSavedViewConfigDto = TaskSavedViewConfigDto(
    version = 4,
    scope = view.apiScope,
    status = status.apiValue,
    time = time.apiValue,
    priority = priorityFilter?.name?.lowercase() ?: "all",
    taskList = if (view == TaskView.Standalone) "unassigned" else selectedListId ?: "all",
    group = selectedGroupId ?: "all",
    ordering = ordering.apiValue.orEmpty(),
    view = "list",
    grouping = grouping.apiValue,
    columns = existing?.columns?.takeIf(List<String>::isNotEmpty)
        ?: DEFAULT_TASK_SAVED_VIEW_COLUMNS,
    columnOrder = existing?.columnOrder?.takeIf(List<String>::isNotEmpty)
        ?: DEFAULT_TASK_SAVED_VIEW_COLUMN_ORDER,
)

internal fun TaskDto.toItem(): TaskItem {
    val people = assignees.ifEmpty { listOfNotNull(assignee) }
    return TaskItem(
        id = id,
        creatorId = creator.id,
        creatorName = creator.displayName,
        creatorAvatarUrl = creator.avatarUrl,
        title = title,
        description = description,
        assignee = people.joinToString { it.displayName }.ifBlank { creator.displayName },
        assigneeAvatarUrl = people.firstOrNull()?.avatarUrl ?: creator.avatarUrl,
        dueLabel = taskDateRangeLabel(startDate, dueDate),
        listId = taskList?.id,
        listName = taskList?.name.orEmpty(),
        section = group?.name ?: taskList?.name ?: "—",
        status = if (status == "completed") TaskStatus.Done else TaskStatus.Todo,
        timeState = timeState.toTaskTimeState(),
        priority = when (priority) {
            "none" -> TaskPriority.None
            "low" -> TaskPriority.Low
            "high" -> TaskPriority.High
            "urgent" -> TaskPriority.Urgent
            else -> TaskPriority.Medium
        },
        followed = isFollowing,
        subtaskProgress = descendantProgress.takeIf { it.total > 0 }
            ?.let { it.completed to it.total },
        canUpdateStatus = canUpdateStatus,
        canDelete = canDelete,
        canComment = canComment,
        canManageAttachments = canManageAttachments,
        canCreateSubtasks = canCreateSubtasks,
        canEdit = canEdit,
        canManageFollowers = canManageFollowers,
        canManageReminder = canManageReminder,
        assignees = people.map { person ->
            TaskPersonItem(person.id, person.displayName, person.avatarUrl)
        },
        followers = followers.map { person ->
            TaskPersonItem(person.id, person.displayName, person.avatarUrl)
        },
        startDate = startDate,
        dueDate = dueDate,
        groupId = group?.id,
        groupName = group?.name,
        completedAt = completedAt,
        createdAt = createdAt,
        parentId = parentId,
        parentTitle = ancestorPath.dropLast(1).lastOrNull()?.title,
        recurrence = recurrence?.let { rule ->
            TaskRecurrenceItem(
                frequency = when (rule.frequency) {
                    "weekly" -> TaskRecurrenceFrequency.Weekly
                    "monthly" -> TaskRecurrenceFrequency.Monthly
                    else -> TaskRecurrenceFrequency.Daily
                },
                interval = rule.interval,
                endDate = rule.endDate,
                maxOccurrences = rule.maxOccurrences,
                generatedCount = rule.generatedCount,
                nextOccurrenceDate = rule.nextOccurrenceDate,
                active = rule.isActive,
                sequence = rule.sequence,
                canManage = rule.canManage,
            )
        },
    )
}

internal fun TaskRepository.Detail.toItem(taskId: String): TaskDetailItem {
    val mappedTask = task.toItem().copy(commentCount = comments.size)
    return TaskDetailItem(
        taskId = taskId,
        task = mappedTask,
        subtasks = subtasks.map(TaskDto::toItem),
        comments = comments.map { comment ->
            TaskCommentItem(
                id = comment.id,
                author = comment.author?.displayName.orEmpty(),
                authorId = comment.author?.id.orEmpty(),
                authorAvatarUrl = comment.author?.avatarUrl.orEmpty(),
                content = comment.content,
                createdAt = comment.createdAt,
            )
        },
        attachments = attachments.map { attachment ->
            TaskAttachmentItem(
                id = attachment.id,
                filename = attachment.filename,
                mimeType = attachment.mimetype,
                downloadUrl = attachment.url,
                size = attachment.size,
                uploader = attachment.uploader?.displayName.orEmpty(),
            )
        },
        activities = activities.map { activity ->
            activity.toItem()
        },
        reminder = reminder?.let { preference ->
            TaskReminderItem(
                enabled = preference.enabled,
                reminderMinutes = preference.reminderMinutes,
                effectiveReminderMinutes = preference.effectiveReminderMinutes,
                globalRemindersEnabled = preference.globalRemindersEnabled,
            )
        },
        parentCandidates = parentCandidates.map { candidate ->
            TaskParentCandidateItem(
                id = candidate.id,
                title = candidate.title,
                depth = candidate.depth,
            )
        },
        subtreeNodeCount = subtreeNodeCount,
    )
}

internal fun TaskActivityDto.toItem() = TaskActivityItem(
    id = id,
    taskId = taskId,
    taskTitle = taskTitle,
    actor = actor?.displayName.orEmpty(),
    event = event,
    createdAt = createdAt,
)

internal fun TaskSettingsDto.toItem() = TaskSettingsItem(
    dailyReminderEnabled = dailyReminderEnabled,
    overdueMarkerEnabled = overdueMarkerEnabled,
    defaultReminderMinutes = defaultReminderMinutes,
)

private fun TaskListDto.toItem() = TaskListItem(
    id = id,
    name = name,
    description = description,
    color = color.toTaskListColor(),
    groupId = listGroup?.id,
    groupName = listGroup?.name,
    isArchived = isArchived,
    taskCount = taskCount,
    canCreateTasks = canCreateTasks,
    accessRole = accessRole.toTaskListRole(),
    canManage = canManage,
    canShare = canShare,
    canArchive = canArchive,
    canRemove = canRemove,
    canDelete = canDelete,
    groups = groups.sortedBy { it.sortOrder }.map(TaskGroupDto::toItem),
)

private fun String.toTaskListColor(): TaskListColor =
    TaskListColor.entries.firstOrNull { it.apiValue == this } ?: TaskListColor.Blue

private fun TaskListAccessDto.toItem(selfUserId: String?) = TaskListMemberItem(
    id = id,
    userId = user.id,
    name = user.displayName.ifBlank { "—" },
    avatarUrl = user.avatarUrl.takeIf(String::isNotBlank),
    role = role.toTaskListRole() ?: TaskListRole.Viewer,
    isSelf = user.id == selfUserId,
)

private fun String?.toTaskListRole(): TaskListRole? = when (this) {
    "viewer" -> TaskListRole.Viewer
    "editor" -> TaskListRole.Editor
    "owner" -> TaskListRole.Owner
    else -> null
}

private val TaskListRole.apiValue: String
    get() = name.lowercase()

private fun TaskGroupDto.toItem() = TaskGroupItem(
    id = id,
    name = name,
    sortOrder = sortOrder,
    taskCount = taskCount,
    canDelete = canDelete,
    canManage = canManage,
)

private fun TaskListGroupDto.toItem() = TaskListGroupItem(
    id = id,
    name = name,
    sortOrder = sortOrder,
    canManage = canManage,
)

private fun List<TaskItem>.replace(id: String, replacement: TaskItem): List<TaskItem> =
    map { if (it.id == id) replacement else it }

private fun TaskDetailItem.replace(id: String, replacement: TaskItem): TaskDetailItem = copy(
    task = if (task?.id == id) replacement else task,
    subtasks = subtasks.replace(id, replacement),
)

internal fun taskDateRangeLabel(startDate: String?, dueDate: String?): String = when {
    startDate != null && dueDate != null && startDate != dueDate -> "$startDate – $dueDate"
    dueDate != null -> dueDate
    startDate != null -> startDate
    else -> "—"
}
