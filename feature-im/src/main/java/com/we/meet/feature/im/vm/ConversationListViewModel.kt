package com.we.meet.feature.im.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jusi.lightim.ConnectionState
import com.jusi.lightim.ConversationSummary
import com.we.meet.core.directory.data.ContactPrefs
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.userMessageRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ConvListVM"

/** Everything one conversation row needs; preview text is derived in the UI. */
data class ConversationRowUi(
    val cid: String,
    val isGroup: Boolean,
    val title: String,
    val avatarUrl: String?,
    /** Stable identity for the avatar cache (peer uid for directs). */
    val avatarKey: String,
    /** Group member IM uids — used by the 9-grid group avatar. */
    val memberUids: List<String> = emptyList(),
    /** Pre-resolved tiles for the 9-grid group avatar (groups only). Carried on
     *  the row so a later directory resolve changes it and the avatar recomposes. */
    val memberTiles: List<GroupTile> = emptyList(),
    /** Resolved display name of the last sender (groups prefix the preview). */
    val lastSenderName: String?,
    val lastContentType: String?,
    val lastMessage: String?,
    val lastMessageTs: Long?,
    val unread: Long,
    val pinned: Boolean,
    val muted: Boolean,
    /** True when @all messages are suppressed for this conversation. */
    val muteAtAll: Boolean = false,
    /** True when the caller owns this group — drives the delete-menu wording. */
    val isOwner: Boolean,
    /** An unread message @-mentioned me → red "@" marker. */
    val mentioned: Boolean = false,
    /** 私聊对端是星标联系人 → 名字后一颗 ⭐(群聊恒 false,星标是对人的)。 */
    val starred: Boolean = false,
    /**
     * 私聊对端开了「他的消息特别提醒」→ 名字后一个铃铛(群聊恒 false)。
     *
     * ⚠️ 会话自己开了免打扰([muted])时**恒 false**:那种情况下 jusi 在发 webhook
     * 前就把你剔掉了,穿透根本不会发生,显示「会通知你」等于撒谎。铃铛在那一行的
     * 缺席正好如实表达「你的特别提醒在这个会话上被免打扰盖过了」。
     */
    val specialAlert: Boolean = false,
    val draftText: String? = null,
)

/**
 * Conversation-list screen VM — a thin projection over the shared [ImSession]:
 * merges the live summaries with resolved display identities and exposes the
 * row actions (pin / mute / delete-or-leave).
 */
class ConversationListViewModel internal constructor(
    private val session: ImSession,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = session.connectionState

    /** Localized message resources, not raw exception text — see [userMessageRes]. */
    val error: StateFlow<Int?> = session.conversations.error

    private val _actionError = MutableStateFlow<Int?>(null)
    val actionError: StateFlow<Int?> = _actionError.asStateFlow()

    /**
     * 星标 / 特别提醒两个 id 集合打包成一个流。
     *
     * 为什么不直接塞进下面的 combine:超过 5 个流就只有 vararg 重载可选,lambda 退化
     * 成 `Array<Any?>`,每个元素都得 UNCHECKED_CAST —— 打包一层就能保住具名参数。
     */
    private val contactMarks: Flow<Pair<Set<String>, Set<String>>> =
        combine(ContactPrefs.starredIds, ContactPrefs.specialAlertIds) { starred, alert ->
            starred to alert
        }

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val listExtras = combine(contactMarks, _drafts) { marks, drafts -> marks to drafts }

    init { refreshDrafts() }

    val rows: StateFlow<List<ConversationRowUi>> =
        combine(
            session.conversations.conversations,
            session.userDirectory.version,
            session.selfUid,
            session.mentionedCids,
            // 两个 flag 都是 we-meet user id 的集合(共享单例),而会话只有 IM uid
            // —— 靠 userDirectory 已解析出的 `id` 搭桥,不额外发请求。
            listExtras,
        ) { conversations, _, selfUid, mentioned, extras ->
            val marks = extras.first
            val drafts = extras.second
            // 触发 resolve 但不阻塞首帧:列表立即用字母兜底头像渲染,真实头像随
            // userDirectory.version 下次 bump 补上(微信/飞书式)。之前在这里挂起
            // resolve(阻塞)会让冷启动/弱网时整列表空白直到返回——比短暂字母帧更差。
            val wanted = buildSet<String> {
                conversations.forEach { c ->
                    c.lastSenderUid?.let { add(it) }
                    if (c.type != "group") {
                        addAll(c.members.filterNot { it == selfUid })
                    } else {
                        addAll(c.members.take(9))
                    }
                }
            }
            session.userDirectory.requestResolve(wanted)
            conversations.map {
                it.toRow(selfUid, marks.first, marks.second)
                    .copy(mentioned = it.cid in mentioned, draftText = drafts[it.cid])
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch { session.conversations.refresh() }
        refreshDrafts()
    }

    private fun refreshDrafts() {
        val uid = session.selfUid.value
        val local = uid?.let(session.inputState::drafts).orEmpty()
        _drafts.value = local.mapValues { it.value.text }
    }

    fun retryConnection() = session.retry()

    fun togglePin(row: ConversationRowUi) =
        session.conversations.setPinned(row.cid, !row.pinned)

    fun toggleMute(row: ConversationRowUi) =
        session.conversations.setMuted(row.cid, !row.muted)

    fun toggleMuteAtAll(row: ConversationRowUi) =
        session.conversations.setMuteAtAll(row.cid, !row.muteAtAll)

    /**
     * Direct → hide; group member → leave; group owner → server auto-transfers
     * to the earliest member (or dissolves when last). A leave announcement is
     * posted best-effort first so the group sees "X 退出群聊".
     */
    fun deleteOrLeave(row: ConversationRowUi) {
        viewModelScope.launch {
            if (row.isGroup) session.bridge.announceLeave(row.cid)
            session.conversations.deleteOrLeave(row.cid)
                .onFailure {
                    Log.w(TAG, "deleteOrLeave failed cid=${row.cid}", it)
                    _actionError.value = it.userMessageRes()
                }
        }
    }

    /**
     * 删除会话 = 软隐藏(保留群身份、历史,收到新消息自动重现)。群聊走新 hide 端点;
     * 私聊沿用既有 DELETE 软隐藏路径(与今日行为一致,不依赖新端点)。不发退群公告。
     */
    fun hideConversation(row: ConversationRowUi) {
        viewModelScope.launch {
            val result =
                if (row.isGroup) session.conversations.hide(row.cid)
                else session.conversations.deleteOrLeave(row.cid)
            result.onFailure {
                Log.w(TAG, "hide failed cid=${row.cid}", it)
                _actionError.value = it.userMessageRes()
            }
        }
    }

    fun dismissActionError() {
        _actionError.value = null
    }

    private fun ConversationSummary.toRow(
        selfUid: String?,
        starredUserIds: Set<String>,
        alertUserIds: Set<String>,
    ): ConversationRowUi {
        val isGroup = type == "group"
        val peerUid = if (!isGroup) members.firstOrNull { it != selfUid } else null
        val peer = peerUid?.let { session.userDirectory.get(it) }
        val title = when {
            isGroup -> name.ifBlank { metaName().orEmpty() }.ifBlank { "" }
            else -> peer?.displayName ?: ""
        }
        val sender = lastSenderUid?.let { uid ->
            if (uid == selfUid) null else session.userDirectory.get(uid)?.displayName
        }
        return ConversationRowUi(
            cid = cid,
            isGroup = isGroup,
            title = title,
            avatarUrl = if (!isGroup) peer?.avatarUrl?.takeIf { it.isNotBlank() } else null,
            avatarKey = peerUid ?: cid,
            memberUids = if (isGroup) members else emptyList(),
            memberTiles = if (isGroup) members.take(9).map { uid ->
                val info = session.userDirectory.get(uid)
                GroupTile(
                    uid = uid,
                    name = info?.displayName ?: uid.take(2),
                    avatarUrl = info?.avatarUrl?.takeIf { it.isNotBlank() },
                )
            } else emptyList(),
            lastSenderName = if (isGroup) sender else null,
            lastContentType = lastContentType,
            lastMessage = lastMessage,
            lastMessageTs = lastMessageTs,
            unread = unreadCount,
            pinned = pinned,
            muted = muted,
            muteAtAll = muteAtAll,
            isOwner = isGroup && ownerUid != null && ownerUid == selfUid,
            // 对端还没解析出来时先按无标记渲染,resolve 回来 version bump 会补上。
            starred = !isGroup && peer?.id?.let { it in starredUserIds } == true,
            // `!muted` 见 ConversationRowUi.specialAlert 的注释:静默的会话上这个
            // 标记必须缺席,否则就是承诺一件不会发生的事。
            specialAlert = !isGroup && !muted &&
                peer?.id?.let { it in alertUserIds } == true,
        )
    }

    private fun ConversationSummary.metaName(): String? =
        ((meta as? Map<*, *>)?.get("name") as? String)

    class Factory(private val deps: ImDeps) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConversationListViewModel::class.java))
            return ConversationListViewModel(ImSession.get(deps)) as T
        }
    }
}
