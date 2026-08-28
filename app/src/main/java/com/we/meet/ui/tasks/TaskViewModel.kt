package com.we.meet.ui.tasks

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.repository.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TaskFailure { Load, Save, Delete, Comment, Attachment, Share }

data class TaskUiState(
    val tasks: List<TaskItem> = emptyList(),
    val taskLists: List<TaskListItem> = emptyList(),
    val listGroups: List<TaskListGroupItem> = emptyList(),
    val view: TaskView = TaskView.Assigned,
    val includeDone: Boolean = false,
    val selectedListId: String? = null,
    val loading: Boolean = true,
    val creating: Boolean = false,
    val mutatingIds: Set<String> = emptySet(),
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
        _ui.update { it.copy(selectedListId = listId) }
        refresh()
    }

    fun setIncludeDone(includeDone: Boolean) {
        if (_ui.value.includeDone == includeDone) return
        _ui.update { it.copy(includeDone = includeDone) }
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val snapshot = _ui.value
            _ui.update { it.copy(loading = true, failure = null) }
            repository.loadTasks(
                scope = snapshot.view.apiScope,
                includeCompleted = snapshot.includeDone,
                taskListId = snapshot.selectedListId,
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
                        taskLists = navigation.lists.map { list ->
                            TaskListItem(
                                id = list.id,
                                name = list.name,
                                groupId = list.listGroup?.id,
                                groupName = list.listGroup?.name,
                                taskCount = list.taskCount,
                                canCreateTasks = list.canCreateTasks,
                            )
                        },
                        listGroups = navigation.groups.sortedBy { it.sortOrder }
                            .map { TaskListGroupItem(it.id, it.name, it.sortOrder) },
                    )
                }
            }
        }
    }

    fun loadDetail(taskId: String) {
        _ui.update {
            it.copy(detail = TaskDetailItem(taskId = taskId, loading = true), failure = null)
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
                                        size = attachment.size,
                                        uploader = attachment.uploader?.displayName.orEmpty(),
                                    )
                                },
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
                detail = it.detail?.copy(subtasks = it.detail.subtasks.replace(id, optimistic)),
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
                            detail = it.detail?.copy(
                                subtasks = it.detail.subtasks.replace(id, confirmed),
                            ),
                            mutatingIds = it.mutatingIds - id,
                        )
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            tasks = it.tasks.replace(id, previous),
                            searchResults = it.searchResults.replace(id, previous),
                            detail = it.detail?.copy(
                                subtasks = it.detail.subtasks.replace(id, previous),
                            ),
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
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _ui.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _ui.update { it.copy(searching = true) }
            repository.loadTasks("all", true, null, query).fold(
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

    fun createListGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createListGroup(name.trim()).fold(
                onSuccess = { refreshNavigation() },
                onFailure = { _ui.update { it.copy(failure = TaskFailure.Save) } },
            )
        }
    }

    fun clearFailure() = _ui.update { it.copy(failure = null) }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TaskViewModel(app.taskRepository, app.tokenStore.userId) as T
    }
}

private val TaskView.apiScope: String
    get() = if (this == TaskView.Following) "following" else "assigned"

private fun TaskDto.toItem(): TaskItem {
    val people = assignees.ifEmpty { listOfNotNull(assignee) }
    return TaskItem(
        id = id,
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
    )
}

private fun List<TaskItem>.replace(id: String, replacement: TaskItem): List<TaskItem> =
    map { if (it.id == id) replacement else it }
