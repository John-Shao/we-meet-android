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
import retrofit2.HttpException

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
        /** 该文档对当前用户不可访问(403 无权限)→ 展示「申请访问」流。 */
        val noAccess: Boolean = false,
        val requestingAccess: Boolean = false,
        /** 已发起过申请(防止重复提交,并提示等待)。 */
        val requestSent: Boolean = false,
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
            _state.update { it.copy(loading = true, error = false, noAccess = false) }
            runCatching { repo.document(docId) }
                .onSuccess { doc -> _state.update { it.copy(doc = doc, loading = false) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = !isNoAccess(e),
                            noAccess = isNoAccess(e),
                        )
                    }
                }
        }
    }

    /**
     * docs 侧对无权限文档返回 403(DocumentPermission 无 retrieve 能力 → PermissionDenied),
     * 据此与真实网络/服务错误区分,走「申请访问」而非「重试」。
     */
    private fun isNoAccess(e: Throwable): Boolean =
        (e as? HttpException)?.code() == 403

    fun requestAccess() {
        viewModelScope.launch {
            _state.update { it.copy(requestingAccess = true) }
            runCatching { repo.createAccessRequest(docId, role = "reader") }
                .onSuccess {
                    _state.update { it.copy(requestingAccess = false, requestSent = true) }
                    _toasts.tryEmit(R.string.docs_ask_access_sent)
                }
                .onFailure {
                    _state.update { it.copy(requestingAccess = false) }
                    _toasts.tryEmit(R.string.docs_ask_access_failed)
                }
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

    /** 复制文档(§4.7 对齐 Web 端 Duplicate) → 成功回调新文档 id。 */
    fun duplicate(onDuplicated: (String) -> Unit) {
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            runCatching { repo.duplicate(doc.id) }
                .onSuccess { newId ->
                    if (newId != null) {
                        _toasts.tryEmit(R.string.docs_duplicated)
                        onDuplicated(newId)
                    } else {
                        _toasts.tryEmit(R.string.docs_load_error)
                    }
                }
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
