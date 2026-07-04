package com.we.meet.feature.im.vm

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jusi.lightim.ConnectionState
import com.jusi.lightim.ConvMember
import com.jusi.lightim.Message
import com.jusi.lightim.MsgOutPayload
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.data.ChatUploadException
import com.we.meet.feature.im.data.ImUserInfo
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.feature.im.model.MessageContentParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val selfUid: String? = null,
    val error: String? = null,
    /** Stable upload-error code the UI maps to an i18n string; null = none. */
    val uploadError: ChatUploadException.Code? = null,
    /** Bumps after each acked text send — the composer clears on this. */
    val sentTick: Int = 0,
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

    /** A conversation the user can forward into (excludes the current one). */
    data class ForwardTarget(val cid: String, val title: String)

    /** Forward destinations: every other conversation, titled for display. */
    fun forwardTargets(): List<ForwardTarget> {
        val self = _ui.value.selfUid
        return session.conversations.conversations.value
            .filter { it.cid != cid }
            .map { c ->
                val title = if (c.type == "group") {
                    c.name.ifBlank { ((c.meta as? Map<*, *>)?.get("name") as? String).orEmpty() }
                } else {
                    c.members.firstOrNull { it != self }
                        ?.let { session.userDirectory.get(it)?.displayName }.orEmpty()
                }
                ForwardTarget(c.cid, title)
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

    /** @-mention candidates for the input dropdown: 所有人 + other members' names. */
    fun mentionCandidates(): List<String> {
        if (!_ui.value.isGroup) return emptyList()
        val self = _ui.value.selfUid
        val names = _ui.value.memberUids
            .filter { it != self }
            .mapNotNull { session.userDirectory.get(it)?.displayName?.takeIf { n -> n.isNotBlank() } }
        return listOf(session.everyoneLabel()) + names
    }

    /** Names highlightable as @mentions inside bubbles: 所有人 + ALL members (incl self). */
    fun mentionHighlightNames(): List<String> {
        if (!_ui.value.isGroup) return emptyList()
        val names = _ui.value.memberUids
            .mapNotNull { session.userDirectory.get(it)?.displayName?.takeIf { n -> n.isNotBlank() } }
        return listOf(session.everyoneLabel()) + names
    }

    /** Subset that means "you" (self name + 所有人) → stronger highlight. */
    fun selfMentionNames(): List<String> {
        if (!_ui.value.isGroup) return emptyList()
        val self = _ui.value.selfUid
            ?.let { session.userDirectory.get(it)?.displayName?.takeIf { n -> n.isNotBlank() } }
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
                    mergeRaw(res.messages.asReversed())
                    _ui.update { s ->
                        deriveRows(s.copy(hasMore = res.hasMore, loadingOlder = false))
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loadingOlder = false, error = e.message) }
                }
        }
    }

    // ---- sends ----

    fun sendText(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                session.client.sendText(cid, trimmed)
                _ui.update { it.copy(error = null, sentTick = it.sentTick + 1) }
            } catch (e: Throwable) {
                Log.w(TAG, "sendText failed", e)
                _ui.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
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
                session.client.sendText(cid, body, contentType = "quote")
                _ui.update { it.copy(error = null, sentTick = it.sentTick + 1) }
            } catch (e: Throwable) {
                Log.w(TAG, "sendQuote failed", e)
                _ui.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    /** Recall [message] (tombstone `{target_mid}`). Only own messages, ≤2 min old. */
    fun recall(message: Message) {
        viewModelScope.launch {
            try {
                session.client.sendText(
                    cid,
                    JSONObject().put("target_mid", message.mid).toString(),
                    contentType = "recall",
                )
            } catch (e: Throwable) {
                Log.w(TAG, "recall failed", e)
                _ui.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    /** Whether the caller has an active reaction [emoji] on [mid] (for toggle). */
    fun hasMyReaction(mid: Long, emoji: String): Boolean {
        val self = _ui.value.selfUid ?: return false
        return _ui.value.reactions[mid]?.get(emoji)?.contains(self) == true
    }

    /** Toggle [emoji] on [message]: sends `{target_mid,emoji,op}`, op derived from current state. */
    fun toggleReaction(message: Message, emoji: String) {
        val op = if (hasMyReaction(message.mid, emoji)) "remove" else "add"
        viewModelScope.launch {
            try {
                session.client.sendText(
                    cid,
                    JSONObject()
                        .put("target_mid", message.mid)
                        .put("emoji", emoji)
                        .put("op", op)
                        .toString(),
                    contentType = "reaction",
                )
            } catch (e: Throwable) {
                Log.w(TAG, "reaction failed", e)
                _ui.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
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

    /** Resolve a chat-media object key to a presigned URL (suspend, cached). */
    suspend fun resolveMediaUrl(objectKey: String): String? =
        session.mediaResolver.resolve(objectKey)

    // ---- internals ----

    private fun sendMedia(kind: String, block: suspend () -> Unit) {
        val localId = nextLocalId++
        _ui.update { it.copy(pending = it.pending + PendingSend(localId, kind)) }
        viewModelScope.launch {
            try {
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
                        error = e.message ?: e::class.simpleName,
                    )
                }
            }
        }
    }

    private fun loadNewest() {
        viewModelScope.launch {
            runCatching { session.client.loadHistory(cid, limit = PAGE_SIZE) }
                .onSuccess { res ->
                    mergeRaw(res.messages.asReversed())
                    _ui.update { s -> deriveRows(s.copy(hasMore = res.hasMore, error = null)) }
                    if (visible) {
                        raw.lastOrNull()?.let { markRead(it.seq) }
                    }
                    requestNameResolution()
                }
                .onFailure { e ->
                    _ui.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
        }
    }

    private fun appendMessages(list: List<Message>) {
        mergeRaw(list)
        _ui.update { s -> deriveRows(s) }
        requestNameResolution()
    }

    private fun mergeRaw(incoming: List<Message>) {
        raw = (raw + incoming)
            .distinctBy { it.mid }
            .sortedBy { it.seq }
            .takeLast(MAX_IN_MEMORY)
    }

    /**
     * Replay [raw] into renderable state: recall tombstones and per-message
     * reaction aggregates (seq order, add/remove), control rows filtered out.
     */
    private fun deriveRows(s: ChatUiState): ChatUiState {
        val recalled = mutableSetOf<Long>()
        val reactions = mutableMapOf<Long, LinkedHashMap<String, MutableList<String>>>()
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
            messages = raw.filterNot { MessageContentParser.isControlType(it.contentType) },
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
                    _ui.update { it.copy(memberUids = roster.map { m -> m.uid }) }
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

    class Factory(private val deps: ImDeps, private val cid: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(ImSession.get(deps), cid) as T
        }
    }

    private companion object {
        const val TAG = "ChatVM"
        const val PAGE_SIZE = 50
        const val MAX_IN_MEMORY = 500
        const val SNIPPET_MAX = 40
        const val RECALL_WINDOW_MS = 2 * 60 * 1000L
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
