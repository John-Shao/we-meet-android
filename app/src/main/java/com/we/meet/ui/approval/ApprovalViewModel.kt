package com.we.meet.ui.approval

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.ApprovalActRequest
import com.we.meet.data.api.dto.ApprovalInstanceDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ApprovalTab(val role: String) { Pending("pending"), Mine("mine") }

/** One tab's paged list state. */
data class ApprovalListState(
    val items: List<ApprovalInstanceDto> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false,
    val loaded: Boolean = false,
    val nextPage: Int = 1,
)

data class ApprovalUiState(
    val tab: ApprovalTab = ApprovalTab.Pending,
    val pending: ApprovalListState = ApprovalListState(),
    val mine: ApprovalListState = ApprovalListState(),
    /** Server count of the pending list's first page — the tab badge. */
    val pendingCount: Int = 0,
    val actionError: Boolean = false,
    /** Instance id with an act/cancel/urge request in flight — disables that
     *  card's buttons so a slow network can't produce a double submit. */
    val actingId: String? = null,
    /** One-shot flag: a 催办 just succeeded (drives a transient confirmation). */
    val urged: Boolean = false,
) {
    val current: ApprovalListState get() = if (tab == ApprovalTab.Pending) pending else mine
}

/**
 * 审批 list VM: two tabs (待我审批 / 我发起的), each independently paged with
 * load-more. Acting/cancelling refresh the affected tab. Scoped to the approval
 * route back-stack entry.
 */
class ApprovalViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as WeMeetApp).apiClient.approvalApi

    private val _ui = MutableStateFlow(ApprovalUiState())
    val ui: StateFlow<ApprovalUiState> = _ui.asStateFlow()

    init {
        loadFirst(ApprovalTab.Pending)
    }

    fun selectTab(tab: ApprovalTab) {
        _ui.update { it.copy(tab = tab) }
        val state = if (tab == ApprovalTab.Pending) _ui.value.pending else _ui.value.mine
        if (!state.loaded && !state.loading) loadFirst(tab)
    }

    fun refresh() = loadFirst(_ui.value.tab)

    private fun loadFirst(tab: ApprovalTab) {
        updateTab(tab) { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            runCatching { api.listApprovals(role = tab.role, page = 1) }
                .onSuccess { page ->
                    updateTab(tab) {
                        it.copy(
                            items = page.results,
                            loading = false,
                            loaded = true,
                            hasMore = page.next != null,
                            nextPage = 2,
                        )
                    }
                    if (tab == ApprovalTab.Pending) {
                        _ui.update { it.copy(pendingCount = page.count) }
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "approval list failed", e)
                    updateTab(tab) { it.copy(loading = false, error = true) }
                }
        }
    }

    fun loadMore() {
        val tab = _ui.value.tab
        val state = _ui.value.current
        if (state.loadingMore || !state.hasMore) return
        updateTab(tab) { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { api.listApprovals(role = tab.role, page = state.nextPage) }
                .onSuccess { page ->
                    updateTab(tab) {
                        it.copy(
                            items = (it.items + page.results).distinctBy { d -> d.id },
                            loadingMore = false,
                            hasMore = page.next != null,
                            nextPage = it.nextPage + 1,
                        )
                    }
                }
                .onFailure { updateTab(tab) { it.copy(loadingMore = false) } }
        }
    }

    fun act(id: String, action: String, comment: String) {
        if (_ui.value.actingId != null) return
        _ui.update { it.copy(actingId = id) }
        viewModelScope.launch {
            runCatching { api.act(id, ApprovalActRequest(action = action, comment = comment.trim())) }
                .onSuccess { _ui.update { s -> s.copy(actingId = null) }; loadFirst(ApprovalTab.Pending) }
                .onFailure {
                    Log.w(TAG, "approval act failed", it)
                    _ui.update { s -> s.copy(actingId = null, actionError = true) }
                }
        }
    }

    fun cancel(id: String) {
        if (_ui.value.actingId != null) return
        _ui.update { it.copy(actingId = id) }
        viewModelScope.launch {
            runCatching { api.cancel(id) }
                .onSuccess { _ui.update { s -> s.copy(actingId = null) }; loadFirst(ApprovalTab.Mine) }
                .onFailure {
                    Log.w(TAG, "approval cancel failed", it)
                    _ui.update { s -> s.copy(actingId = null, actionError = true) }
                }
        }
    }

    /** 催办:re-ping the current approver. No list change; flags a confirmation. */
    fun urge(id: String) {
        if (_ui.value.actingId != null) return
        _ui.update { it.copy(actingId = id) }
        viewModelScope.launch {
            runCatching { api.urge(id) }
                .onSuccess { _ui.update { s -> s.copy(actingId = null, urged = true) } }
                .onFailure {
                    Log.w(TAG, "approval urge failed", it)
                    _ui.update { s -> s.copy(actingId = null, actionError = true) }
                }
        }
    }

    fun dismissActionError() = _ui.update { it.copy(actionError = false) }

    fun dismissUrged() = _ui.update { it.copy(urged = false) }

    private inline fun updateTab(tab: ApprovalTab, block: (ApprovalListState) -> ApprovalListState) {
        _ui.update {
            if (tab == ApprovalTab.Pending) it.copy(pending = block(it.pending))
            else it.copy(mine = block(it.mine))
        }
    }

    private companion object {
        const val TAG = "ApprovalVM"
    }
}
