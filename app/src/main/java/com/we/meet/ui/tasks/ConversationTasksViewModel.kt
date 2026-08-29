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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationTasksUiState(
    val tasks: List<TaskItem> = emptyList(),
    val loading: Boolean = true,
    val mutatingIds: Set<String> = emptySet(),
    val failed: Boolean = false,
    val detail: TaskDetailItem? = null,
    val detailFailed: Boolean = false,
    val detailActionRunning: Boolean = false,
    val detailActionFailure: TaskFailure? = null,
    val deletingAttachmentIds: Set<String> = emptySet(),
)

class ConversationTasksViewModel(
    private val repository: TaskRepository,
    private val conversationId: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(ConversationTasksUiState())
    val ui: StateFlow<ConversationTasksUiState> = _ui.asStateFlow()
    private val detailBackStack = mutableListOf<TaskDetailItem>()
    private var detailLoadJob: Job? = null
    private var detailActionJob: Job? = null

    fun refresh() {
        _ui.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            repository.loadConversationTasks(conversationId).fold(
                onSuccess = { tasks ->
                    _ui.update {
                        it.copy(
                            tasks = tasks.map(TaskDto::toItem),
                            loading = false,
                            failed = false,
                        )
                    }
                },
                onFailure = {
                    _ui.update { it.copy(loading = false, failed = true) }
                },
            )
        }
    }

    fun toggleCompleted(task: TaskItem) {
        if (!task.canUpdateStatus || task.id in _ui.value.mutatingIds) return
        val completed = task.status != TaskStatus.Done
        val optimistic = task.copy(
            status = if (completed) TaskStatus.Done else TaskStatus.Todo,
        )
        _ui.update { state ->
            state.copy(
                tasks = state.tasks.map {
                    if (it.id == task.id) optimistic else it
                },
                detail = state.detail?.let { detail ->
                    if (detail.taskId == task.id) detail.copy(task = optimistic) else detail
                },
                mutatingIds = state.mutatingIds + task.id,
                failed = false,
            )
        }
        viewModelScope.launch {
            repository.setCompleted(task.id, completed, sharedVia = conversationId).fold(
                onSuccess = { updated ->
                    _ui.update { state ->
                        val mapped = updated.toItem().copy(commentCount = task.commentCount)
                        state.copy(
                            tasks = state.tasks.map {
                                if (it.id == task.id) mapped else it
                            },
                            detail = state.detail?.let { detail ->
                                if (detail.taskId == task.id) detail.copy(task = mapped)
                                else detail
                            },
                            mutatingIds = state.mutatingIds - task.id,
                        )
                    }
                },
                onFailure = {
                    _ui.update { state ->
                        state.copy(
                            tasks = state.tasks.map {
                                if (it.id == task.id) task else it
                            },
                            detail = state.detail?.let { detail ->
                                if (detail.taskId == task.id) detail.copy(task = task)
                                else detail
                            },
                            mutatingIds = state.mutatingIds - task.id,
                            failed = true,
                        )
                    }
                },
            )
        }
    }

    fun openTask(task: TaskItem) {
        _ui.value.detail?.let(detailBackStack::add)
        loadTask(task)
    }

    fun retryTask() {
        _ui.value.detail?.task?.let(::loadTask)
    }

    private fun loadTask(task: TaskItem) {
        detailLoadJob?.cancel()
        detailActionJob?.cancel()
        _ui.update {
            it.copy(
                detail = TaskDetailItem(taskId = task.id, task = task, loading = true),
                detailFailed = false,
                detailActionRunning = false,
                detailActionFailure = null,
                deletingAttachmentIds = emptySet(),
            )
        }
        detailLoadJob = viewModelScope.launch {
            repository.loadDetail(task.id, sharedVia = conversationId).fold(
                onSuccess = { detail ->
                    val mapped = detail.toItem(task.id)
                    _ui.update { state ->
                        if (state.detail?.taskId != task.id) return@update state
                        state.copy(
                            tasks = state.tasks.map { item ->
                                if (item.id == task.id) requireNotNull(mapped.task) else item
                            },
                            detail = mapped,
                            detailFailed = false,
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update { state ->
                        if (state.detail?.taskId != task.id) return@update state
                        state.copy(
                            detail = state.detail?.copy(loading = false),
                            detailFailed = true,
                        )
                    }
                },
            )
        }
    }

    fun closeTask() {
        detailLoadJob?.cancel()
        detailActionJob?.cancel()
        val previous = detailBackStack.removeLastOrNull()
        _ui.update {
            it.copy(
                detail = previous,
                detailFailed = false,
                detailActionRunning = false,
                detailActionFailure = null,
                deletingAttachmentIds = emptySet(),
            )
        }
    }

    fun toggleFollowing(task: TaskItem) {
        if (_ui.value.detailActionRunning) return
        val following = !task.followed
        updateDetailTask(task.copy(followed = following), actionRunning = true)
        detailActionJob = viewModelScope.launch {
            repository.setFollowing(task.id, following, sharedVia = conversationId).fold(
                onSuccess = { updated ->
                    val mapped = updated.toItem().copy(commentCount = task.commentCount)
                    updateDetailTask(mapped, actionRunning = false)
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    updateDetailTask(
                        task,
                        actionRunning = false,
                        failure = TaskFailure.Save,
                    )
                },
            )
        }
    }

    fun sendComment(task: TaskItem, content: String, onSent: () -> Unit) {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || !task.canComment || _ui.value.detailActionRunning) return
        _ui.update {
            it.copy(detailActionRunning = true, detailActionFailure = null)
        }
        detailActionJob = viewModelScope.launch {
            repository.createComment(task.id, trimmed, sharedVia = conversationId).fold(
                onSuccess = { comment ->
                    val mapped = TaskCommentItem(
                        id = comment.id,
                        author = comment.author?.displayName.orEmpty(),
                        authorId = comment.author?.id.orEmpty(),
                        authorAvatarUrl = comment.author?.avatarUrl.orEmpty(),
                        content = comment.content,
                        createdAt = comment.createdAt,
                    )
                    _ui.update { state ->
                        val current = state.detail
                        if (current?.taskId != task.id) {
                            state.copy(detailActionRunning = false)
                        } else {
                            val updatedTask = current.task?.copy(
                                commentCount = current.comments.size + 1,
                            )
                            state.copy(
                                tasks = state.tasks.map { item ->
                                    if (item.id == task.id) {
                                        item.copy(commentCount = current.comments.size + 1)
                                    } else {
                                        item
                                    }
                                },
                                detail = current.copy(
                                    task = updatedTask,
                                    comments = current.comments + mapped,
                                ),
                                detailActionRunning = false,
                                detailActionFailure = null,
                            )
                        }
                    }
                    onSent()
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    _ui.update { state ->
                        state.copy(
                            detailActionRunning = false,
                            detailActionFailure = TaskFailure.Comment,
                        )
                    }
                },
            )
        }
    }

    fun downloadAttachment(
        taskId: String,
        attachment: TaskAttachmentItem,
        destination: Uri,
    ) {
        val current = _ui.value.detail
        if (current?.taskId != taskId) return
        if (attachment.id in current.downloadingAttachmentIds) return
        if (attachment.downloadUrl.isBlank()) {
            _ui.update { it.copy(detailActionFailure = TaskFailure.Attachment) }
            return
        }
        _ui.update { state ->
            state.copy(
                detail = state.detail?.takeIf { it.taskId == taskId }?.copy(
                    downloadingAttachmentIds =
                        state.detail.downloadingAttachmentIds + attachment.id,
                ) ?: state.detail,
                detailActionFailure = null,
            )
        }
        viewModelScope.launch {
            repository.downloadAttachment(attachment.downloadUrl, destination).fold(
                onSuccess = {
                    finishAttachmentDownload(taskId, attachment.id)
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    finishAttachmentDownload(
                        taskId,
                        attachment.id,
                        TaskFailure.Attachment,
                    )
                },
            )
        }
    }

    fun uploadAttachment(task: TaskItem, uri: Uri) {
        val current = _ui.value.detail
        if (!task.canManageAttachments || current?.taskId != task.id) return
        if (current.uploadingAttachment) return
        _ui.update { state ->
            state.copy(
                detail = state.detail?.copy(uploadingAttachment = true),
                detailActionFailure = null,
            )
        }
        viewModelScope.launch {
            repository.uploadAttachment(task.id, uri, sharedVia = conversationId).fold(
                onSuccess = { attachment ->
                    _ui.update { state ->
                        val detail = state.detail
                        if (detail?.taskId != task.id) return@update state
                        state.copy(
                            detail = detail.copy(
                                uploadingAttachment = false,
                                attachments = detail.attachments + TaskAttachmentItem(
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
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    finishAttachmentMutation(task.id, TaskFailure.Attachment)
                },
            )
        }
    }

    fun deleteAttachment(task: TaskItem, attachment: TaskAttachmentItem) {
        val current = _ui.value.detail
        if (!task.canManageAttachments || current?.taskId != task.id) return
        if (attachment.id in current.downloadingAttachmentIds) return
        if (attachment.id in _ui.value.deletingAttachmentIds) return
        _ui.update {
            it.copy(
                deletingAttachmentIds = it.deletingAttachmentIds + attachment.id,
                detailActionFailure = null,
            )
        }
        viewModelScope.launch {
            repository.deleteAttachment(
                task.id,
                attachment.id,
                sharedVia = conversationId,
            ).fold(
                onSuccess = {
                    _ui.update { state ->
                        val detail = state.detail
                        if (detail?.taskId != task.id) return@update state
                        state.copy(
                            detail = detail.copy(
                                attachments = detail.attachments.filterNot {
                                    it.id == attachment.id
                                },
                            ),
                            deletingAttachmentIds = state.deletingAttachmentIds - attachment.id,
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) return@launch
                    finishAttachmentMutation(
                        task.id,
                        TaskFailure.Attachment,
                        attachment.id,
                    )
                },
            )
        }
    }

    private fun finishAttachmentMutation(
        taskId: String,
        failure: TaskFailure?,
        attachmentId: String? = null,
    ) {
        _ui.update { state ->
            val detail = state.detail
            if (detail?.taskId != taskId) return@update state
            state.copy(
                detail = detail.copy(uploadingAttachment = false),
                detailActionFailure = failure,
                deletingAttachmentIds = attachmentId?.let {
                    state.deletingAttachmentIds - it
                } ?: state.deletingAttachmentIds,
            )
        }
    }

    private fun finishAttachmentDownload(
        taskId: String,
        attachmentId: String,
        failure: TaskFailure? = null,
    ) {
        _ui.update { state ->
            val detail = state.detail
            if (detail?.taskId != taskId) return@update state
            state.copy(
                detail = detail.copy(
                    downloadingAttachmentIds =
                        detail.downloadingAttachmentIds - attachmentId,
                ),
                detailActionFailure = failure,
            )
        }
    }

    private fun updateDetailTask(
        task: TaskItem,
        actionRunning: Boolean,
        failure: TaskFailure? = null,
    ) {
        _ui.update { state ->
            state.copy(
                tasks = state.tasks.map { if (it.id == task.id) task else it },
                detail = state.detail?.let { detail ->
                    if (detail.taskId == task.id) detail.copy(task = task) else detail
                },
                detailActionRunning = actionRunning,
                detailActionFailure = failure,
            )
        }
    }

    class Factory(
        private val app: WeMeetApp,
        private val conversationId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationTasksViewModel(app.taskRepository, conversationId) as T
    }
}
