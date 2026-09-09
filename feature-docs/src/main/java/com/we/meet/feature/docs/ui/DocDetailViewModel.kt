package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.util.docsRunCatching as runCatching

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
        val refreshing: Boolean = false,
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

    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    private var refreshPending = false
    private var manualPending = false
    var pollDelayMillis: Long = 30_000
        private set

    fun load() { viewModelScope.launch { refresh(manual = true) } }
    fun loadContent() = load()
    suspend fun pollContent() = refresh()
    fun onOffline() { _state.update { if (it.doc == null) it.copy(error = true, loading = false) else it } }

    suspend fun refresh(manual: Boolean = false) {
        refreshPending = true
        manualPending = manualPending || manual
        if (!refreshMutex.tryLock()) return
        try {
            while (refreshPending) {
                val requestedManually = manualPending
                refreshPending = false
                manualPending = false
                refreshOnce(requestedManually)
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun backOff(error: Exception) {
        val retryAfter = (error as? HttpException)?.response()?.headers()?.get("Retry-After")?.toLongOrNull()
        pollDelayMillis = retryAfter?.coerceIn(30, 3600)?.times(1000)
            ?: (pollDelayMillis * 2).coerceAtMost(300_000)
    }

    private suspend fun refreshOnce(manual: Boolean) {
        try {
            _state.update { it.copy(loading = it.doc == null, refreshing = manual && it.doc != null, error = false) }
            val doc = repo.document(docId)
            _state.update { it.copy(doc = doc, loading = false, noAccess = false, contentLoading = it.blocks.isEmpty()) }
            try {
                val raw = repo.formattedContent(docId, format = "json").content
                val blocks = if (lastContentRaw == raw?.toString()) _state.value.blocks else
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parseBlockNoteContent(raw) }
                lastContentRaw = raw?.toString()
                pollDelayMillis = 30_000
                _state.update { it.copy(blocks = blocks, contentLoading = false, contentError = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNoAccess(e) || (e as? HttpException)?.code() == 404) {
                    throw e
                }
                backOff(e)
                _state.update { it.copy(contentLoading = false, contentError = true) }
                if (manual && _state.value.blocks.isNotEmpty()) _toasts.tryEmit(R.string.docs_load_error)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            backOff(e)
            if (isNoAccess(e) || (e as? HttpException)?.code() == 404) {
                lastContentRaw = null
                _state.update { UiState(noAccess = isNoAccess(e), error = !isNoAccess(e), requestSent = it.requestSent) }
            } else {
                _state.update { it.copy(loading = false, error = true) }
                if (manual && _state.value.doc != null) _toasts.tryEmit(R.string.docs_load_error)
            }
        } finally {
            _state.update { it.copy(loading = false, refreshing = false, contentLoading = false) }
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

    fun toggleFavorite() {
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            runCatching { repo.favorite(doc.id, add = !doc.isFavorite) }
                .onSuccess { _state.update { it.copy(doc = it.doc?.copy(isFavorite = !doc.isFavorite)) } }
                .onFailure { _toasts.tryEmit(R.string.docs_load_error) }
        }
    }

    fun rename(newTitle: String, onComplete: (Boolean) -> Unit) {
        val doc = _state.value.doc ?: return onComplete(false)
        viewModelScope.launch {
            val result = runCatching { repo.rename(doc.id, newTitle) }
                .onSuccess { updated -> _state.update { it.copy(doc = updated) } }
            onComplete(result.isSuccess)
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

    fun move(targetId: String, position: String, onComplete: (Boolean) -> Unit) {
        val doc = _state.value.doc ?: return onComplete(false)
        viewModelScope.launch {
            onComplete(runCatching { repo.move(doc.id, targetId, position) }.isSuccess)
        }
    }

}
