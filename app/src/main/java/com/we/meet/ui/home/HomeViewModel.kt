package com.we.meet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.LiveKitDto
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.history.HistoryEntry
import com.we.meet.data.history.HistoryStore
import com.we.meet.data.repository.AuthRepository
import com.we.meet.data.repository.RoomRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val phone: String? = null,
    val roomInput: String = "",
    val isJoining: Boolean = false,
    val isCreating: Boolean = false,
    val errorMessage: String? = null,
)

/** Result of a successful room lookup, ready to be passed to RoomScreen. */
data class JoinTarget(
    val livekit: LiveKitDto,
    val displayName: String,
    val slug: String,
)

class HomeViewModel(
    private val tokenStore: TokenStore,
    private val authRepository: AuthRepository,
    private val roomRepository: RoomRepository,
    historyStore: HistoryStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState(phone = tokenStore.phone))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val history: StateFlow<List<HistoryEntry>> = historyStore.entries

    fun onRoomInputChange(value: String) {
        _state.update { it.copy(roomInput = value, errorMessage = null) }
    }

    /** The display name used as participant identity in LiveKit: nickname first, then phone. */
    private val displayUsername: String
        get() = tokenStore.nickname?.takeIf { it.isNotBlank() } ?: tokenStore.phone ?: "Android User"

    fun joinRoom(onResolved: (JoinTarget) -> Unit) {
        val raw = _state.value.roomInput.trim()
        if (raw.isEmpty()) return
        if (_state.value.isJoining) return

        _state.update { it.copy(isJoining = true, errorMessage = null) }
        viewModelScope.launch {
            roomRepository.getRoom(raw, displayUsername).fold(
                onSuccess = { room ->
                    val lk = room.livekit
                    if (lk == null) {
                        _state.update { it.copy(isJoining = false, errorMessage = "Room has no LiveKit info") }
                    } else {
                        _state.update { it.copy(isJoining = false) }
                        onResolved(JoinTarget(livekit = lk, displayName = room.name ?: room.slug ?: room.id, slug = room.slug ?: room.id))
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(isJoining = false, errorMessage = e.localizedMessage ?: "Failed to load room")
                    }
                },
            )
        }
    }

    fun createMeeting(onResolved: (JoinTarget) -> Unit) {
        if (_state.value.isCreating) return

        _state.update { it.copy(isCreating = true, errorMessage = null) }
        val username = displayUsername
        val roomName = "${username}的会议"

        viewModelScope.launch {
            roomRepository.createRoom(username, roomName).fold(
                onSuccess = { room ->
                    val lk = room.livekit
                    if (lk == null) {
                        _state.update { it.copy(isCreating = false, errorMessage = "Room has no LiveKit info") }
                    } else {
                        _state.update { it.copy(isCreating = false) }
                        onResolved(JoinTarget(livekit = lk, displayName = room.name ?: room.slug ?: room.id, slug = room.slug ?: room.id))
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(isCreating = false, errorMessage = e.localizedMessage ?: "Failed to create room")
                    }
                },
            )
        }
    }

    fun signOut() {
        authRepository.signOut()
    }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(
                tokenStore = app.tokenStore,
                authRepository = app.authRepository,
                roomRepository = app.roomRepository,
                historyStore = app.historyStore,
            ) as T
    }
}
