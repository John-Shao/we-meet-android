package com.we.meet.ui.contacts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactsUiState(
    /** Full flat department list, loaded once; children derived via `parent`. */
    val departments: List<DepartmentDto> = emptyList(),
    /** Drill-down path; empty = organization root. */
    val deptStack: List<DepartmentDto> = emptyList(),
    /** Members of the current node (subtree included). */
    val members: List<MemberDto> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false,
) {
    val currentDept: DepartmentDto? get() = deptStack.lastOrNull()
    val childDepartments: List<DepartmentDto>
        get() {
            val parentId = currentDept?.id
            return departments.filter { it.parent == parentId }
        }
}

/**
 * 通讯录 tab VM — scoped to the HOME back-stack entry so drill-down state survives
 * tab switches.
 *
 * **只管浏览**:部门下钻 + 当前节点的成员分页。
 *
 * 页内搜索已经拆掉了。原先这里有一个 `query` + 300ms 防抖的服务端搜索,但它和
 * 「去全局搜索页里限定同一个部门搜」是同一件事的两个壳 —— 同一个
 * `directory/members/?department=` 接口、同样的整份替换式结果,却各自维护一套
 * 状态、空态、分页;更糟的是两个入口的范围语义还可能不一致(浏览走
 * `include_subtree=true`,搜索走的是「仅直属」)。现在搜索统一走
 * [com.we.meet.ui.nav.Routes.imSearch],范围由 `dept` 参数带过去,这一页只剩
 * 「我现在在哪个部门、这个部门有谁」。
 */
class ContactsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as WeMeetApp).directoryRepository

    private val _ui = MutableStateFlow(ContactsUiState(loading = true))
    val ui: StateFlow<ContactsUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var nextPage: Int = 1

    init {
        loadDepartments()
        loadMembersForCurrentNode()
    }

    fun openDepartment(dept: DepartmentDto) {
        _ui.update { it.copy(deptStack = it.deptStack + dept) }
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
        val dept = currentDept
        if (dept != null) {
            repository.departmentMembers(dept.id, page)
        } else {
            repository.allMembers(page)
        }
    }

    private companion object {
        const val TAG = "ContactsVM"
    }
}
