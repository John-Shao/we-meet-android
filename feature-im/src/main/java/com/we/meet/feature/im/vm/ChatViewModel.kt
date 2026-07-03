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
        if (isVisible) {
            raw.lastOrNull()?.let { markRead(it.seq) }
        }
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
