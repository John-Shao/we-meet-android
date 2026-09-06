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
 * Document detail skeleton (设计文档 M1): metadata + row actions; the rich
 * read mode (BlockNote renderer) lands in M2 — until then the body area
 * points at the docs WebView fallback.
 */
class DocDetailViewModel(
    private val repo: DocsRepository,
    private val docId: String,
) : ViewModel() {

    data class UiState(
        val doc: DocumentDto? = null,
        val loading: Boolean = false,
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val toasts: SharedFlow<Int> = _toasts.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            runCatching { repo.document(docId) }
                .onSuccess { doc -> _state.update { it.copy(doc = doc, loading = false) } }
                .onFailure { _state.update { it.copy(loading = false, error = true) } }
        }
    }

    fun toggleFavorite() {
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            runCatching { repo.favorite(doc.id, add = !doc.isFavorite) }
                .onSuccess { _state.update { it.copy(doc = it.doc?.copy(isFavorite = !doc.isFavorite)) } }
                .onFailure { _toasts.tryEmit(R.string.docs_load_error) }
        }
    }

    fun rename(newTitle: String) {
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            runCatching { repo.rename(doc.id, newTitle) }
                .onSuccess { updated -> _state.update { it.copy(doc = updated) } }
                .onFailure { _toasts.tryEmit(R.string.docs_load_error) }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            runCatching { repo.delete(doc.id) }
                .onSuccess { onDeleted() }
                .onFailure { _toasts.tryEmit(R.string.docs_load_error) }
        }
    }

    fun move(targetId: String, position: String, onMoved: () -> Unit) {
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            runCatching { repo.move(doc.id, targetId, position) }
                .onSuccess { onMoved() }
                .onFailure { _toasts.tryEmit(R.string.docs_load_error) }
        }
    }
}
