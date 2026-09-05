package com.we.meet.ui.waiting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.RequestEntryResponse
import com.we.meet.data.repository.RoomRepository
import com.we.meet.util.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the visitor-side lobby flow. The visitor stays here while the
 * host hasn't acted; on accept we hand off the freshly-minted LiveKit
 * token to the room route, on deny we land on a terminal "rejected"
 * screen so the user knows to back out.
 */
data class WaitingRoomUiState(
    val phase: Phase = Phase.Waiting,
    /** LiveKit token + url + room when the host admitted us. */
    val livekitUrl: String? = null,
    val livekitToken: String? = null,
    val livekitRoomId: String? = null,
    /** Free-form description shown on the error screen. */
    val errorMessage: String? = null,
) {
    enum class Phase { Waiting, Accepted, Denied, Error }
}

/** Backend status values returned by request-entry. */
private object LobbyStatus {
    const val WAITING = "waiting"
    const val ACCEPTED = "accepted"
    const val DENIED = "denied"
}

class WaitingRoomViewModel(
    application: Application,
    private val idOrSlug: String,
    private val username: String,
    private val roomRepository: RoomRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(WaitingRoomUiState())
    val state: StateFlow<WaitingRoomUiState> = _state.asStateFlow()
    private var pollingJob: Job? = null

    init {
        startPolling()
    }

    fun retry() {
        if (pollingJob?.isActive == true) return
        _state.value = WaitingRoomUiState()
        startPolling()
    }

    /**
     * Hammer request-entry every 4 s. Backend uses an HTTP cookie to
     * keep the same participant id across polls (provided by the
     * application's InMemoryCookieJar), and the server-side timeout
     * refreshes on every call so the visitor doesn't get GC'd from the
     * lobby while waiting. Polling stops once we reach a terminal
     * phase (Accepted, Denied, Error).
     *
     * Throttle on the backend caps the call rate
     * (RequestEntryAuthenticatedUserRateThrottle / Anon) — 4 s is
     * comfortably under that limit while still feeling responsive.
     */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (true) {
                if (_state.value.phase != WaitingRoomUiState.Phase.Waiting) break
                roomRepository.requestEntry(idOrSlug, username)
                    .onSuccess { response -> consumeResponse(response) }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                phase = WaitingRoomUiState.Phase.Error,
                                errorMessage = error.toUserMessage(getApplication()),
                            )
                        }
                    }
                if (_state.value.phase != WaitingRoomUiState.Phase.Waiting) break
                delay(4_000)
            }
        }
    }

    private fun consumeResponse(response: RequestEntryResponse) {
        when (response.status) {
            LobbyStatus.ACCEPTED -> {
                val lk = response.livekit
                if (lk == null) {
                    // Accepted with no token shouldn't happen, but if it does
                    // we'd rather error visibly than hang on Waiting forever.
                    _state.update {
                        it.copy(
                            phase = WaitingRoomUiState.Phase.Error,
                            errorMessage = getApplication<Application>().getString(R.string.waiting_no_token),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            phase = WaitingRoomUiState.Phase.Accepted,
                            livekitUrl = lk.url,
                            livekitToken = lk.token,
                            livekitRoomId = lk.room,
                        )
                    }
                }
            }
            LobbyStatus.DENIED -> {
                _state.update { it.copy(phase = WaitingRoomUiState.Phase.Denied) }
            }
            LobbyStatus.WAITING -> {
                // Stay in Waiting; nothing to update.
            }
            else -> {
                // Unknown status — treat as transient, keep polling.
            }
        }
    }

    class Factory(
        private val application: Application,
        private val idOrSlug: String,
        private val username: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as WeMeetApp
            return WaitingRoomViewModel(
                application = application,
                idOrSlug = idOrSlug,
                username = username,
                roomRepository = app.roomRepository,
            ) as T
        }
    }
}
