package com.we.meet.feature.im.vm

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.userMessageRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One roster entry with its resolved display identity. */
data class GroupMemberUi(
    val uid: String,
    /** we-meet user uuid — what remove-member / transfer flows need. */
    val userId: String?,
    val displayName: String,
    val avatarUrl: String?,
    val isOwner: Boolean,
    val isSelf: Boolean,
    /**
     * P10:该成员已从组织离职。群成员关系归 jusi、组织关系归 we-meet,两者本就
     * 不同步 —— 人离职了群里不会自动少一个人,名单上标出来群主才知道该清谁。
     */
    val isDeparted: Boolean = false,
)

data class GroupInfoUiState(
    val cid: String = "",
    val name: String = "",
    val description: String = "",
    val members: List<GroupMemberUi> = emptyList(),
    /**
     * 群里装了几个机器人(对标飞书:入口右侧带数字)。
     *
     * null = 还没拿到 / 拉取失败,这时那一行不显示数字 —— 先写 0 再跳成 2,
     * 看着像刚被人加了一个。
     */
    val botCount: Int? = null,
    val isOwner: Boolean = false,
    /** Caller's own per-conversation 群昵称 (P10); blank = use directory name. */
    val myNickname: String = "",
    /** Per-member private toggles (P10) — mirrors the conversation summary flags. */
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val muteAtAll: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    /** Localized message resource, not raw exception text — see [userMessageRes]. */
    @StringRes val error: Int? = null,
)

sealed interface GroupInfoEvent {
    /** Left / dissolved — pop back past the chat screen. */
    data object LeftGroup : GroupInfoEvent
    /** History cleared — the chat screen should reload. */
    data object HistoryCleared : GroupInfoEvent
}

/**
 * Group management VM: roster + owner actions. Server truth via SDK roster +
 * bridge mutations; conv events refresh the roster live.
 */
class GroupInfoViewModel internal constructor(
    private val session: ImSession,
    private val cid: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(GroupInfoUiState(cid = cid))
    val ui: StateFlow<GroupInfoUiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<GroupInfoEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<GroupInfoEvent> = _events.asSharedFlow()

    // ⚠️ Must be declared BEFORE init: the version.collect below fires
    // synchronously (StateFlow + Main.immediate) and calls rebuildRoster(),
    // which reads these fields — declared after init they would still be null.
    private var rosterUids: List<String> = emptyList()
    private var ownerUid: String? = null
    // 每个成员在本会话的群昵称(P10):uid → 非空昵称。昵称优先于目录名显示,
    // 与 Web GroupInfoPanel 的 nameOf 口径一致;随 refresh() 重新拉取而更新。
    private var nicknames: Map<String, String> = emptyMap()
    private var refreshJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            session.client.conversationEvents.collect { ev ->
                if (ev.cid == cid) refresh()
            }
        }
        viewModelScope.launch {
            session.userDirectory.version.collect { rebuildRoster() }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        _ui.update { it.copy(loading = it.members.isEmpty(), error = null) }
        refreshJob = viewModelScope.launch {
            val summary = session.conversations.conversations.value.firstOrNull { it.cid == cid }
            val meta = summary?.meta as? Map<*, *>
            _ui.update {
                it.copy(
                    name = summary?.name?.ifBlank { (meta?.get("name") as? String).orEmpty() }
                        ?: it.name,
                    description = (meta?.get("description") as? String).orEmpty(),
                    pinned = summary?.pinned ?: false,
                    muted = summary?.muted ?: false,
                    muteAtAll = summary?.muteAtAll ?: false,
                )
            }
            runCatching { session.client.listMembers(cid) }
                .onSuccess { roster ->
                    rosterUids = roster.map { it.uid }
                    ownerUid = roster.firstOrNull { it.role == "owner" }?.uid
                        ?: summary?.ownerUid
                    // 收集所有成员的群昵称(含他人),供成员列表覆盖目录名显示。
                    nicknames = roster
                        .mapNotNull { m ->
                            m.nickname?.takeIf { it.isNotBlank() }?.let { m.uid to it }
                        }
                        .toMap()
                    val myNick = roster
                        .firstOrNull { it.uid == session.selfUid.value }
                        ?.nickname.orEmpty()
                    _ui.update { it.copy(myNickname = myNick) }
                    session.userDirectory.requestResolve(rosterUids)
                    rebuildRoster()
                    _ui.update { it.copy(loading = false, error = null) }
                }
                .onFailure { e ->
                    Log.w(TAG, "group info refresh failed", e)
                    _ui.update { it.copy(loading = false, error = e.userMessageRes()) }
                }
            // 机器人计数。**失败不报错、不清空**:这一行没有数字照样能点进去,
            // 为一个角标把整屏推进错误态不值当。装/删机器人会让 jusi 发
            // member_added/removed,顺着上面那条 collect 再跑一遍,数字自己会更。
            runCatching { session.bridge.listBots(cid) }
                .onSuccess { bots -> _ui.update { it.copy(botCount = bots.size) } }
                .onFailure { e -> Log.w(TAG, "bot count failed", e) }
        }
    }

    fun addMembers(userIds: List<String>) = mutate {
        session.bridge.addMembers(cid, userIds)
        refresh()
    }

    fun removeMember(member: GroupMemberUi) = mutate {
        val userId = member.userId
            ?: throw IllegalStateException("member identity not resolved yet")
        session.bridge.removeMember(cid, userId)
        refresh()
    }

    fun rename(newName: String) = mutate {
        session.bridge.updateGroupMeta(
            cid = cid,
            name = newName,
            description = _ui.value.description,
            kind = "rename",
        )
        _ui.update { it.copy(name = newName) }
        session.conversations.refresh()
    }

    /** 我的群昵称(P10):每成员私有,本会话内覆盖显示名。任何成员可改自己的。 */
    fun setMyNickname(nick: String) = mutate {
        session.client.setConversationSettings(cid, nickname = nick)
        _ui.update { it.copy(myNickname = nick) }
        session.conversations.refresh()
        refresh()
    }

    /** 群公告(description):owner-only, kind="description" 触发对应系统消息。 */
    fun setDescription(newDesc: String) = mutate {
        session.bridge.updateGroupMeta(
            cid = cid,
            name = _ui.value.name,
            description = newDesc,
            kind = "description",
        )
        _ui.update { it.copy(description = newDesc) }
        session.conversations.refresh()
    }

    fun transferOwner(member: GroupMemberUi) = mutate {
        session.client.transferOwner(cid, member.uid)
        refresh()
        session.conversations.refresh()
    }

    fun clearHistory() = mutate {
        session.client.clearHistory(cid)
        session.conversations.refresh()
        _events.tryEmit(GroupInfoEvent.HistoryCleared)
    }

    /** Announce (best-effort), then leave. Owner leave auto-transfers/dissolves. */
    fun leaveGroup() = mutate {
        session.bridge.announceLeave(cid)
        session.client.leaveConversation(cid)
        session.conversations.refresh()
        _events.tryEmit(GroupInfoEvent.LeftGroup)
    }

    /** Toggle per-conversation pin (P10). */
    fun togglePin() = mutate {
        val previous = _ui.value.pinned
        _ui.update { it.copy(pinned = !previous) }
        session.conversations.setPinned(cid, !previous).onFailure {
            _ui.update { state -> state.copy(pinned = previous) }
        }.getOrThrow()
    }

    /** Toggle per-conversation mute (P10). */
    fun toggleMute() = mutate {
        val previous = _ui.value.muted
        _ui.update { it.copy(muted = !previous) }
        session.conversations.setMuted(cid, !previous).onFailure {
            _ui.update { state -> state.copy(muted = previous) }
        }.getOrThrow()
    }

    /** Toggle @all notification suppression (P10). */
    fun toggleMuteAtAll() = mutate {
        val previous = _ui.value.muteAtAll
        _ui.update { it.copy(muteAtAll = !previous) }
        session.conversations.setMuteAtAll(cid, !previous).onFailure {
            _ui.update { state -> state.copy(muteAtAll = previous) }
        }.getOrThrow()
    }

    // ---- internals ----

    private fun rebuildRoster() {
        val self = session.selfUid.value
        val members = rosterUids.map { uid ->
            val info = session.userDirectory.get(uid)
            GroupMemberUi(
                uid = uid,
                userId = info?.id?.takeIf { it.isNotBlank() },
                // 群昵称(P10)优先覆盖目录名,与 Web nameOf 口径一致。
                displayName = nicknames[uid] ?: info?.displayName ?: "",
                avatarUrl = info?.avatarUrl?.takeIf { it.isNotBlank() },
                isOwner = uid == ownerUid,
                isSelf = uid == self,
                isDeparted = info?.left == true,
            )
        }
        _ui.update {
            it.copy(
                members = members,
                isOwner = self != null && self == ownerUid,
            )
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _ui.update { it.copy(busy = false) }
            } catch (e: Throwable) {
                Log.w(TAG, "group action failed", e)
                _ui.update { it.copy(busy = false, error = e.userMessageRes()) }
            }
        }
    }

    class Factory(private val deps: ImDeps, private val cid: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GroupInfoViewModel::class.java))
            return GroupInfoViewModel(ImSession.get(deps), cid) as T
        }
    }

    private companion object {
        const val TAG = "GroupInfoVM"
    }
}
