package com.we.meet.feature.docs.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.util.docsRunCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared by document routes so changing the selected page keeps the workspace tree open state. */
class DocTreeViewModel(private val repo: DocsRepository, private val savedState: SavedStateHandle) : ViewModel() {
    data class UiState(
        val currentId: String = "",
        val root: DocumentDto? = null,
        val expanded: Set<String> = emptySet(),
        val loaded: Set<String> = emptySet(),
        val loadingBranches: Set<String> = emptySet(),
        val failedBranches: Set<String> = emptySet(),
        val loading: Boolean = false,
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var generation = repo.accountGeneration
    private var request = 0
    private var treeJob: Job? = null
    private val branchJobs = mutableMapOf<String, Job>()

    fun load(docId: String, refresh: Boolean = false) {
        if (generation != repo.accountGeneration) {
            generation = repo.accountGeneration
            savedState.remove<ArrayList<String>>("expanded")
            savedState.remove<String>("root")
            _state.value = UiState()
        }
        val prior = _state.value
        treeJob?.cancel()
        branchJobs.values.forEach { it.cancel() }
        branchJobs.clear()
        val ticket = ++request
        val cached = prior.root?.takeIf { it.findTreeNode(docId) != null }
        _state.value = prior.copy(currentId = docId, root = cached, loading = true, error = false,
            loadingBranches = emptySet(), failedBranches = emptySet())
        treeJob = viewModelScope.launch {
            docsRunCatching { repo.tree(docId) }.onSuccess { fresh ->
                if (ticket != request) return@onSuccess
                val sameRoot = fresh.id == prior.root?.id
                val root = if (refresh) fresh else mergeDocumentTree(fresh, cached)
                val savedExpanded = if (savedState.get<String>("root") == root.id)
                    savedState.get<ArrayList<String>>("expanded").orEmpty().toSet() else emptySet()
                val expanded = (if (sameRoot) _state.value.expanded else savedExpanded) +
                    root.pathToNode(docId).dropLast(1) + root.id
                _state.value = UiState(docId, root, expanded, loadedTreeBranches(root))
                saveExpanded()
                expanded.forEach { id -> if (root.findTreeNode(id) != null && id !in _state.value.loaded) loadChildren(id) }
            }.onFailure {
                if (ticket == request) _state.update { it.copy(root = null, loading = false, error = true) }
            }
        }
    }

    private fun saveExpanded() {
        savedState["root"] = _state.value.root?.id
        savedState["expanded"] = ArrayList(_state.value.expanded)
    }

    fun toggle(id: String) {
        val state = _state.value
        _state.update { it.copy(expanded = if (id in state.expanded) it.expanded - id else it.expanded + id) }
        saveExpanded()
        if (id in _state.value.expanded && id !in state.loaded) loadChildren(id)
    }

    fun revealCurrent() {
        val state = _state.value
        val path = state.root?.pathToNode(state.currentId).orEmpty()
        _state.update { it.copy(expanded = it.expanded + path) }
        saveExpanded()
        path.forEach { if (it !in state.loaded) loadChildren(it) }
    }

    fun loadChildren(id: String) {
        val state = _state.value
        if (id in state.loadingBranches || state.root?.findTreeNode(id) == null) return
        val ticket = request
        _state.update { it.copy(loadingBranches = it.loadingBranches + id, failedBranches = it.failedBranches - id) }
        branchJobs[id] = viewModelScope.launch {
            docsRunCatching {
                loadAllTreeChildren { page -> repo.children(id, page, pageSize = 200) }
            }.onSuccess { children ->
                if (ticket == request) {
                    _state.update { it.copy(root = it.root?.let { root -> replaceTreeChildren(root, id, children) },
                        loaded = it.loaded + id, loadingBranches = it.loadingBranches - id) }
                    // Restore expanded descendants after refreshing an unloaded branch.
                    children.filter { it.id in _state.value.expanded && it.numchild > 0 }.forEach { loadChildren(it.id) }
                }
            }.onFailure {
                if (ticket == request) _state.update { it.copy(loadingBranches = it.loadingBranches - id,
                    failedBranches = it.failedBranches + id) }
            }
        }
    }
}
