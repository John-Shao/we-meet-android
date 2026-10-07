package com.we.meet.ui.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.data.api.*
import com.we.meet.data.repository.WorkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class WorkUi(
    val loading: Boolean = false, val acting: Boolean = false, val enabled: Boolean = false,
    val workspaces: List<DesktopWorkspace> = emptyList(), val tasks: List<WorkTaskDto> = emptyList(),
    val page: Int = 1, val hasMore: Boolean = false, val selected: WorkTaskDto? = null,
    val files: List<WorkFileDto> = emptyList(), val previewName: String = "", val preview: String = "",
    val error: String = "", val uncertain: Boolean = false,
)

/** A lost response retains the exact request in SavedStateHandle for reconciliation. */
class WorkViewModel(private val repo: WorkRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val _ui = MutableStateFlow(WorkUi(uncertain = saved.get<String>("requestId") != null))
    val ui = _ui.asStateFlow()
    init { refresh() }
    val pendingGoal: String get() = saved["requestGoal"] ?: ""
    val pendingWorkspace: String get() = saved["requestWorkspace"] ?: ""

    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        _ui.update { it.copy(error = "work_request_failed") }
    }
    fun refresh() {
        if (_ui.value.loading || _ui.value.acting) return
        _ui.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val cap = repo.capabilities()
                val folders = if (cap.remoteEnabled) repo.workspaces() else emptyList()
                val tasks = repo.tasks(1)
                _ui.update { it.copy(enabled = cap.remoteEnabled, workspaces = folders, tasks = tasks.results, page = 1, hasMore = tasks.next != null) }
                saved.get<String>("requestId")?.let { id ->
                    if (tasks.results.any { task -> task.runs.any { it.id == id } }) clearIntent()
                }
                _ui.value.selected?.let { selectInternal(it.id) }
            } catch (e: Exception) { fail(e) }
            finally { _ui.update { it.copy(loading = false) } }
        }
    }
    fun more() = act {
        val next = repo.tasks(_ui.value.page + 1)
        _ui.update { it.copy(tasks = (it.tasks + next.results).distinctBy { t -> t.id }, page = it.page + 1, hasMore = next.next != null) }
    }
    private fun clearIntent() {
        saved.remove<String>("requestId"); saved.remove<String>("requestGoal"); saved.remove<String>("requestWorkspace")
        _ui.update { it.copy(uncertain = false) }
    }
    fun dispatch(workspaceId: String, goal: String) = act {
        if (!_ui.value.enabled) return@act
        val previous = saved.get<String>("requestId")
        if (previous == null) {
            require(goal.isNotBlank() && goal.length <= 2000 && _ui.value.workspaces.any { it.id == workspaceId && it.enabled })
            saved["requestId"] = UUID.randomUUID().toString()
            saved["requestGoal"] = goal
            saved["requestWorkspace"] = workspaceId
        }
        _ui.update { it.copy(uncertain = true) }
        val response = repo.dispatch(RemoteWorkRequest(saved["requestId"]!!, pendingWorkspace, pendingGoal))
        clearIntent()
        _ui.update { it.copy(selected = response.task, tasks = (listOf(response.task) + it.tasks).distinctBy { t -> t.id }, files = emptyList(), preview = "", previewName = "") }
    }
    fun select(id: String) = act { selectInternal(id) }
    private suspend fun selectInternal(id: String) {
        val task = repo.task(id)
        val run = task.runs.lastOrNull()
        val files = if (run?.status == "succeeded") repo.files(run.id) else emptyList()
        _ui.update {
            val keep = it.selected?.id == task.id && files.any { f -> f.name == it.previewName && it.files.any { old -> old.name == f.name && old.sha256 == f.sha256 } }
            it.copy(selected = task, files = files, previewName = if (keep) it.previewName else "", preview = if (keep) it.preview else "")
        }
    }
    fun closeDetail() { _ui.update { it.copy(selected = null, files = emptyList(), preview = "", previewName = "") } }
    fun cancel() = act {
        val task = _ui.value.selected ?: return@act
        task.runs.lastOrNull()?.let { repo.cancel(it.id) }
        selectInternal(task.id)
    }
    fun preview(file: WorkFileDto) = act {
        val run = _ui.value.selected?.runs?.lastOrNull() ?: return@act
        val text = repo.preview(run.id, file)
        _ui.update { it.copy(previewName = file.name, preview = text) }
    }
    private fun act(action: suspend () -> Unit) {
        if (_ui.value.acting || _ui.value.loading) return
        _ui.update { it.copy(acting = true, error = "") }
        viewModelScope.launch {
            try { action() } catch (e: Exception) { fail(e) }
            finally { _ui.update { it.copy(acting = false) } }
        }
    }
}
