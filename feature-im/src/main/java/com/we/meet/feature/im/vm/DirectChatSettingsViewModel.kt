package com.we.meet.feature.im.vm

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

data class DirectSettingsUiState(
    val cid: String = "",
    /** Peer display name (resolved from directory). */
    val peerName: String = "",
    /** Peer avatar URL (presigned); null / blank → tinted initial. */
    val peerAvatarUrl: String? = null,
    /** Peer's we-meet user id — needed to seed the group picker. */
    val peerUserId: String? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface DirectSettingsEvent {
    /** History cleared — the chat screen should reload. */
    data object HistoryCleared : DirectSettingsEvent
}

/**
 * Direct (1-on-1) chat settings VM — mirrors Web DirectSettingsPanel.
 *
 * Surfaces: peer identity, pin / mute toggles, clear history, and a
 * "create group" seed so the caller can open a group picker with this peer.
 */
class DirectChatSettingsViewModel internal constructor(
    private val session: ImSession,
    private val cid: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(DirectSettingsUiState(cid = cid))
    val ui: StateFlow<DirectSettingsUiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<DirectSettingsEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<DirectSettingsEvent> = _events.asSharedFlow()

    private var peerUid: String? = null

    init {
        refresh()
        viewModelScope.launch {
            session.conversations.conversations.collect { refresh() }
        }
        viewModelScope.launch {
            session.userDirectory.version.collect { rebuildIdentity() }
        }
    }

    fun refresh() {
        val summary = session.conversations.conversations.value.firstOrNull { it.cid == cid }
        if (summary == null) {
            _ui.update { it.copy(error = "Conversation not found") }
            return
        }
        val self = session.selfUid.value
        peerUid = summary.members.firstOrNull { it != self }
        _ui.update {
            it.copy(
                pinned = summary.pinned,
                muted = summary.muted,
            )
        }
        if (peerUid != null) {
            session.userDirectory.requestResolve(setOf(peerUid!!))
            rebuildIdentity()
        }
    }

    private fun rebuildIdentity() {
        val info = peerUid?.let { session.userDirectory.get(it) }
        _ui.update {
            it.copy(
                peerName = info?.displayName ?: "",
                peerAvatarUrl = info?.avatarUrl?.takeIf { url -> url.isNotBlank() },
                peerUserId = info?.id?.takeIf { id -> id.isNotBlank() },
            )
        }
    }

    fun togglePin() {
        val next = !_ui.value.pinned
        _ui.update { it.copy(pinned = next) }
        session.conversations.setPinned(cid, next)
    }

    fun toggleMute() {
        val next = !_ui.value.muted
        _ui.update { it.copy(muted = next) }
        session.conversations.setMuted(cid, next)
    }

    fun clearHistory() = mutate {
        session.client.clearHistory(cid)
        session.conversations.refresh()
        _events.tryEmit(DirectSettingsEvent.HistoryCleared)
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                block()
                _ui.update { it.copy(busy = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }

    class Factory(
        private val deps: ImDeps,
        private val cid: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DirectChatSettingsViewModel(ImSession.get(deps), cid) as T
    }
}
