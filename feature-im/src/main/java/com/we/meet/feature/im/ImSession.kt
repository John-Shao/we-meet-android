package com.we.meet.feature.im

import android.content.Context
import android.util.Log
import com.jusi.lightim.BackoffConfig
import com.jusi.lightim.Client
import com.jusi.lightim.ConnectionState
import com.we.meet.feature.im.call.CallController
import com.we.meet.feature.im.call.CallHost
import com.we.meet.feature.im.data.ChatUploadRepository
import com.we.meet.feature.im.data.ConversationRepository
import com.we.meet.feature.im.data.DeletedMessageStore
import com.we.meet.feature.im.data.ImApi
import com.we.meet.feature.im.data.ImBridgeRepository
import com.we.meet.feature.im.data.MediaResolver
import com.we.meet.feature.im.data.UserDirectory
import com.we.meet.feature.im.net.ImNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Process-wide IM session — owns the single jusi-light-im [Client], the bridge
 * repositories, and the live conversation state that multiple screens share
 * (list screen, chat screens, the tab badge).
 *
 * Created lazily on first access ([get]); torn down on sign-out ([shutdown])
 * so the next login doesn't inherit the previous user's socket or caches.
 *
 * Reconnect compensation is mandatory, not polish: messages missed while the
 * WS was down never re-deliver, so every RECONNECTING → CONNECTED transition
 * refreshes the conversation list and emits [onResynced] — open chat screens
 * reload their newest page + read snapshot on that tick.
 */
class ImSession private constructor(deps: ImDeps, appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val everyoneLabel = appContext.getString(R.string.im_mention_everyone)

    private val api: ImApi = ImNetwork.retrofit(deps).create(ImApi::class.java)
    internal val bridge = ImBridgeRepository(api)

    private val _selfUid = MutableStateFlow<String?>(null)
    val selfUid: StateFlow<String?> = _selfUid.asStateFlow()

    // SDK 用自己独立的 OkHttp (无 Interceptor), 不能复用 we-meet 主 OkHttp:
    // 主 OkHttp 的 AuthInterceptor 会把 Keycloak Bearer 无差别覆盖到 jusi 请求,
    // 顶掉 SDK 内部塞的 IM JWT, 触发 jusi 401 -> Authenticator 死循环 refresh.
    //
    // pingInterval 是移动端「经常发送失败」的关键:默认 OkHttp 不发 WS 心跳,
    // 闲置/切后台时连接被服务端 70s 读超时静默关闭,半开死连接要到下次写才暴露。
    // 20s 客户端心跳既保活 NAT, 又能在 ~20s 内检测到断连 -> onFailure -> 触发重连,
    // 20s < 服务端 WS_PING_INTERVAL(30s) < WS_READ_TIMEOUT(70s), 留足余量。
    private val imOkHttp: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val client: Client = Client(
        baseUrl = deps.jusiImBaseUrl,
        // Minting the IM token also tells us our own jusi uid — cache it for
        // read-receipt / own-message detection everywhere.
        tokenProvider = {
            val tok = bridge.token()
            _selfUid.value = tok.uid
            tok.token
        },
        okHttp = imOkHttp,
        backoffConfig = BackoffConfig.default(),
    )

    internal val conversations = ConversationRepository(client, scope) { _selfUid.value }

    /**
     * P1 一对一通话 state machine. Room ops come from the host app when it
     * implements [CallHost] (WeMeetApp does); a host without it degrades to
     * "call buttons error out", never a crash.
     */
    val calls = CallController(client, scope, { _selfUid.value }, deps as? CallHost)
    internal val userDirectory = UserDirectory(bridge, scope)
    internal val mediaResolver = MediaResolver(bridge)
    internal val uploads = ChatUploadRepository(bridge, appContext.contentResolver)

    /** Per-device 「删除消息」持久化(仅本端隐藏,jusi 无服务端删除)。 */
    internal val deletedMessages = DeletedMessageStore(appContext)

    val connectionState: StateFlow<ConnectionState> = client.state

    /** Sum of unread across non-muted conversations — drives the tab badge. */
    val totalUnread: StateFlow<Long> get() = conversations.totalUnread

    private val _onResynced = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Fires after a reconnect once the conversation list has been re-fetched. */
    val onResynced: SharedFlow<Unit> = _onResynced.asSharedFlow()

    private val _mentionedCids = MutableStateFlow<Set<String>>(emptySet())

    /** cids with an unread message that @-mentioned me → red "@" in the list. */
    val mentionedCids: StateFlow<Set<String>> = _mentionedCids.asStateFlow()

    /** cid currently open on screen — its inbound messages don't raise a mention flag. */
    @Volatile
    private var activeCid: String? = null

    init {
        conversations.start()
        // @-mention detection (text heuristic, mirrors web): an inbound group
        // message from someone else that names me (@myDisplayName) or @everyone
        // (unless muted / mute-at-all) flags the conversation.
        scope.launch {
            client.messages.collect { m ->
                val self = _selfUid.value
                if (m.senderUid == self || m.cid == activeCid) return@collect
                if (m.contentType != "text") return@collect
                val summary = conversations.conversations.value.firstOrNull { it.cid == m.cid }
                if (summary?.type != "group" || summary.muted) return@collect
                val selfName = self?.let { userDirectory.get(it)?.displayName }
                val mentionsSelf = !selfName.isNullOrBlank() && m.body.contains("@$selfName")
                val mentionsAll = m.body.contains("@$everyoneLabel")
                if (mentionsSelf || (mentionsAll && !summary.muteAtAll)) {
                    _mentionedCids.value = _mentionedCids.value + m.cid
                }
            }
        }
        // 拍板 #2: non-connected call terminals persist a "未接来电" message so
        // both sides see the attempt in the chat (and the callee learns about
        // invites lost to a WS gap). Caller-side only (CallController gates).
        scope.launch {
            calls.callLogRequests.collect { req ->
                runCatching {
                    client.sendText(
                        cid = req.cid,
                        body = "{\"media\":\"${req.media}\",\"result\":\"${req.result}\"}",
                        contentType = "call-log",
                    )
                }.onFailure { Log.w(TAG, "call-log send failed", it) }
            }
        }
        // Resync-on-reconnect: WS gaps are silent message loss.
        // The SDK transitions RECONNECTING → CONNECTING → CONNECTED, so we can't
        // compare against the immediate previous state (it's always CONNECTING
        // at the CONNECTED tick). Latch on RECONNECTING, consume on the next
        // CONNECTED. The initial connect (DISCONNECTED → CONNECTING → CONNECTED)
        // never sets the latch, so it doesn't trigger a spurious resync.
        scope.launch {
            var sawReconnect = false
            client.state.collect { state ->
                when (state) {
                    ConnectionState.RECONNECTING -> sawReconnect = true
                    ConnectionState.CONNECTED -> if (sawReconnect) {
                        sawReconnect = false
                        conversations.refresh()
                        _onResynced.tryEmit(Unit)
                    }
                    else -> Unit
                }
            }
        }
        connect()
    }

    /** Mark [cid] as the on-screen chat (suppresses + clears its mention flag). */
    fun setActiveConversation(cid: String?) {
        activeCid = cid
        if (cid != null && cid in _mentionedCids.value) {
            _mentionedCids.value = _mentionedCids.value - cid
        }
    }

    /** The everyone token to insert for @-all (locale-aware). */
    fun everyoneLabel(): String = everyoneLabel

    /** Idempotent connect + initial list load. */
    fun connect() {
        scope.launch {
            try {
                client.connect()
                conversations.refresh()
            } catch (e: Throwable) {
                Log.w(TAG, "IM connect failed", e)
            }
        }
    }

    /** Hard retry after an error/auth failure: tear the socket down first. */
    fun retry() {
        scope.launch {
            try {
                client.disconnect()
                client.connect()
                conversations.refresh()
            } catch (e: Throwable) {
                Log.w(TAG, "IM retry failed", e)
            }
        }
    }

    private fun close() {
        client.close()
        conversations.clear()
        userDirectory.clear()
        mediaResolver.clear()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ImSession"

        @Volatile
        private var instance: ImSession? = null

        /** Lazily create (or return) the process-wide session. */
        fun get(deps: ImDeps): ImSession {
            // The deps object is the Application in practice, so the cast below
            // for a Context is safe; a custom ImDeps in tests must also be a Context.
            return instance ?: synchronized(this) {
                instance ?: ImSession(deps, deps as Context).also { instance = it }
            }
        }

        /** Tear down on sign-out; the next [get] builds a fresh session. */
        fun shutdown() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
