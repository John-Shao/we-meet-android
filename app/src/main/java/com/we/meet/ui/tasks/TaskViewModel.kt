package com.we.meet.ui.tasks

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskListDto
import com.we.meet.data.api.dto.TaskListAccessDto
import com.we.meet.data.api.dto.TaskListGroupDto
import com.we.meet.data.api.dto.PatchTaskRequest
import com.we.meet.data.api.dto.TaskGroupDto
import com.we.meet.data.repository.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TaskFailure { Load, Save, Delete, Comment, Attachment, Share, Navigation }

data class TaskUiState(
    val tasks: List<TaskItem> = emptyList(),
    val taskLists: List<TaskListItem> = emptyList(),
    val archivedTaskLists: List<TaskListItem> = emptyList(),
    val taskListMembers: List<TaskListMemberItem> = emptyList(),
    val taskListMembersFor: String? = null,
    val listGroups: List<TaskListGroupItem> = emptyList(),
    val navigationCounts: TaskNavigationCounts = TaskNavigationCounts(),
    val view: TaskView = TaskView.Assigned,
    val includeDone: Boolean = false,
    val grouping: TaskGrouping = TaskGrouping.List,
    val ordering: TaskOrdering = TaskOrdering.DueDate,
    val selectedListId: String? = null,
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
    val detail: TaskDetailItem? = null,
    val failure: TaskFailure? = null,
) {
    val selectedList: TaskListItem?
        get() = taskLists.firstOrNull { it.id == selectedListId }
}

class TaskViewModel(
    private val repository: TaskRepository,
    private val selfUserId: String?,
) : ViewModel() {
    private val _ui = MutableStateFlow(TaskUiState())
    val ui: StateFlow<TaskUiState> = _ui.asStateFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var taskListMembersJob: Job? = null

    init {
        refreshNavigation()
        refresh()
    }

    fun setView(view: TaskView) {
        if (_ui.value.view == view && _ui.value.selectedListId == null) return
        _ui.update { it.copy(view = view, selectedListId = null) }
        refresh()
    }

    fun selectList(listId: String?) {
        if (_ui.value.selectedListId == listId) return
        _ui.update {
            it.copy(
                selectedListId = listId,
                view = if (listId != null && it.view == TaskView.Standalone) {
                    TaskView.Assigned
                } else {
                    it.view
                },
            )
        }
        refresh()
    }

    fun applyListFilter(
        includeDone: Boolean,
        grouping: TaskGrouping,
        ordering: TaskOrdering,
    ) {
        val previous = _ui.value
        if (
            previous.includeDone == includeDone &&
            previous.grouping == grouping &&
            previous.ordering == ordering
        ) return
        _ui.update {
            it.copy(
                includeDone = includeDone,
                grouping = grouping,
                ordering = ordering,
            )
        }
        if (previous.includeDone != includeDone || previous.ordering != ordering) refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val snapshot = _ui.value
            _ui.update { it.copy(loading = true, failure = null) }
            repository.loadTasks(
                scope = snapshot.view.apiScope,
                status = snapshot.view.apiStatus(snapshot.includeDone),
                taskListId = if (snapshot.view == TaskView.Standalone) {
                    "unassigned"
                } else {
                    snapshot.selectedListId
                },
                ordering = snapshot.ordering.apiValue,
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
                _ui.update { state ->
                    state.copy(
                        taskLists = navigation.lists.map(TaskListDto::toItem),
                        listGroups = navigation.groups.sortedBy { it.sortOrder }
                            .map(TaskListGroupDto::toItem),
                        navigationCounts = TaskNavigationCounts(
                            assigned = navigation.counts.assigned,
                            following = navigation.counts.following,
                            created = navigation.counts.created,
                            all = navigation.counts.all,
                            completed = navigation.counts.completed,
                            standalone = navigation.counts.standalone,
                        ),
                    )
                }
            }
        }
    }

    fun loadDetail(taskId: String) {
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
        viewModelScope.launch {
            repository.loadDetail(taskId).fold(
                onSuccess = { detail ->
                    val task = detail.task.toItem().copy(commentCount = detail.comments.size)
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(taskId, task),
                            searchResults = it.searchResults.replace(taskId, task),
                            detail = TaskDetailItem(
                                taskId = taskId,
                                task = task,
                                subtasks = detail.subtasks.map(TaskDto::toItem),
                                comments = detail.comments.map { comment ->
                                    TaskCommentItem(
                                        id = comment.id,
                                        author = comment.author?.displayName.orEmpty(),
                                        content = comment.content,
                                        createdAt = comment.createdAt,
                                    )
                                },
                                attachments = detail.attachments.map { attachment ->
                                    TaskAttachmentItem(
                                        id = attachment.id,
                                        filename = attachment.filename,
                                        mimeType = attachment.mimetype,
                                        downloadUrl = attachment.url,
                                        size = attachment.size,
                                        uploader = attachment.uploader?.displayName.orEmpty(),
                                    )
                                },
                                activities = detail.activities.map { activity ->
                                    TaskActivityItem(
                                        id = activity.id,
                                        actor = activity.actor?.displayName.orEmpty(),
                                        event = activity.event,
                                        createdAt = activity.createdAt,
                                    )
                                },
                                parentCandidates = detail.parentCandidates.map { candidate ->
                                    TaskParentCandidateItem(
                                        id = candidate.id,
                                        title = candidate.title,
                                        depth = candidate.depth,
                                    )
                                },
                                subtreeNodeCount = detail.subtreeNodeCount,
                            ),
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update {
                        it.copy(
                            detail = it.detail?.copy(loading = false),
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
        dueDate: String?,
        taskListId: String?,
        onCreated: (TaskItem) -> Unit,
    ) {
        if (_ui.value.creating) return
        _ui.update { it.copy(creating = true, failure = null) }
        viewModelScope.launch {
            repository.createTask(title, description, selfUserId, dueDate, taskListId).fold(
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

    fun updateContent(item: TaskItem, title: String, description: String) {
        if (!item.canEdit || title.isBlank()) return
        updateTask(
            item,
            PatchTaskRequest(
                title = title.trim(),
                description = description.trim(),
                recurrenceScope = "one",
            ),
        )
    }

    fun updateDueDate(item: TaskItem, dueDate: String) {
        if (!item.canEdit) return
        updateTask(
            item,
            PatchTaskRequest(dueDate = dueDate, recurrenceScope = "one"),
        )
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

    fun addFollowers(item: TaskItem, userIds: List<String>) {
        if (!item.canManageFollowers || userIds.isEmpty() || item.id in _ui.value.mutatingIds) {
            return
        }
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.addFollowers(item.id, userIds).fold(
                onSuccess = { updated ->
                    val confirmed = updated.toItem()
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, confirmed),
                            searchResults = it.searchResults.replace(item.id, confirmed),
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

    fun removeFollower(item: TaskItem, followerId: String) {
        if (!item.canManageFollowers || item.id in _ui.value.mutatingIds) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.removeFollower(item.id, followerId).fold(
                onSuccess = {
                    val updated = item.copy(
                        followers = item.followers.filterNot { it.id == followerId },
                        followed = item.followed && followerId != selfUserId,
                    )
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(item.id, updated),
                            searchResults = it.searchResults.replace(item.id, updated),
                            detail = it.detail?.replace(item.id, updated),
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

    fun deleteTask(item: TaskItem, onDeleted: () -> Unit) {
        if (!item.canDelete || item.id in _ui.value.mutatingIds) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            repository.deleteTask(item.id).fold(
                onSuccess = {
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.filterNot { task -> task.id == item.id },
                            searchResults = it.searchResults.filterNot { task -> task.id == item.id },
                            mutatingIds = it.mutatingIds - item.id,
                        )
                    }
                    refreshNavigation()
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
                assigneeId = selfUserId,
                dueDate = null,
                taskListId = parent.listId,
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

    fun setRecurrence(item: TaskItem, frequency: TaskRecurrenceFrequency?) {
        val canManage = item.recurrence?.canManage ?: (item.creatorId == selfUserId)
        if (!canManage || item.parentId != null || item.id in _ui.value.mutatingIds) return
        if (frequency == null && item.recurrence?.active != true) return
        _ui.update { it.copy(mutatingIds = it.mutatingIds + item.id, failure = null) }
        viewModelScope.launch {
            val request = if (frequency == null) {
                repository.stopRecurrence(item.id)
            } else {
                repository.setRecurrence(
                    taskId = item.id,
                    frequency = frequency.apiValue,
                    interval = item.recurrence?.interval ?: 1,
                    endDate = item.recurrence?.endDate,
                    maxOccurrences = item.recurrence?.maxOccurrences,
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
        if (conversationIds.isEmpty()) return
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

    fun createListGroup(name: String, onCreated: () -> Unit = {}) {
        if (name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.createListGroup(name.trim()).fold(
                onSuccess = { created ->
                    _ui.update {
                        it.copy(
                            navigationMutating = false,
                            listGroups = (it.listGroups + created.toItem()).sortedBy(
                                TaskListGroupItem::sortOrder,
                            ),
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

    fun createTaskList(name: String, groupId: String?, onCreated: () -> Unit) {
        if (name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.createTaskList(name.trim(), groupId).fold(
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

    fun renameTaskList(list: TaskListItem, name: String) {
        if (!list.canManage || name.isBlank() || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.renameTaskList(list.id, name.trim()).fold(
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
                    if (wasSelected) refresh()
                },
                onFailure = { navigationMutationFailed() },
            )
        }
    }

    fun deleteTaskList(list: TaskListItem) {
        if (!list.canDelete || _ui.value.navigationMutating) return
        _ui.update { it.copy(navigationMutating = true, failure = null) }
        viewModelScope.launch {
            repository.deleteTaskList(list.id).fold(
                onSuccess = {
                    val wasSelected = _ui.value.selectedListId == list.id
                    _ui.update { state ->
                        state.copy(
                            navigationMutating = false,
                            selectedListId = state.selectedListId.takeUnless { it == list.id },
                            taskLists = state.taskLists.filterNot { it.id == list.id },
                        )
                    }
                    if (wasSelected) refresh()
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

private val TaskView.apiScope: String
    get() = when (this) {
        TaskView.Assigned -> "assigned"
        TaskView.Following -> "following"
        TaskView.Created -> "created"
        TaskView.All, TaskView.Completed, TaskView.Standalone -> "all"
    }

private fun TaskView.apiStatus(includeDone: Boolean): String = when (this) {
    TaskView.All -> "all"
    TaskView.Completed -> "completed"
    TaskView.Standalone -> if (includeDone) "all" else "open"
    else -> if (includeDone) "all" else "open"
}

private fun TaskDto.toItem(): TaskItem {
    val people = assignees.ifEmpty { listOfNotNull(assignee) }
    return TaskItem(
        id = id,
        creatorId = creator.id,
        title = title,
        description = description,
        assignee = people.joinToString { it.displayName }.ifBlank { creator.displayName },
        dueLabel = dueDate ?: startDate ?: "—",
        listId = taskList?.id,
        listName = taskList?.name ?: "—",
        section = group?.name ?: taskList?.name ?: "—",
        status = if (status == "completed") TaskStatus.Done else TaskStatus.Todo,
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
        assignees = people.map { person ->
            TaskPersonItem(person.id, person.displayName, person.avatarUrl)
        },
        followers = followers.map { person ->
            TaskPersonItem(person.id, person.displayName, person.avatarUrl)
        },
        startDate = startDate,
        dueDate = dueDate,
        groupId = group?.id,
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

private fun TaskListDto.toItem() = TaskListItem(
    id = id,
    name = name,
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
