package com.we.meet.feature.im.data

import com.jusi.lightim.Client
import com.jusi.lightim.ConversationSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Live conversation-list state — port of the web client's useConversations.ts.
 *
 * - [refresh] pulls the full list from the SDK.
 * - Inbound `msg` frames bump last_seq / unread / preview in place; a message
 *   for an UNKNOWN cid (we were just pulled into a new conversation) triggers a
 *   full refresh so the new row arrives with its name + members.
 * - `read` frames: only OUR OWN read echo may clear the local unread count —
 *   otherwise a peer reading their copy would zero our badge.
 * - `conv` lifecycle frames always trigger a refresh.
 * - Non-bumping control messages (reaction / recall, P12) leave the list alone.
 */
internal class ConversationRepository(
    private val client: Client,
    private val scope: CoroutineScope,
    private val selfUid: () -> String?,
) {
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    /** Sum of unread across conversations (muted ones excluded from the badge). */
    val totalUnread: StateFlow<Long>
        get() = _totalUnread.asStateFlow()
    private val _totalUnread = MutableStateFlow(0L)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun start() {
        scope.launch {
            client.messages.collect { m ->
                if (m.contentType in NON_BUMPING) return@collect
                val known = _conversations.value.any { it.cid == m.cid }
                if (!known) {
                    refresh()
                    return@collect
                }
                mutate { list ->
                    list.map { c ->
                        if (c.cid == m.cid) c.copy(
                            lastSeq = maxOf(c.lastSeq, m.seq),
                            unreadCount = c.unreadCount + 1,
                            lastMessage = m.body,
                            lastMessageTs = m.createdAt,
                            lastSenderUid = m.senderUid,
                            lastContentType = m.contentType,
                        ) else c
                    }
                }
            }
        }
        scope.launch {
            client.reads.collect { r ->
                if (r.uid != selfUid()) return@collect
                mutate { list ->
                    list.map { c ->
                        if (c.cid == r.cid) c.copy(unreadCount = maxOf(0, c.lastSeq - r.seq))
                        else c
                    }
                }
            }
        }
        scope.launch {
            client.conversationEvents.collect { refresh() }
        }
    }

    suspend fun refresh() {
        runCatching { client.listConversations() }
            .onSuccess { list ->
                _error.value = null
                mutate { list }
            }
            .onFailure { e -> _error.value = e.message ?: e::class.simpleName }
    }

    /** Optimistic local patch + server settings write. */
    fun setPinned(cid: String, pinned: Boolean) = patchSettings(cid) { it.copy(pinned = pinned) }
        .also { scope.launch { runCatching { client.setConversationSettings(cid, pinned = pinned) } } }

    fun setMuted(cid: String, muted: Boolean) = patchSettings(cid) { it.copy(muted = muted) }
        .also { scope.launch { runCatching { client.setConversationSettings(cid, muted = muted) } } }

    /** Delete/leave; on success the row disappears locally right away. */
    suspend fun deleteOrLeave(cid: String, transferTo: String? = null): Result<Unit> =
        runCatching { client.leaveConversation(cid, transferTo) }
            .onSuccess { mutate { list -> list.filterNot { it.cid == cid } } }

    /** Zero a conversation's local unread (after the chat screen marked it read). */
    fun markReadLocally(cid: String, seq: Long) {
        mutate { list ->
            list.map { c ->
                if (c.cid == cid) c.copy(unreadCount = maxOf(0, c.lastSeq - seq)) else c
            }
        }
    }

    fun clear() {
        _conversations.value = emptyList()
        _totalUnread.value = 0
        _error.value = null
    }

    private fun patchSettings(cid: String, patch: (ConversationSummary) -> ConversationSummary) {
        mutate { list -> list.map { if (it.cid == cid) patch(it) else it } }
    }

    /** Apply + re-sort + recompute the badge in one pass. */
    private fun mutate(block: (List<ConversationSummary>) -> List<ConversationSummary>) {
        _conversations.update { current -> sort(block(current)) }
        _totalUnread.value = _conversations.value
            .filterNot { it.muted }
            .sumOf { it.unreadCount }
    }

    private companion object {
        /** P12 control messages that must not bump activity/unread/preview. */
        val NON_BUMPING = setOf("reaction", "recall")

        /** 飞书/微信式: pinned first, then last-activity desc, stable within ties. */
        fun sort(list: List<ConversationSummary>): List<ConversationSummary> =
            list.sortedWith(
                compareByDescending<ConversationSummary> { it.pinned }
                    .thenByDescending { it.lastMessageTs ?: 0L }
            )
    }
}
