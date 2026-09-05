package com.we.meet.ui.contacts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactsUiState(
    /** Full flat department list, loaded once; children derived via `parent`. */
    val departments: List<DepartmentDto> = emptyList(),
    /** Drill-down path; empty = organization root. */
    val deptStack: List<DepartmentDto> = emptyList(),
    /** Members of the current node (subtree) or search hits. */
    val members: List<MemberDto> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    val hasMore: Boolean = false,
    val query: String = "",
    val error: Boolean = false,
) {
    val currentDept: DepartmentDto? get() = deptStack.lastOrNull()
    val childDepartments: List<DepartmentDto>
        get() {
            val parentId = currentDept?.id
            return departments.filter { it.parent == parentId }
        }
    val searching: Boolean get() = query.isNotBlank()
}

/**
 * 通讯录 tab VM — scoped to the HOME back-stack entry so drill-down state
 * survives tab switches. Search replaces the browse body while non-blank.
 */
@OptIn(FlowPreview::class)
class ContactsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as WeMeetApp).directoryRepository

    private val _ui = MutableStateFlow(ContactsUiState(loading = true))
    val ui: StateFlow<ContactsUiState> = _ui.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var loadJob: Job? = null
    private var nextPage: Int = 1

    init {
        loadDepartments()
        loadMembersForCurrentNode()
        viewModelScope.launch {
            // drop(1): the initial empty query is served by init's load.
            queryFlow.drop(1).debounce(300).distinctUntilChanged().collect {
                loadMembersForCurrentNode()
            }
        }
    }

    fun onQueryChange(q: String) {
        _ui.update { it.copy(query = q) }
        queryFlow.value = q
    }

    fun openDepartment(dept: DepartmentDto) {
        _ui.update { it.copy(deptStack = it.deptStack + dept, query = "") }
        queryFlow.value = ""
        loadMembersForCurrentNode()
    }

    /** Pop to a specific breadcrumb level; index -1 = organization root. */
    fun popTo(index: Int) {
        _ui.update { it.copy(deptStack = it.deptStack.take(index + 1)) }
        loadMembersForCurrentNode()
    }

    fun popOne() {
        _ui.update { it.copy(deptStack = it.deptStack.dropLast(1)) }
        loadMembersForCurrentNode()
    }

    fun retry() {
        if (_ui.value.departments.isEmpty()) loadDepartments()
        loadMembersForCurrentNode()
    }

    fun loadMore() {
        val state = _ui.value
        if (state.loadingMore || !state.hasMore) return
        _ui.update { it.copy(loadingMore = true, loadMoreError = false) }
        viewModelScope.launch {
            fetchPage(nextPage)
                .onSuccess { page ->
                    nextPage = page.nextPage
                    _ui.update {
                        it.copy(
                            members = (it.members + page.members).distinctBy { m -> m.id },
                            hasMore = page.hasMore,
                            loadingMore = false,
                            loadMoreError = false,
                        )
                    }
                }
                .onFailure {
                    _ui.update { it.copy(loadingMore = false, loadMoreError = true) }
                }
        }
    }

    private fun loadDepartments() {
        viewModelScope.launch {
            repository.listAllDepartments()
                .onSuccess { depts -> _ui.update { it.copy(departments = depts) } }
                .onFailure { e ->
                    Log.w(TAG, "departments load failed", e)
                    _ui.update { it.copy(error = true) }
                }
        }
    }

    private fun loadMembersForCurrentNode() {
        loadJob?.cancel()
        _ui.update { it.copy(loading = true, error = false, loadMoreError = false) }
        loadJob = viewModelScope.launch {
            fetchPage(1)
                .onSuccess { page ->
                    nextPage = page.nextPage
                    _ui.update {
                        it.copy(members = page.members, hasMore = page.hasMore, loading = false)
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "members load failed", e)
                    _ui.update { it.copy(loading = false, error = true) }
                }
        }
    }

    private suspend fun fetchPage(page: Int) = with(_ui.value) {
        when {
            searching -> repository.searchMembers(query.trim(), page)
            currentDept != null -> repository.departmentMembers(currentDept!!.id, page)
            else -> repository.allMembers(page)
        }
    }

    private companion object {
        const val TAG = "ContactsVM"
    }
}
