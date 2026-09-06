package com.we.meet.feature.docs.ui

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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, idle = query.isBlank()) }
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
                    _state.update { it.copy(results = page.results, loading = false) }
                }
                .onFailure {
                    _state.update { it.copy(loading = false, error = true) }
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
                    _state.update { it.copy(results = page.results, loading = false) }
                }
                .onFailure {
                    _state.update { it.copy(loading = false, error = true) }
                }
        }
    }
}
