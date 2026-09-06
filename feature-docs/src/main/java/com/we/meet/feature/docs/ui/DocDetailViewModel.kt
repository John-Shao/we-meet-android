package com.we.meet.feature.docs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.renderer.JsonBlockDto
import com.we.meet.feature.docs.renderer.parseBlockNoteContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 文档详情(M2):元数据 + 阅读态正文(formatted-content → BlockNote JSON)。
 *
 * 新鲜度策略(设计文档 §4.7.4):进入/前台/下拉刷新全量重拉;停留期间 30s
 * 轻轮询,内容变了才重渲染并提示。
 */
class DocDetailViewModel(
    private val repo: DocsRepository,
    private val docId: String,
) : ViewModel() {

    data class UiState(
        val doc: DocumentDto? = null,
        val loading: Boolean = false,
        val error: Boolean = false,
        val blocks: List<JsonBlockDto> = emptyList(),
        val contentLoading: Boolean = false,
        val contentError: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val toasts: SharedFlow<Int> = _toasts.asSharedFlow()

    private var lastContentRaw: String? = null

    init {
        load()
        loadContent()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            runCatching { repo.document(docId) }
                .onSuccess { doc -> _state.update { it.copy(doc = doc, loading = false) } }
                .onFailure { _state.update { it.copy(loading = false, error = true) } }
        }
    }

    fun loadContent() {
        viewModelScope.launch {
            _state.update { it.copy(contentLoading = true, contentError = false) }
            runCatching { repo.formattedContent(docId, format = "json") }
                .onSuccess { dto ->
                    val raw = dto.content
                    lastContentRaw = raw?.toString()
                    _state.update {
                        it.copy(
                            blocks = parseBlockNoteContent(raw),
                            contentLoading = false,
                            contentError = false,
                        )
                    }
                }
                .onFailure {
                    _state.update { it.copy(contentLoading = false, contentError = true) }
                }
        }
    }

    /** 前台停留期间的轻轮询:内容没变就不动,变了重渲染 + 提示。 */
    fun pollContent() {
        viewModelScope.launch {
            runCatching { repo.formattedContent(docId, format = "json") }
                .onSuccess { dto ->
                    val raw = dto.content
                    if (raw?.toString() != lastContentRaw) {
                        lastContentRaw = raw?.toString()
                        _state.update {
                            it.copy(blocks = parseBlockNoteContent(raw), contentError = false)
                        }
                        _toasts.tryEmit(R.string.docs_content_updated)
                    }
                }
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

    fun restoreVersion(base64Content: String, onRestored: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.restoreContent(docId, base64Content) }
                .onSuccess {
                    _toasts.tryEmit(R.string.docs_version_restored)
                    loadContent()
                    onRestored()
                }
                .onFailure { _toasts.tryEmit(R.string.docs_version_restore_failed) }
        }
    }
}
