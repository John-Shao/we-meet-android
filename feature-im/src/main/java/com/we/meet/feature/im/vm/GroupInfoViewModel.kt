package com.we.meet.feature.im.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
)

data class GroupInfoUiState(
    val cid: String = "",
    val name: String = "",
    val description: String = "",
    val members: List<GroupMemberUi> = emptyList(),
    val isOwner: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
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
        viewModelScope.launch {
            val summary = session.conversations.conversations.value.firstOrNull { it.cid == cid }
            val meta = summary?.meta as? Map<*, *>
            _ui.update {
                it.copy(
                    name = summary?.name?.ifBlank { (meta?.get("name") as? String).orEmpty() }
                        ?: it.name,
                    description = (meta?.get("description") as? String).orEmpty(),
                )
            }
            runCatching { session.client.listMembers(cid) }
                .onSuccess { roster ->
                    rosterUids = roster.map { it.uid }
                    ownerUid = roster.firstOrNull { it.role == "owner" }?.uid
                        ?: summary?.ownerUid
                    session.userDirectory.requestResolve(rosterUids)
                    rebuildRoster()
                }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
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

    // ---- internals ----

    private fun rebuildRoster() {
        val self = session.selfUid.value
        val members = rosterUids.map { uid ->
            val info = session.userDirectory.get(uid)
            GroupMemberUi(
                uid = uid,
                userId = info?.id?.takeIf { it.isNotBlank() },
                displayName = info?.displayName ?: "",
                avatarUrl = info?.avatarUrl?.takeIf { it.isNotBlank() },
                isOwner = uid == ownerUid,
                isSelf = uid == self,
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
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                block()
                _ui.update { it.copy(busy = false) }
            } catch (e: Throwable) {
                Log.w(TAG, "group action failed", e)
                _ui.update { it.copy(busy = false, error = e.message ?: e::class.simpleName) }
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
