package com.we.meet.feature.im.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jusi.lightim.AckPayload
import com.jusi.lightim.BackoffConfig
import com.jusi.lightim.Client
import com.jusi.lightim.ConnectionState
import com.jusi.lightim.ConversationSummary
import com.jusi.lightim.Message
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.data.ImApi
import com.we.meet.feature.im.data.ImTokenRepository
import com.we.meet.feature.im.net.ImNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Top-level Tab ViewModel for the IM feature.
 *
 * One per Tab mount — owns the jusi-light-im [Client] singleton (one per VM, one per
 * App process), drives connect on init, surfaces the connection state + conversation
 * list to the UI, and exposes `openConversation(cid)` / `sendText(...)` for the chat pane.
 */
class ImTabViewModel internal constructor(
    private val tokenRepo: ImTokenRepository,
    private val jusiImBaseUrl: String,
) : ViewModel() {

    // SDK 用自己的默认 OkHttp (无 Interceptor), 不能复用 we-meet 主 OkHttp:
    // 主 OkHttp 的 AuthInterceptor 会把 Keycloak Bearer 无差别覆盖到 jusi 请求,
    // 顶掉 SDK 内部塞的 IM JWT, 触发 jusi 401 -> Authenticator 死循环 refresh
    // (参见 [[reference-livekit-auth-chain]]).
    private val client: Client = Client(
        baseUrl = jusiImBaseUrl,
        tokenProvider = { tokenRepo.token().token },
        backoffConfig = BackoffConfig.default(),
    )

    private val _uiState = MutableStateFlow(ImTabUiState())
    val uiState: StateFlow<ImTabUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = client.state

    init {
        // Auto-connect on VM creation; the UI watches connectionState + uiState.error.
        viewModelScope.launch {
            try {
                // 同步把 self_uid 拉下来缓存到 uiState — "新建会话" UI 需要显示给用户.
                val tok = runCatching { tokenRepo.token() }.getOrNull()
                if (tok != null) {
                    _uiState.update { it.copy(selfUid = tok.uid) }
                }
                client.connect()
                refreshConversations()
            } catch (e: Throwable) {
                Log.w(TAG, "connect failed", e)
                _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
        // Fan-out: every inbound MSG appends to the active chat thread when relevant
        // and bumps unread counts on inactive ones.
        viewModelScope.launch {
            client.messages.collect { incoming ->
                _uiState.update { current ->
                    val updatedConvs = current.conversations.map { c ->
                        if (c.cid == incoming.cid) {
                            c.copy(
                                lastSeq = maxOf(c.lastSeq, incoming.seq),
                                unreadCount = if (current.activeCid == incoming.cid) c.unreadCount
                                              else c.unreadCount + 1,
                            )
                        } else c
                    }
                    val newMessages = if (current.activeCid == incoming.cid) {
                        val asMessage = Message(
                            mid = incoming.mid,
                            cid = incoming.cid,
                            senderUid = incoming.senderUid,
                            seq = incoming.seq,
                            contentType = incoming.contentType,
                            body = incoming.body,
                            ts = incoming.createdAt,
                        )
                        (current.activeMessages + asMessage)
                            .distinctBy { it.mid }
                            .sortedBy { it.seq }
                    } else current.activeMessages
                    current.copy(conversations = updatedConvs, activeMessages = newMessages)
                }
            }
        }
    }

    fun selectConversation(cid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeCid = cid, activeMessages = emptyList(), error = null) }
            try {
                val history = client.loadHistory(cid)
                _uiState.update {
                    val convs = it.conversations.map { c ->
                        if (c.cid == cid) c.copy(unreadCount = 0) else c
                    }
                    it.copy(
                        // Server returns newest-first; reverse for natural top-to-bottom rendering.
                        activeMessages = history.messages.asReversed(),
                        conversations = convs,
                    )
                }
                history.messages.firstOrNull()?.let { latest ->
                    runCatching { client.markRead(cid, latest.seq) }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "loadHistory failed", e)
                _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    fun sendText(cid: String, body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        viewModelScope.launch {
            try {
                val ack: AckPayload = client.sendText(cid, trimmed)
                Log.d(TAG, "sendText ack mid=${ack.mid} seq=${ack.seq}")
            } catch (e: Throwable) {
                Log.w(TAG, "sendText failed", e)
                _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
        return true
    }

    /**
     * MVP 联调入口: 通过对方的 jusi uid create-or-get 一个 direct conv.
     * 后端会算 deterministic cid, 双方都会拿到同一 cid (sorted pair).
     */
    fun createDirect(peerUid: String) {
        val trimmed = peerUid.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = tokenRepo.createDirect(trimmed)
                refreshConversations()
                selectConversation(result.cid)
            } catch (e: Throwable) {
                Log.w(TAG, "createDirect failed", e)
                _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    fun retry() {
        _uiState.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                client.connect()
                refreshConversations()
            } catch (e: Throwable) {
                _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    private suspend fun refreshConversations() {
        try {
            val list = client.listConversations()
            _uiState.update { it.copy(conversations = list) }
        } catch (e: Throwable) {
            Log.w(TAG, "listConversations failed", e)
            _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
        }
    }

    override fun onCleared() {
        client.close()
    }

    class Factory(
        private val appContext: Context,
        private val deps: ImDeps,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ImTabViewModel::class.java))
            val retrofit = ImNetwork.retrofit(deps)
            val api = retrofit.create(ImApi::class.java)
            return ImTabViewModel(
                tokenRepo = ImTokenRepository(api),
                jusiImBaseUrl = deps.jusiImBaseUrl,
            ) as T
        }

        // appContext kept for future use (e.g. SharedPreferences for last-active cid).
        @Suppress("unused")
        private val context = appContext
    }

    private companion object {
        const val TAG = "ImTabVM"
    }
}

/** Mutable UI snapshot for ImTabRoot. */
data class ImTabUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    val activeCid: String? = null,
    val activeMessages: List<Message> = emptyList(),
    val error: String? = null,
    /** Self uid (jusi-light-im id) — needed by the "New direct" dialog. */
    val selfUid: String? = null,
)
