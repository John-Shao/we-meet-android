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
import com.jusi.lightim.MsgOutPayload
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
        // Minting the IM token also tells us our own jusi uid; cache it here for the
        // "New conversation" dialog instead of doing a second standalone token call.
        tokenProvider = {
            val tok = tokenRepo.token()
            _uiState.update { it.copy(selfUid = tok.uid) }
            tok.token
        },
        backoffConfig = BackoffConfig.default(),
    )

    private val _uiState = MutableStateFlow(ImTabUiState())
    val uiState: StateFlow<ImTabUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = client.state

    init {
        // Auto-connect on VM creation; the UI watches connectionState + uiState.error.
        // connect() invokes tokenProvider, which also populates selfUid for the
        // "New conversation" dialog — no separate token round-trip needed here.
        viewModelScope.launch {
            try {
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
                val known = _uiState.value.conversations.any { it.cid == incoming.cid }
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
                        (current.activeMessages + incoming.toMessage())
                            .distinctBy { it.mid }
                            .sortedBy { it.seq }
                    } else current.activeMessages
                    current.copy(conversations = updatedConvs, activeMessages = newMessages)
                }
                // The message landed on the thread the user is viewing — advance the
                // server read cursor so the unread badge doesn't resurrect on refresh.
                if (_uiState.value.activeCid == incoming.cid) {
                    runCatching { client.markRead(incoming.cid, incoming.seq) }
                }
                // First message of a conversation we don't have listed yet (e.g. a peer
                // just opened a new direct chat) — pull it into the list.
                if (!known) refreshConversations()
            }
        }
    }

    fun selectConversation(cid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeCid = cid, activeMessages = emptyList(), error = null) }
            try {
                val history = client.loadHistory(cid)
                _uiState.update {
                    // The user may have switched conversations while history loaded —
                    // don't clobber the now-active thread with this stale result.
                    if (it.activeCid != cid) return@update it
                    val convs = it.conversations.map { c ->
                        if (c.cid == cid) c.copy(unreadCount = 0) else c
                    }
                    // Server returns newest-first; reverse for top-to-bottom rendering.
                    // Merge (not overwrite) so a live message that arrived during the
                    // load isn't dropped.
                    val merged = (history.messages.asReversed() + it.activeMessages)
                        .distinctBy { m -> m.mid }
                        .sortedBy { m -> m.seq }
                    it.copy(activeMessages = merged, conversations = convs)
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

    fun sendText(cid: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val ack: AckPayload = client.sendText(cid, trimmed)
                Log.d(TAG, "sendText ack mid=${ack.mid} seq=${ack.seq}")
                // Clear the composer only once the server has acked, so a failed send
                // keeps the user's text instead of silently dropping it.
                _uiState.update { it.copy(error = null, sentTick = it.sentTick + 1) }
            } catch (e: Throwable) {
                Log.w(TAG, "sendText failed", e)
                _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
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
                // connect() no-ops while state is CONNECTING/CONNECTED, and a failed
                // initial connect leaves the SDK pinned at CONNECTING — disconnect first
                // to guarantee a fresh attempt.
                client.disconnect()
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
            // Clear any stale error now that we've recovered, so the banner doesn't linger.
            _uiState.update { it.copy(conversations = list, error = null) }
        } catch (e: Throwable) {
            Log.w(TAG, "listConversations failed", e)
            _uiState.update { it.copy(error = e.message ?: e::class.simpleName) }
        }
    }

    override fun onCleared() {
        super.onCleared()
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
    /**
     * Increments each time a message is successfully acked by the server. The input
     * bar watches this to clear its text only after a confirmed send (so a failed
     * send keeps the user's draft).
     */
    val sentTick: Int = 0,
)

/** Map an inbound WS payload to the REST [Message] shape the UI renders. */
private fun MsgOutPayload.toMessage(): Message = Message(
    mid = mid,
    cid = cid,
    senderUid = senderUid,
    seq = seq,
    contentType = contentType,
    body = body,
    ts = createdAt,
)
