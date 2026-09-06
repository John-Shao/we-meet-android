package com.we.meet.feature.docs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocumentDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Document list screen state machine (设计文档 §4.4 文档主页).
 *
 * Two modes share the same machinery:
 *  - [Mode.HOME]: filter (全部/我的/收藏) + ordering + full row actions;
 *  - [Mode.TRASH]: trashbin endpoint, rows only offer restore.
 */
class DocsHomeViewModel(
    private val repo: DocsRepository,
    private val mode: Mode,
) : ViewModel() {

    enum class Mode { HOME, TRASH }
    enum class Filter { ALL, MINE, SHARED }

    data class UiState(
        val filter: Filter = Filter.ALL,
        val ordering: String = "-updated_at",
        val items: List<DocumentDto> = emptyList(),
        val loading: Boolean = false,
        val error: Boolean = false,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val allCount: Int? = null,
        val mineCount: Int? = null,
        val sharedCount: Int? = null,
        val trashCount: Int? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot toasts (resId) — refresh failures with content, mutation results. */
    private val _toasts = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val toasts: SharedFlow<Int> = _toasts.asSharedFlow()

    private var page = 1

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (_state.value.items.isEmpty()) {
                _state.update { it.copy(loading = true, error = false) }
            } else {
                _state.update { it.copy(refreshing = true) }
            }
            // 取消进行中的加载更多(可能用旧 page 拼接造成重复),随刷新整体重置分页。
            _state.update { it.copy(loadingMore = false) }
            page = 1
            val result = runCatching { fetchPage(1) }
            result.onSuccess { pageDto ->
                _state.update {
                    it.copy(
                        items = pageDto.results,
                        loading = false,
                        error = false,
                        refreshing = false,
                        hasMore = pageDto.next != null,
                    )
                }
            }.onFailure {
                _state.update { state ->
                    state.copy(
                        loading = false,
                        refreshing = false,
                        // 已有内容时保留列表,失败走 Snackbar 而不是整屏错误图。
                        error = state.items.isEmpty(),
                    )
                }
                if (_state.value.items.isNotEmpty()) emitToast(R.string.docs_load_error)
            }
        }
    }

    fun setFilter(filter: Filter) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    /** 抽屉计数(各筛选一次轻查询,page_size=1 只取 count)。 */
    fun refreshCounts() {
        val current = _state.value
        viewModelScope.launch {
            val all = runCatching { repo.list(page = 1, pageSize = 1) }.getOrNull()?.count
            val mine = runCatching { repo.list(page = 1, pageSize = 1, isCreatorMe = true) }.getOrNull()?.count
            val shared = runCatching { repo.list(page = 1, pageSize = 1, isCreatorMe = false) }.getOrNull()?.count
            val trash = runCatching { repo.trashbin(page = 1, pageSize = 1) }.getOrNull()?.count
            _state.update {
                it.copy(
                    allCount = all,
                    mineCount = mine,
                    sharedCount = shared,
                    trashCount = trash,
                )
            }
        }
    }

    fun setOrdering(ordering: String) {
        if (_state.value.ordering == ordering) return
        _state.update { it.copy(ordering = ordering) }
        refresh()
    }

    fun loadMore() {
        val state = _state.value
        if (state.loading || state.refreshing || state.loadingMore || !state.hasMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val result = runCatching { fetchPage(page + 1) }
            result.onSuccess { pageDto ->
                page += 1
                _state.update {
                    it.copy(
                        items = it.items + pageDto.results,
                        loadingMore = false,
                        hasMore = pageDto.next != null,
                    )
                }
            }.onFailure {
                _state.update { it.copy(loadingMore = false) }
                emitToast(R.string.docs_load_error)
            }
        }
    }

    fun toggleFavorite(doc: DocumentDto) {
        if (doc.id.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.favorite(doc.id, add = !doc.isFavorite) }
                .onSuccess {
                    _state.update { state ->
                        state.copy(items = state.items.map { if (it.id == doc.id) it.copy(isFavorite = !doc.isFavorite) else it })
                    }
                }
                .onFailure { emitToast(R.string.docs_load_error) }
        }
    }

    fun rename(doc: DocumentDto, newTitle: String) {
        if (doc.id.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.rename(doc.id, newTitle) }
                .onSuccess { updated ->
                    _state.update { state ->
                        state.copy(items = state.items.map { if (it.id == doc.id) updated else it })
                    }
                    emitToast(R.string.docs_renamed_toast)
                }
                .onFailure { emitToast(R.string.docs_load_error) }
        }
    }

    fun delete(doc: DocumentDto) {
        if (doc.id.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.delete(doc.id) }
                .onSuccess {
                    _state.update { state -> state.copy(items = state.items.filterNot { it.id == doc.id }) }
                    emitToast(R.string.docs_deleted_toast)
                }
                .onFailure { emitToast(R.string.docs_load_error) }
        }
    }

    fun restore(doc: DocumentDto) {
        if (doc.id.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.restore(doc.id) }
                .onSuccess {
                    _state.update { state -> state.copy(items = state.items.filterNot { it.id == doc.id }) }
                    emitToast(R.string.docs_restored_toast)
                }
                .onFailure { emitToast(R.string.docs_load_error) }
        }
    }

    fun move(doc: DocumentDto, targetId: String, position: String) {
        if (doc.id.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.move(doc.id, targetId, position) }
                .onSuccess {
                    _state.update { state -> state.copy(items = state.items.filterNot { it.id == doc.id }) }
                    emitToast(R.string.docs_moved_toast)
                }
                .onFailure { emitToast(R.string.docs_load_error) }
        }
    }

    /** Creates a document; returns its id for navigation, or null on failure. */
    suspend fun create(title: String): String? =
        runCatching { repo.create(title) }
            .onSuccess { emitToast(R.string.docs_created_toast) }
            .onFailure { emitToast(R.string.docs_load_error) }
            .getOrNull()
            ?.id

    private suspend fun fetchPage(pageNumber: Int) = when (mode) {
        Mode.HOME -> repo.list(
            page = pageNumber,
            isCreatorMe = when (_state.value.filter) {
                Filter.MINE -> true
                Filter.SHARED -> false
                Filter.ALL -> null
            },
            ordering = _state.value.ordering,
        )
        Mode.TRASH -> repo.trashbin(pageNumber)
    }

    private fun emitToast(resId: Int) {
        _toasts.tryEmit(resId)
    }
}
