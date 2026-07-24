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
import com.we.meet.feature.im.data.DocHit
import com.we.meet.feature.im.data.DocsApi
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

    // 分享云文档到聊天(入口 A):独立小接口,不塞进 ImBridgeRepository——
    // 那是专门包 core/api/im.py 的门面,这条端点属于 core/api/search.py。
    private val docsApi: DocsApi = ImNetwork.retrofit(deps).create(DocsApi::class.java)

    /** "我的文档"列表(选择器初始态/搜索),`query` 为空即最近文档。 */
    internal suspend fun myDocuments(query: String? = null): List<DocHit> =
        docsApi.myDocuments(query).results

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
     * P8:app 层跨模块发送自定义 content_type 消息(日程 event-card 回发)。
     * SDK 的 Client 类型不对 app 模块暴露(implementation 依赖),经此转发;
     * 用会话级 [scope] fire-and-forget —— 调用方(导航条目)可能在发送完成前
     * 就已 pop 销毁,不能用它的 rememberCoroutineScope。失败仅记日志(best-effort,
     * 日程本体是 source of truth,卡片只是投影)。
     */
    fun sendMessageAsync(cid: String, body: String, contentType: String) {
        scope.launch {
            runCatching { client.sendText(cid, body, contentType = contentType) }
                .onFailure {
                    android.util.Log.w("ImSession", "sendMessageAsync($contentType) failed", it)
                }
        }
    }

    /**
     * 分享云文档到聊天(入口 B):Docs tab 内触发,不在某个具体会话里 ——
     * 没有 ChatViewModel.forwardTargets() 那样"排除当前会话"的概念,全量返回。
     * 逻辑与 forwardTargets() 同源(那边继续用自己的版本,重写有回归风险);
     * 这里是给 IM 连接树/某个 ChatViewModel 之外的调用者(MainTabScreen)用的。
     */
    fun allForwardTargets(): List<com.we.meet.feature.im.vm.ChatViewModel.ForwardTarget> {
        val self = _selfUid.value
        return conversations.conversations.value.map { c ->
            val isGroup = c.type == "group"
            val peerUid = if (!isGroup) c.members.firstOrNull { it != self } else null
            val peer = peerUid?.let { userDirectory.get(it) }
            val title = if (isGroup) {
                c.name.ifBlank { ((c.meta as? Map<*, *>)?.get("name") as? String).orEmpty() }
            } else {
                peer?.displayName.orEmpty()
            }
            com.we.meet.feature.im.vm.ChatViewModel.ForwardTarget(
                cid = c.cid,
                title = title,
                isGroup = isGroup,
                avatarUrl = if (!isGroup) peer?.avatarUrl?.takeIf { it.isNotBlank() } else null,
                avatarKey = peerUid ?: c.cid,
                memberTiles = if (isGroup) c.members.take(9).map { uid ->
                    val info = userDirectory.get(uid)
                    com.we.meet.feature.im.data.GroupTile(
                        uid = uid,
                        name = info?.displayName ?: uid.take(2),
                        avatarUrl = info?.avatarUrl?.takeIf { it.isNotBlank() },
                    )
                } else emptyList(),
            )
        }
    }

    /**
     * P1 一对一通话 state machine. Room ops come from the host app when it
     * implements [CallHost] (WeMeetApp does); a host without it degrades to
     * "call buttons error out", never a crash.
     */
    val calls = CallController(client, scope, { _selfUid.value }, deps as? CallHost)

    /** P4 通话中拉人 — parallel escalation-invite fan-out (the 1:1 machine above stays untouched). */
    val meetInvites = com.we.meet.feature.im.call.MeetInviteTracker(client, scope, bridge)
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
                    val duration =
                        if (req.durationSec > 0) ",\"duration\":${req.durationSec}" else ""
                    val slug =
                        req.slug?.let { ",\"slug\":\"$it\"" } ?: ""
                    client.sendText(
                        cid = req.cid,
                        body = "{\"media\":\"${req.media}\",\"result\":\"${req.result}\"$duration$slug}",
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

        /**
         * The live session if one exists — does NOT lazily create. For hooks
         * that fire outside the IM lifecycle (e.g. the meeting FGS teardown
         * notifying the call controller) where creating a session as a side
         * effect would be wrong.
         */
        fun peek(): ImSession? = instance

        /** Tear down on sign-out; the next [get] builds a fresh session. */
        fun shutdown() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
