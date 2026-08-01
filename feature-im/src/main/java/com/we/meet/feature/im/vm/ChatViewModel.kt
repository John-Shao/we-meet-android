package com.we.meet.feature.im.vm

import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jusi.lightim.ConnectionState
import com.jusi.lightim.ConvMember
import com.jusi.lightim.Message
import com.jusi.lightim.MsgOutPayload
import com.jusi.lightim.NetworkException
import com.jusi.lightim.PinnedMessage
import com.jusi.lightim.ReactionGroup
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.userMessageRes
import com.we.meet.feature.im.data.ChatUploadException
import com.we.meet.feature.im.data.DocHit
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.data.ImUserInfo
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.feature.im.model.MessageContentParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One in-flight optimistic send shown at the bottom of the thread. */
data class PendingSend(
    val localId: Long,
    /** text | image | file */
    val kind: String,
    val text: String = "",
    val failed: Boolean = false,
)

/** Aggregated reactions on one message: emoji → reacting uids (in arrival order). */
typealias ReactionMap = Map<String, List<String>>

data class ChatUiState(
    val cid: String = "",
    val isGroup: Boolean = false,
    val title: String = "",
    /** Direct chats only: the peer's uid (P1 通话 needs a call target); null for groups. */
    val peerUid: String? = null,
    /**
     * P10:私聊对端已从组织离职。单独一个 flag 而不是把「(已离职)」拼进 [title]
     * —— title 会顺着 peerName / roomName 流进通话与会议室命名,那些地方不该带后缀。
     */
    val peerLeft: Boolean = false,
    /** Renderable rows only — control messages (recall/reaction) are filtered out. */
    val messages: List<Message> = emptyList(),
    /** mids replaced by a recall tombstone. */
    val recalledMids: Set<Long> = emptySet(),
    /** target mid → aggregated reactions (replayed in seq order, add/remove). */
    val reactions: Map<Long, ReactionMap> = emptyMap(),
    val pending: List<PendingSend> = emptyList(),
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    /** uid → last-read seq, live-merged; drives read receipts. */
    val readMarkers: Map<String, Long> = emptyMap(),
    /** Group roster (uids), for read-receipt counting; empty for directs. */
    val memberUids: List<String> = emptyList(),
    /** P10 群昵称:uid → per-conversation nickname (blank names omitted). */
    val nicknames: Map<String, String> = emptyMap(),
    val selfUid: String? = null,
    /** Localized message resource, not raw exception text — see [userMessageRes]. */
    @StringRes val error: Int? = null,
    /** Stable upload-error code the UI maps to an i18n string; null = none. */
    val uploadError: ChatUploadException.Code? = null,
    /** Bumps after each acked text send — the composer clears on this. */
    val sentTick: Int = 0,
    /** P17 会话共享置顶(newest first,快照内联)。 */
    val pins: List<PinnedMessage> = emptyList(),
    /** P1-M3 搜索定位:待滚动高亮的 mid(消费后置空)。 */
    val locateMid: Long? = null,
)

/** One-shot events the screen reacts to (toast + pop, etc.). */
sealed interface ChatEvent {
    data object RemovedFromConversation : ChatEvent
}

/**
 * Chat-thread VM, keyed by cid. In-memory seq-ordered paging (newest page on
 * open, `before_seq` upward), live WS append, read-marker merging, optimistic
 * sends. Read marking only happens while the screen is RESUMED ([setVisible])
 * so a backgrounded chat can't eat unread counts.
 */
class ChatViewModel internal constructor(
    private val session: ImSession,
    private val cid: String,
    /** P1-M3 搜索定位:进入后回翻到该 seq 并高亮(一次性)。 */
    private val locateSeq: Long? = null,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState(cid = cid))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = session.connectionState

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    /** Bumped by UserDirectory when new identities resolve — collect to re-render names. */
    val directoryVersion: StateFlow<Int> = session.userDirectory.version

    @Volatile
    private var visible = false

    private var nextLocalId = 1L

    /**
     * Full seq-ordered window including control messages (recall/reaction) —
     * they must survive merges so tombstones/reactions can be replayed, but
     * [ChatUiState.messages] only carries renderable rows.
     */
    private var raw: List<Message> = emptyList()

    /** Locally-deleted mids (仅本端删除,持久化);filtered out of rendered rows. */
    private var deletedMids: Set<Long> = session.deletedMessages.get(cid)

    /** Skip the first resume-triggered reload — init() already loaded the newest
     *  page + read snapshot, so the first RESUMED tick would double-fetch. */
    private var reloadArmed = false

    init {
        _ui.update { it.copy(selfUid = session.selfUid.value) }
        viewModelScope.launch { session.selfUid.collect { uid -> _ui.update { s -> s.copy(selfUid = uid) } } }

        refreshConversationShape()
        loadNewest()
        loadReadSnapshot()
        if (isGroupGuess()) loadMembers()

        // Live message append (this thread only).
        viewModelScope.launch {
            session.client.messages.collect { m ->
                if (m.cid != cid) return@collect
                appendMessages(listOf(m.toMessage()))
                if (visible) markRead(m.seq)
            }
        }
        // P16 native recall:patch the raw final state and re-derive — the
        // legacy tombstone replay in deriveRows keeps covering pre-P16 rows.
        viewModelScope.launch {
            session.client.recalledMessages.collect { e ->
                if (e.cid != cid) return@collect
                raw = raw.map { if (it.mid == e.mid) it.copy(recalled = true, body = "") else it }
                _ui.update { deriveRows(it) }
            }
        }
        // P16 native reaction deltas → patch the message's aggregate in place.
        viewModelScope.launch {
            session.client.reactions.collect { e ->
                if (e.cid != cid) return@collect
                raw = raw.map { m ->
                    if (m.mid != e.mid) m
                    else {
                        val groups = m.reactions.toMutableList()
                        val idx = groups.indexOfFirst { it.emoji == e.emoji }
                        when {
                            e.op == "add" && idx < 0 ->
                                groups += ReactionGroup(e.emoji, listOf(e.uid))
                            e.op == "add" && e.uid !in groups[idx].uids ->
                                groups[idx] = groups[idx].copy(uids = groups[idx].uids + e.uid)
                            e.op == "remove" && idx >= 0 ->
                                groups[idx] = groups[idx].copy(uids = groups[idx].uids - e.uid)
                        }
                        m.copy(reactions = groups.filter { it.uids.isNotEmpty() })
                    }
                }
                _ui.update { deriveRows(it) }
            }
        }
        // P17 pin set changed (own or peers') → refetch the snapshot list.
        viewModelScope.launch {
            session.client.pins.collect { e ->
                if (e.cid != cid) return@collect
                loadPins()
            }
        }
        loadPins()
        // Live read markers — monotonic merge, everyone's (receipts need peers').
        viewModelScope.launch {
            session.client.reads.collect { r ->
                if (r.cid != cid) return@collect
                _ui.update { s ->
                    val prev = s.readMarkers[r.uid] ?: 0L
                    if (r.seq <= prev) s
                    else s.copy(readMarkers = s.readMarkers + (r.uid to r.seq))
                }
            }
        }
        // Conversation lifecycle: kicked → pop; renamed/membership → refresh shape.
        viewModelScope.launch {
            session.client.conversationEvents.collect { ev ->
                if (ev.cid != cid) return@collect
                val self = session.selfUid.value
                if (ev.event == "member_removed" && self != null && self !in ev.members) {
                    _events.tryEmit(ChatEvent.RemovedFromConversation)
                } else {
                    refreshConversationShape()
                    if (_ui.value.isGroup) loadMembers()
                }
            }
        }
        // Reconnect gap compensation: reload the newest page + read snapshot.
        viewModelScope.launch {
            session.onResynced.collect {
                loadNewest()
                loadReadSnapshot()
            }
        }
        // Drop a stale connection-error banner (e.g. "sendText: not connected")
        // once the socket recovers — only on the recovery EDGE, so a genuine
        // error raised while already CONNECTED isn't wiped by the initial emit.
        viewModelScope.launch {
            var wasConnected = connectionState.value == ConnectionState.CONNECTED
            connectionState.collect { st ->
                val nowConnected = st == ConnectionState.CONNECTED
                if (nowConnected && !wasConnected) {
                    _ui.update { s -> if (s.error != null) s.copy(error = null) else s }
                }
                wasConnected = nowConnected
            }
        }
        // Re-derive a direct chat's title once the peer's profile resolves —
        // refreshConversationShape only reads the cache once, so a cold peer
        // (e.g. opened from contacts) would otherwise stay "Untitled chat".
        viewModelScope.launch {
            session.userDirectory.version.collect {
                val s = _ui.value
                if (s.isGroup) return@collect
                val self = session.selfUid.value
                val peerUid = session.conversations.conversations.value
                    .firstOrNull { it.cid == cid }?.members?.firstOrNull { it != self } ?: return@collect
                val peer = session.userDirectory.get(peerUid)
                val name = peer?.displayName
                val left = peer?.left ?: false
                if ((!name.isNullOrBlank() && name != s.title) || left != s.peerLeft) {
                    _ui.update {
                        it.copy(
                            title = if (name.isNullOrBlank()) it.title else name,
                            peerLeft = left,
                        )
                    }
                }
            }
        }
    }

    // ---- lifecycle ----

    /** The screen reports RESUMED visibility; read marking only happens while true. */
    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        // Suppress + clear this conversation's @-mention flag while it's open.
        session.setActiveConversation(if (isVisible) cid else null)
        if (isVisible) {
            raw.lastOrNull()?.let { markRead(it.seq) }
        }
    }

    /** Reload the newest page from the server — called on resume so that
     *  "clear history" / external mutations take effect immediately. */
    fun reloadHistory() {
        // init() already did the first load; skip the first RESUMED tick to avoid
        // a redundant double-fetch on entering the chat. Later resumes (real
        // background→foreground / clear-history / resync) reload as before.
        if (!reloadArmed) {
            reloadArmed = true
            return
        }
        loadNewest()
        loadReadSnapshot()
    }

    /** Hard retry the WS connection — mirrors ConversationListViewModel.retryConnection. */
    fun retryConnection() = session.retry()

    /**
     * A conversation the user can forward into (excludes the current one).
     * Carries avatar data so the Feishu-style picker can render the same
     * direct/group avatars the conversation list uses.
     */
    data class ForwardTarget(
        val cid: String,
        val title: String,
        val isGroup: Boolean,
        /** Direct-chat peer avatar (null for groups). */
        val avatarUrl: String?,
        /** Stable avatar cache identity (peer uid for directs, cid otherwise). */
        val avatarKey: String,
        /** Pre-resolved tiles for the 9-grid group avatar (groups only). */
        val memberTiles: List<GroupTile>,
        /** 单聊对端的 we-meet user id —— 用来和通讯录搜索结果去重(已有会话的人
         * 不该在「通讯录」分组里再出现一次)。未解析到时为 null,不参与去重。 */
        val peerUserId: String? = null,
    )

    /** Forward destinations: every other conversation, titled + avatared for display. */
    fun forwardTargets(): List<ForwardTarget> {
        val self = _ui.value.selfUid
        return session.conversations.conversations.value
            .filter { it.cid != cid }
            .map { c ->
                val isGroup = c.type == "group"
                val peerUid = if (!isGroup) c.members.firstOrNull { it != self } else null
                val peer = peerUid?.let { session.userDirectory.get(it) }
                val title = if (isGroup) {
                    c.name.ifBlank { ((c.meta as? Map<*, *>)?.get("name") as? String).orEmpty() }
                } else {
                    peer?.displayName.orEmpty()
                }
                ForwardTarget(
                    cid = c.cid,
                    title = title,
                    isGroup = isGroup,
                    avatarUrl = if (!isGroup) peer?.avatarUrl?.takeIf { it.isNotBlank() } else null,
                    avatarKey = peerUid ?: c.cid,
                    memberTiles = if (isGroup) c.members.take(9).map { uid ->
                        val info = session.userDirectory.get(uid)
                        GroupTile(
                            uid = uid,
                            name = info?.displayName ?: uid.take(2),
                            avatarUrl = info?.avatarUrl?.takeIf { it.isNotBlank() },
                        )
                    } else emptyList(),
                    peerUserId = if (!isGroup) peer?.id?.takeIf { it.isNotBlank() } else null,
                )
            }
    }

    /** Re-send [message] verbatim (same content_type/body) into [targetCid]. */
    fun forward(message: Message, targetCid: String) {
        viewModelScope.launch {
            runCatching { session.client.sendText(targetCid, message.body, contentType = message.contentType) }
                .onFailure { Log.w(TAG, "forward failed", it) }
        }
    }

    /** Package [messages] into one merged chat-record and send to [targetCid]. */
    fun forwardMerged(messages: List<Message>, targetCid: String) {
        if (messages.isEmpty()) return
        val self = _ui.value.selfUid
        val items = org.json.JSONArray()
        messages.sortedBy { it.seq }.forEach { m ->
            val sender = session.userDirectory.get(m.senderUid)?.displayName.orEmpty()
            items.put(
                JSONObject()
                    .put("sender", sender)
                    .put("text", mergedTextOf(m))
                    .put("ts", m.ts),
            )
        }
        val body = JSONObject()
            .put("title", _ui.value.title)
            .put("count", items.length())
            .put("items", items)
            .toString()
        viewModelScope.launch {
            runCatching { session.client.sendText(targetCid, body, contentType = "merged") }
                .onFailure { Log.w(TAG, "forwardMerged failed", it) }
        }
    }

    /**
     * Forward each selected message individually (逐条转发) to [targetCid].
     * Sends messages in seq order so the target conversation displays them
     * in the same chronological sequence.
     */
    fun forwardOneByOne(messages: List<Message>, targetCid: String) {
        if (messages.isEmpty()) return
        viewModelScope.launch {
            messages.sortedBy { it.seq }.forEach { m ->
                runCatching {
                    session.client.sendText(targetCid, m.body, contentType = m.contentType)
                }.onFailure { Log.w(TAG, "forwardOneByOne failed for mid=${m.mid}", it) }
            }
        }
    }

    /** Full plain-text for a merged line (media → placeholder, text/quote → full). */
    private fun mergedTextOf(m: Message): String =
        when (val c = MessageContentParser.parse(m.contentType, m.body)) {
            is MessageContent.Text -> c.body
            is MessageContent.Quote -> c.text
            is MessageContent.Image -> "[图片]"
            is MessageContent.File -> "[文件] ${c.name}"
            is MessageContent.Voice -> "[语音]"
            is MessageContent.Merged -> "[聊天记录]"
            else -> ""
        }

    /** @-mention candidates for the input dropdown: 所有人 + other members (群昵称 优先). */
    fun mentionCandidates(): List<String> {
        if (!_ui.value.isGroup) return emptyList()
        val self = _ui.value.selfUid
        val names = _ui.value.memberUids.filter { it != self }.mapNotNull { senderName(it) }
        return listOf(session.everyoneLabel()) + names
    }

    /** Names highlightable as @mentions inside bubbles: 所有人 + ALL members (群昵称 优先). */
    fun mentionHighlightNames(): List<String> {
        if (!_ui.value.isGroup) return emptyList()
        val names = _ui.value.memberUids.mapNotNull { senderName(it) }
        return listOf(session.everyoneLabel()) + names
    }

    /** Subset that means "you" (self 群昵称/名 + 所有人) → stronger highlight. */
    fun selfMentionNames(): List<String> {
        if (!_ui.value.isGroup) return emptyList()
        val self = _ui.value.selfUid?.let { senderName(it) }
        return listOfNotNull(session.everyoneLabel(), self)
    }

    // ---- paging ----

    fun loadOlder() {
        val state = _ui.value
        if (state.loadingOlder || !state.hasMore) return
        val oldest = raw.firstOrNull()?.seq ?: return
        _ui.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            runCatching { session.client.loadHistory(cid, beforeSeq = oldest, limit = PAGE_SIZE) }
                .onSuccess { res ->
                    mergeRaw(res.messages.asReversed(), keepOldest = true)
                    _ui.update { s ->
                        deriveRows(s.copy(hasMore = res.hasMore, loadingOlder = false))
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "loadOlder failed", e)
                    _ui.update { it.copy(loadingOlder = false, error = e.userMessageRes()) }
                }
        }
    }

    // ---- sends ----

    fun sendText(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                sendViaSocket(trimmed, "text")
                _ui.update { it.copy(error = null, sentTick = it.sentTick + 1) }
            } catch (e: Throwable) {
                Log.w(TAG, "sendText failed", e)
                _ui.update { it.copy(error = e.userMessageRes()) }
            }
        }
    }

    /**
     * Send one WS text frame, resilient to a dead-but-not-yet-detected socket.
     *
     * A [NetworkException] means the frame never reached the server (socket
     * closed, or died inside the ~ping-detection window while [connectionState]
     * still read CONNECTED so [ensureConnected] was a no-op). Since nothing was
     * acked, force a reconnect and retry the send ONCE — safe from duplication.
     * Ack timeouts are deliberately NOT retried (the server may have received
     * it). Throws if still unconnected after the retry → caller shows the error.
     */
    private suspend fun sendViaSocket(body: String, contentType: String) {
        ensureConnected()
        try {
            session.client.sendText(cid, body, contentType = contentType)
        } catch (e: NetworkException) {
            session.retry()
            val reconnected = withTimeoutOrNull(CONNECT_WAIT_MS) {
                connectionState.first { it == ConnectionState.CONNECTED }
            } != null
            if (!reconnected) throw e
            session.client.sendText(cid, body, contentType = contentType)
        }
    }

    /**
     * Bring the socket up before a send. A terminal-state socket gets a retry;
     * then we WAIT (bounded) for CONNECTED — otherwise the send races the async
     * reconnect and the current attempt fails even though a retry was kicked off
     * (the user then has to tap send again). Times out gracefully so a truly
     * offline send still falls through to sendText's error handling.
     */
    private suspend fun ensureConnected() {
        val s = connectionState.value
        if (s == ConnectionState.CONNECTED) return
        // CONNECTING 已有一次建连在途,静候即可;其余状态——尤其 RECONNECTING
        // 可能正卡在退避到 30s 的等待节拍上——用户主动发消息时强制立刻重连:
        // retry() 会 disconnect+connect 并复位退避,避免发送干等而报 not connected。
        if (s != ConnectionState.CONNECTING) {
            session.retry()
        }
        withTimeoutOrNull(CONNECT_WAIT_MS) {
            connectionState.first { it == ConnectionState.CONNECTED }
        }
    }

    fun sendImage(uri: Uri) = sendMedia(kind = "image") {
        val objectKey = session.uploads.uploadImage(uri)
        session.client.sendText(cid, objectKey, contentType = "image")
    }

    fun sendFile(uri: Uri) = sendMedia(kind = "file") {
        val meta = session.uploads.uploadFile(uri)
        val body = JSONObject()
            .put("key", meta.key)
            .put("name", meta.name)
            .put("size", meta.size)
            .toString()
        session.client.sendText(cid, body, contentType = "file")
    }

    fun dismissUploadError() {
        _ui.update { it.copy(uploadError = null) }
    }

    /** 分享云文档到聊天(入口 A)选择器数据源:"我的文档",空 query 即最近文档。 */
    suspend fun myDocuments(query: String? = null): List<DocHit> = session.myDocuments(query)

    /** 选择器多选后逐个发 content_type="doc-card"(飞书式,单条失败不影响其余)。 */
    fun sendDocs(docs: List<DocHit>) {
        viewModelScope.launch {
            ensureConnected()
            docs.forEach { doc ->
                runCatching {
                    val body = JSONObject()
                        .put("v", 1)
                        .put("doc_id", doc.id)
                        .put("title", doc.title)
                        .put("url", doc.url)
                        .toString()
                    session.client.sendText(cid, body, contentType = "doc-card")
                    // 分享即精准授权:让本会话成员点开卡片能直接看(best-effort)。
                    session.grantDocAccessAsync(doc.id, listOf(cid))
                }.onFailure { Log.w(TAG, "sendDoc failed for ${doc.id}", it) }
            }
        }
    }

    /** Upload a recorded clip then send content_type="voice", body `{key,duration}`. */
    fun sendVoice(file: java.io.File, durationMs: Long) = sendMedia(kind = "voice") {
        try {
            val objectKey = session.uploads.uploadVoice(file)
            session.client.sendText(
                cid,
                JSONObject().put("key", objectKey).put("duration", durationMs).toString(),
                contentType = "voice",
            )
        } finally {
            file.delete()
        }
    }

    // ---- long-press actions (quote / recall / reaction) ----

    /**
     * Reply to [replyTo] with [text]. Body mirrors web:
     * `{reply_to:{sender,snippet}, text}`, content_type=quote. `sender` is the
     * resolved display name baked in at send time (target convo may not know it).
     */
    fun sendQuote(replyTo: Message, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val senderName = if (replyTo.senderUid == _ui.value.selfUid) {
            null // rendered as "You" client-side via snippet sender fallback
        } else {
            session.userDirectory.get(replyTo.senderUid)?.displayName
        }
        val body = JSONObject()
            .put(
                "reply_to",
                JSONObject()
                    .put("sender", senderName ?: _ui.value.title)
                    .put("snippet", snippetOf(replyTo)),
            )
            .put("text", trimmed)
            .toString()
        viewModelScope.launch {
            try {
                sendViaSocket(body, "quote")
                _ui.update { it.copy(error = null, sentTick = it.sentTick + 1) }
            } catch (e: Throwable) {
                Log.w(TAG, "sendQuote failed", e)
                _ui.update { it.copy(error = e.userMessageRes()) }
            }
        }
    }

    /** Recall [message] — P16 服务端原生(仅本人 + 服务端时窗;成功经
     *  recalledMessages 事件回流)。不再发 content_type='recall' 控制消息。 */
    fun recall(message: Message) {
        viewModelScope.launch {
            try {
                session.client.recall(cid, message.mid)
            } catch (e: Throwable) {
                Log.w(TAG, "recall failed", e)
                _ui.update { it.copy(error = e.userMessageRes()) }
            }
        }
    }

    /**
     * Batch-delete selected messages via the backend bridge. On success, the
     * messages are removed from the local cache so the UI updates immediately.
     */
    fun deleteMessages(
        mids: Collection<Long>,
        onSuccess: () -> Unit,
        /** Receives a localized message resource, not raw exception text. */
        onError: (Int) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                session.bridge.deleteMessages(cid, mids)
                // 仅本端删除:持久化已删 mid(重进/重启不复现)+ 立即从渲染中过滤。
                session.deletedMessages.add(cid, mids)
                deletedMids = deletedMids + mids
                raw = raw.filterNot { it.mid in mids }
                _ui.update { s ->
                    deriveRows(s).copy(error = null)
                }
                onSuccess()
            } catch (e: Throwable) {
                Log.w(TAG, "deleteMessages failed", e)
                _ui.update { it.copy(error = e.userMessageRes()) }
                onError(e.userMessageRes())
            }
        }
    }

    /** Whether the caller has an active reaction [emoji] on [mid] (for toggle). */
    fun hasMyReaction(mid: Long, emoji: String): Boolean {
        val self = _ui.value.selfUid ?: return false
        return _ui.value.reactions[mid]?.get(emoji)?.contains(self) == true
    }

    /** Toggle [emoji] on [message] — P16 服务端原生(幂等;增量经 reactions
     *  事件回流)。不再发 content_type='reaction' 控制消息。 */
    fun toggleReaction(message: Message, emoji: String) {
        val op = if (hasMyReaction(message.mid, emoji)) "remove" else "add"
        viewModelScope.launch {
            try {
                session.client.react(cid, message.mid, emoji, op)
            } catch (e: Throwable) {
                Log.w(TAG, "reaction failed", e)
                _ui.update { it.copy(error = e.userMessageRes()) }
            }
        }
    }

    // ---- P17 pins(会话共享置顶)----

    /** Whether [mid] is currently pinned (drives the action-sheet label). */
    fun isPinned(mid: Long): Boolean = _ui.value.pins.any { it.mid == mid }

    /** Pin / unpin [message] for the whole conversation. 权限由服务端裁决。 */
    fun togglePin(message: Message) {
        val op = if (isPinned(message.mid)) "remove" else "add"
        viewModelScope.launch {
            try {
                session.client.pin(cid, message.mid, op)
            } catch (e: Throwable) {
                Log.w(TAG, "pin failed", e)
                _ui.update { it.copy(error = e.userMessageRes()) }
            }
        }
    }

    /** Unpin from the pin bar (may be someone else's pin — server decides). */
    fun unpin(mid: Long) {
        viewModelScope.launch {
            try {
                session.client.pin(cid, mid, "remove")
            } catch (e: Throwable) {
                Log.w(TAG, "unpin failed", e)
            }
        }
    }

    private fun loadPins() {
        viewModelScope.launch {
            runCatching { session.client.listPins(cid) }
                .onSuccess { p -> _ui.update { it.copy(pins = p) } }
        }
    }

    /** True while [message] is still within the recall window (client-enforced, web parity). */
    fun canRecall(message: Message): Boolean {
        if (message.senderUid != _ui.value.selfUid) return false
        // ts is epoch millis (same basis as the list time label).
        return System.currentTimeMillis() - message.ts <= RECALL_WINDOW_MS
    }

    /** Public plain-text snippet (reply preview bar / copy / quote sender). */
    fun snippetPreview(m: Message): String = snippetOf(m)

    /** Plain-text snippet of a message for quote previews (media → placeholder). */
    private fun snippetOf(m: Message): String =
        when (val c = MessageContentParser.parse(m.contentType, m.body)) {
            is MessageContent.Text -> c.body.take(SNIPPET_MAX)
            is MessageContent.Quote -> c.text.take(SNIPPET_MAX)
            is MessageContent.Image -> "[图片]"
            is MessageContent.File -> "[文件] ${c.name}"
            is MessageContent.Voice -> "[语音]"
            is MessageContent.Merged -> "[聊天记录]"
            else -> ""
        }

    // ---- read receipts ----

    /**
     * Receipt for the caller's own latest message: direct → peer read yes/no;
     * group → count of other members whose marker covers it.
     */
    fun readCountFor(seq: Long): Int {
        val s = _ui.value
        val self = s.selfUid ?: return 0
        return s.readMarkers.count { (uid, readSeq) -> uid != self && readSeq >= seq }
    }

    fun resolveUser(uid: String): ImUserInfo? = session.userDirectory.get(uid)

    /** Display name honouring the per-conversation 群昵称 (P10): nickname → directory
     *  name → null. Used for the sender label + @mention candidates. */
    fun senderName(uid: String): String? =
        _ui.value.nicknames[uid]?.takeIf { it.isNotBlank() }
            ?: session.userDirectory.get(uid)?.displayName?.takeIf { it.isNotBlank() }

    /**
     * P10:该 uid 在本组织已离职,气泡上的发送人名要标出来。
     *
     * 刻意不做成「[senderName] 直接返回带后缀的名字」—— 那个名字同时喂给 @提及
     * 候选(`mentionCandidates`)和引用/合并转发的 `sender` 字段,后两者会被**写进
     * 消息体发到服务端**,一旦带上后缀就永久冻在历史里,人复职了也改不回来。
     * 所以标记与名字分开走,只在纯渲染处合成。
     */
    fun isDeparted(uid: String): Boolean =
        session.userDirectory.get(uid)?.left == true

    /** Resolve a chat-media object key to a presigned URL (suspend, cached). */
    suspend fun resolveMediaUrl(objectKey: String): String? =
        session.mediaResolver.resolve(objectKey)

    // ---- internals ----

    private fun sendMedia(kind: String, block: suspend () -> Unit) {
        val localId = nextLocalId++
        _ui.update { it.copy(pending = it.pending + PendingSend(localId, kind)) }
        viewModelScope.launch {
            try {
                ensureConnected()
                block()
                _ui.update { s -> s.copy(pending = s.pending.filterNot { it.localId == localId }) }
            } catch (e: ChatUploadException) {
                Log.w(TAG, "upload failed: ${e.code}", e)
                _ui.update { s ->
                    s.copy(
                        pending = s.pending.filterNot { it.localId == localId },
                        uploadError = e.code,
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "send $kind failed", e)
                _ui.update { s ->
                    s.copy(
                        pending = s.pending.filterNot { it.localId == localId },
                        error = e.userMessageRes(),
                    )
                }
            }
        }
    }

    private fun loadNewest() {
        viewModelScope.launch {
            runCatching { session.client.loadHistory(cid, limit = PAGE_SIZE) }
                .onSuccess { res ->
                    raw = emptyList()  // Replace — clear history in SDK returns empty page.
                    mergeRaw(res.messages.asReversed())
                    _ui.update { s -> deriveRows(s.copy(hasMore = res.hasMore, error = null)) }
                    if (visible) {
                        raw.lastOrNull()?.let { markRead(it.seq) }
                    }
                    requestNameResolution()
                    locateSeq?.takeIf { !locateDone }?.let { locateTo(it) }
                }
                .onFailure { e ->
                    _ui.update { it.copy(error = e.userMessageRes()) }
                }
        }
    }

    /** P1-M3: 定位只执行一次(resume 重载/重试不再回翻)。 */
    private var locateDone = false

    /**
     * P1-M3 搜索定位:目标 seq 不在首屏时向旧回翻(每页 [LOCATE_PAGE],
     * 上限 [MAX_LOCATE_PAGES] 页),找到后把 mid 写进 [ChatUiState.locateMid]
     * 供屏幕滚动+高亮。找不到(被删/超上限)则静默放弃,停在最新页。
     */
    private suspend fun locateTo(target: Long) {
        locateDone = true
        var guard = 0
        while (raw.none { it.seq == target } &&
            (raw.firstOrNull()?.seq ?: 0L) > target &&
            _ui.value.hasMore &&
            guard < MAX_LOCATE_PAGES
        ) {
            guard++
            val oldest = raw.firstOrNull()?.seq ?: break
            val res = runCatching {
                session.client.loadHistory(cid, beforeSeq = oldest, limit = LOCATE_PAGE)
            }.getOrNull() ?: break
            if (res.messages.isEmpty()) break
            mergeRaw(res.messages.asReversed(), keepOldest = true)
            _ui.update { s -> deriveRows(s.copy(hasMore = res.hasMore)) }
        }
        val mid = raw.firstOrNull { it.seq == target }?.mid ?: return
        _ui.update { it.copy(locateMid = mid) }
        requestNameResolution()
    }

    /** 屏幕滚动+高亮完成后调用,防重复触发。 */
    fun consumeLocate() {
        _ui.update { it.copy(locateMid = null) }
    }

    /** P3-M3 后补:Pin 条目点击等场景的按需定位(同一套回翻+高亮)。 */
    fun locateToSeq(seq: Long) {
        viewModelScope.launch { locateTo(seq) }
    }

    private fun appendMessages(list: List<Message>) {
        mergeRaw(list)
        _ui.update { s -> deriveRows(s) }
        requestNameResolution()
    }

    /**
     * Merge [incoming] into the in-memory window, capped at [MAX_IN_MEMORY].
     * [keepOldest] controls which end survives the cap: append/live paths keep
     * the newest (default); upward paging keeps the oldest — otherwise `takeLast`
     * would discard the just-fetched older page, leaving `oldest` unchanged and
     * making the top sentinel re-fire loadOlder forever with the same beforeSeq.
     */
    private fun mergeRaw(incoming: List<Message>, keepOldest: Boolean = false) {
        val merged = (raw + incoming)
            .distinctBy { it.mid }
            .sortedBy { it.seq }
        raw = if (keepOldest) merged.take(MAX_IN_MEMORY) else merged.takeLast(MAX_IN_MEMORY)
    }

    /**
     * Replay [raw] into renderable state: recall tombstones and per-message
     * reaction aggregates (seq order, add/remove), control rows filtered out.
     */
    private fun deriveRows(s: ChatUiState): ChatUiState {
        val recalled = mutableSetOf<Long>()
        val reactions = mutableMapOf<Long, LinkedHashMap<String, MutableList<String>>>()
        // P16 native final state first (history snapshot + live-patched raw):
        // recalled flag + aggregated reactions arrive on the message itself.
        raw.forEach { m ->
            if (m.recalled) recalled += m.mid
            if (m.reactions.isNotEmpty()) {
                val perEmoji = reactions.getOrPut(m.mid) { linkedMapOf() }
                m.reactions.forEach { g -> perEmoji[g.emoji] = g.uids.toMutableList() }
            }
        }
        // Legacy control-message replay (pre-P16 rows only — disjoint from
        // native, so merging can't double count). 双读兜底,与 Web 端一致。
        raw.forEach { m ->
            when (val c = MessageContentParser.parse(m.contentType, m.body)) {
                is MessageContent.Recall -> recalled += c.targetMid
                is MessageContent.Reaction -> {
                    val perEmoji = reactions.getOrPut(c.targetMid) { linkedMapOf() }
                    val uids = perEmoji.getOrPut(c.emoji) { mutableListOf() }
                    if (c.op == "remove") uids.remove(m.senderUid)
                    else if (m.senderUid !in uids) uids.add(m.senderUid)
                }
                else -> Unit
            }
        }
        return s.copy(
            messages = raw.filterNot {
                MessageContentParser.isControlType(it.contentType) || it.mid in deletedMids
            },
            recalledMids = recalled,
            reactions = reactions
                .mapValues { (_, perEmoji) ->
                    perEmoji.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }
                }
                .filterValues { it.isNotEmpty() },
        )
    }

    private fun markRead(seq: Long) {
        viewModelScope.launch {
            runCatching { session.client.markRead(cid, seq) }
            session.conversations.markReadLocally(cid, seq)
        }
    }

    private fun loadReadSnapshot() {
        viewModelScope.launch {
            runCatching { session.client.listReads(cid) }
                .onSuccess { markers ->
                    _ui.update { s ->
                        val merged = s.readMarkers.toMutableMap()
                        markers.forEach { m ->
                            val prev = merged[m.uid] ?: 0L
                            if (m.seq > prev) merged[m.uid] = m.seq
                        }
                        s.copy(readMarkers = merged)
                    }
                }
        }
    }

    private fun loadMembers() {
        viewModelScope.launch {
            runCatching { session.client.listMembers(cid) }
                .onSuccess { roster: List<ConvMember> ->
                    val nicks = roster.mapNotNull { m ->
                        m.nickname?.takeIf { it.isNotBlank() }?.let { m.uid to it }
                    }.toMap()
                    _ui.update {
                        it.copy(memberUids = roster.map { m -> m.uid }, nicknames = nicks)
                    }
                    session.userDirectory.requestResolve(roster.map { it.uid })
                }
        }
    }

    /** Pull title/isGroup from the shared conversation list (refreshing if absent). */
    private fun refreshConversationShape() {
        viewModelScope.launch {
            var summary = session.conversations.conversations.value.firstOrNull { it.cid == cid }
            if (summary == null) {
                session.conversations.refresh()
                summary = session.conversations.conversations.value.firstOrNull { it.cid == cid }
            }
            val isGroup = summary?.type == "group"
            val self = session.selfUid.value
            val peerUid = if (!isGroup) summary?.members?.firstOrNull { it != self } else null
            if (peerUid != null) session.userDirectory.requestResolve(listOf(peerUid))
            _ui.update { s ->
                s.copy(
                    isGroup = isGroup,
                    peerUid = peerUid ?: s.peerUid,
                    title = when {
                        summary == null -> s.title
                        isGroup -> summary.name.ifBlank {
                            ((summary.meta as? Map<*, *>)?.get("name") as? String).orEmpty()
                        }
                        else -> peerUid?.let { session.userDirectory.get(it)?.displayName }
                            ?: s.title
                    },
                    memberUids = if (!isGroup) summary?.members ?: s.memberUids else s.memberUids,
                )
            }
            if (isGroup) loadMembers()
        }
    }

    private fun isGroupGuess(): Boolean =
        session.conversations.conversations.value.firstOrNull { it.cid == cid }?.type == "group"

    private fun requestNameResolution() {
        session.userDirectory.requestResolve(_ui.value.messages.map { it.senderUid }.toSet())
    }

    class Factory(
        private val deps: ImDeps,
        private val cid: String,
        private val locateSeq: Long? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(ImSession.get(deps), cid, locateSeq) as T
        }
    }

    private companion object {
        const val TAG = "ChatVM"
        const val PAGE_SIZE = 50
        const val MAX_IN_MEMORY = 500
        /** P1-M3 定位回翻:页大小与页数上限(最多回看约 1000 条)。 */
        const val LOCATE_PAGE = 200
        const val MAX_LOCATE_PAGES = 5
        const val SNIPPET_MAX = 40
        const val RECALL_WINDOW_MS = 2 * 60 * 1000L
        /** Max wait for the socket to reconnect before a send gives up (falls
         *  through to the send's own error handling). */
        const val CONNECT_WAIT_MS = 8_000L
    }
}

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
