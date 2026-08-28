package com.we.meet.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.repository.TaskRepository
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
)

class ConversationTasksViewModel(
    private val repository: TaskRepository,
    private val conversationId: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(ConversationTasksUiState())
    val ui: StateFlow<ConversationTasksUiState> = _ui.asStateFlow()

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
        _ui.update { state ->
            state.copy(
                tasks = state.tasks.map {
                    if (it.id == task.id) {
                        it.copy(status = if (completed) TaskStatus.Done else TaskStatus.Todo)
                    } else {
                        it
                    }
                },
                mutatingIds = state.mutatingIds + task.id,
                failed = false,
            )
        }
        viewModelScope.launch {
            repository.setCompleted(task.id, completed).fold(
                onSuccess = { updated ->
                    _ui.update { state ->
                        state.copy(
                            tasks = state.tasks.map {
                                if (it.id == task.id) updated.toItem() else it
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
                            mutatingIds = state.mutatingIds - task.id,
                            failed = true,
                        )
                    }
                },
            )
        }
    }

    fun openTask(task: TaskItem) {
        _ui.update {
            it.copy(
                detail = TaskDetailItem(taskId = task.id, task = task, loading = true),
                detailFailed = false,
            )
        }
        viewModelScope.launch {
            repository.loadDetail(task.id, sharedVia = conversationId).fold(
                onSuccess = { detail ->
                    val mapped = detail.toItem(task.id)
                    _ui.update { state ->
                        state.copy(
                            tasks = state.tasks.map { item ->
                                if (item.id == task.id) requireNotNull(mapped.task) else item
                            },
                            detail = mapped,
                            detailFailed = false,
                        )
                    }
                },
                onFailure = {
                    _ui.update { state ->
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
        _ui.update { it.copy(detail = null, detailFailed = false) }
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
