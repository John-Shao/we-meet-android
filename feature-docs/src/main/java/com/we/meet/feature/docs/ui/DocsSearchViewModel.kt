package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.util.docsRunCatching as runCatching

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocumentDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Module search page (docs `documents/search/`), 300ms debounced. */
class DocsSearchViewModel(private val repo: DocsRepository) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<DocumentDto> = emptyList(),
        val loading: Boolean = false,
        val error: Boolean = false,
        val idle: Boolean = true,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var pageNumber = 1

    fun onQueryChange(query: String) {
        pageNumber = 1
        _state.update { it.copy(query = query, loading = query.isNotBlank(), error = false, idle = query.isBlank(), results = emptyList(), hasMore = false, loadingMore = false) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), loading = false, error = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(loading = true, error = false) }
            runCatching { repo.search(query) }
                .onSuccess { page ->
                    // 仅在当前 query 仍是本次请求时应用结果,避免旧响应覆盖新输入。
                    if (_state.value.query == query) {
                        _state.update { it.copy(results = page.results, loading = false, hasMore = page.next != null) }
                    }
                }
                .onFailure {
                    if (_state.value.query == query) {
                        _state.update { it.copy(loading = false, error = true) }
                    }
                }
        }
    }

    fun retry() {
        val q = _state.value.query
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            runCatching { repo.search(q) }
                .onSuccess { page ->
                    if (_state.value.query == q) {
                        _state.update { it.copy(results = page.results, loading = false, hasMore = page.next != null) }
                    }
                }
                .onFailure {
                    if (_state.value.query == q) {
                        _state.update { it.copy(loading = false, error = true) }
                    }
                }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.loadingMore || !snapshot.hasMore) return
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val next = repo.search(snapshot.query, pageNumber + 1)
                if (_state.value.query != snapshot.query) return@launch
                pageNumber += 1
                _state.update { it.copy(results = (it.results + next.results).distinctBy { doc -> doc.id }, loadingMore = false, hasMore = next.next != null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }
}
