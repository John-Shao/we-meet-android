package com.we.meet.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.repository.RoomRepository
import com.we.meet.util.ErrorScope
import com.we.meet.util.toUserMessage
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreviewUiState(
    val isLoading: Boolean = false,
    /**
     * Persistent error banner text. Null when no error is showing. The
     * banner stays visible until the user edits the meeting input or
     * explicitly dismisses it — errors like "会议号无效" need enough time
     * for the user to read and correct the input.
     */
    val errorMessage: String? = null,
)

data class RoomTarget(
    val roomId: String,
    val livekitUrl: String,
    val livekitToken: String,
    val displayName: String,
    val slug: String,
    val isAdmin: Boolean,
    /** 发起人. From the backend room API (owner field). */
    val host: String?,
    /** 创建时间 in epoch millis. Parsed from RoomDto.created_at, or now() as fallback. */
    val createdAtMs: Long,
)

class PreviewViewModel(
    application: Application,
    private val tokenStore: TokenStore,
    private val roomRepository: RoomRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    /** The display name used as participant identity in LiveKit: nickname first, then phone. */
    private val displayUsername: String
        get() = tokenStore.nickname?.takeIf { it.isNotBlank() } ?: tokenStore.phone ?: "Android User"

    val defaultMeetingName: String
        get() = "${displayUsername}的会议"

    fun createMeeting(meetingName: String, onSuccess: (RoomTarget) -> Unit) {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        val username = displayUsername
        viewModelScope.launch {
            roomRepository.createRoom(username, meetingName).fold(
                onSuccess = { room ->
                    val lk = room.livekit
                    if (lk == null) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = getApplication<Application>().getString(R.string.error_unknown),
                            )
                        }
                    } else {
                        _state.update { it.copy(isLoading = false) }
                        onSuccess(RoomTarget(
                            roomId = room.id,
                            livekitUrl = lk.url,
                            livekitToken = lk.token,
                            displayName = room.name ?: room.slug ?: room.id,
                            slug = room.slug ?: room.id,
                            isAdmin = room.is_administrable == true,
                            // We're the creator → we're the host.
                            host = username,
                            createdAtMs = parseIsoMillis(room.created_at),
                        ))
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.toUserMessage(getApplication(), ErrorScope.GENERIC),
                        )
                    }
                },
            )
        }
    }

    fun joinRoom(slug: String, onSuccess: (RoomTarget) -> Unit) {
        val trimmed = slug.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.isLoading) return

        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            roomRepository.getRoom(trimmed, displayUsername).fold(
                onSuccess = { room ->
                    val lk = room.livekit
                    if (lk == null) {
                        // Backend returns 200 with no `livekit` block when the
                        // room has been ended — surface 「会议已结束」 rather
                        // than the generic fallback so the user knows why they
                        // can't join.
                        val msgRes = if (room.closed_at != null) {
                            R.string.error_meeting_ended
                        } else {
                            R.string.error_unknown
                        }
                        _state.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = getApplication<Application>().getString(msgRes),
                            )
                        }
                    } else {
                        _state.update { it.copy(isLoading = false) }
                        onSuccess(RoomTarget(
                            roomId = room.id,
                            livekitUrl = lk.url,
                            livekitToken = lk.token,
                            displayName = room.name ?: room.slug ?: room.id,
                            slug = room.slug ?: room.id,
                            isAdmin = room.is_administrable == true,
                            host = room.owner,
                            createdAtMs = parseIsoMillis(room.created_at),
                        ))
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.toUserMessage(getApplication(), ErrorScope.ROOM_FETCH),
                        )
                    }
                },
            )
        }
    }

    private fun parseIsoMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        // DRF emits RFC 3339 like "2026-04-22T14:58:12.345678Z". SimpleDateFormat
        // doesn't handle fractional seconds or "Z" uniformly across Android
        // versions, so normalize first: strip fractional seconds and replace
        // trailing "Z" with "+0000".
        val normalized = iso
            .replace(Regex("\\.\\d+"), "")
            .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { fmt.parse(normalized)?.time }.getOrNull()
            ?: System.currentTimeMillis()
    }

    fun dismissError() {
        if (_state.value.errorMessage != null) {
            _state.update { it.copy(errorMessage = null) }
        }
    }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PreviewViewModel(
                application = app,
                tokenStore = app.tokenStore,
                roomRepository = app.roomRepository,
            ) as T
    }
}
