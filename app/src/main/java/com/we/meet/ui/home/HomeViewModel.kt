package com.we.meet.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.history.HistoryEntry
import com.we.meet.data.history.HistoryStore
import com.we.meet.data.repository.RoomRepository
import com.we.meet.ui.preview.RoomTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HomeViewModel(
    application: Application,
    private val tokenStore: TokenStore,
    private val roomRepository: RoomRepository,
    historyStore: HistoryStore,
) : AndroidViewModel(application) {

    val history: StateFlow<List<HistoryEntry>> = historyStore.entries

    /**
     * The room just created via "create later" — non-null while the
     * invite sheet is open. Cleared when the user dismisses or enters
     * the meeting.
     */
    private val _laterCreated = MutableStateFlow<RoomTarget?>(null)
    val laterCreated: StateFlow<RoomTarget?> = _laterCreated.asStateFlow()

    private val _laterCreating = MutableStateFlow(false)
    val laterCreating: StateFlow<Boolean> = _laterCreating.asStateFlow()

    private val displayUsername: String
        get() = tokenStore.nickname?.takeIf { it.isNotBlank() }
            ?: tokenStore.phone
            ?: "Android User"

    val defaultMeetingName: String
        get() = "${displayUsername}的会议"

    /**
     * Create a room without joining it. Surfaces the invite info via
     * [laterCreated] so HomeScreen can pop the invite sheet for the
     * host to copy/share before deciding when (if ever) to enter.
     *
     * [scheduledAtIso] optional — non-null when the host picked a
     * specific start time in the dialog. Persisted server-side as
     * Room.scheduled_at and shown on the invite sheet so the host's
     * paste includes "scheduled for X" alongside the room link.
     */
    fun createLaterMeeting(meetingName: String, scheduledAtIso: String? = null) {
        if (_laterCreating.value) return
        _laterCreating.update { true }
        viewModelScope.launch {
            roomRepository.createRoom(displayUsername, meetingName, scheduledAtIso)
                .onSuccess { room ->
                    val lk = room.livekit
                    if (lk != null) {
                        _laterCreated.value = RoomTarget(
                            roomId = room.id,
                            livekitUrl = lk.url,
                            livekitToken = lk.token,
                            displayName = room.name ?: room.slug ?: room.id,
                            slug = room.slug ?: room.id,
                            isAdmin = room.is_administrable == true,
                            host = displayUsername,
                            createdAtMs = parseIsoMillis(room.created_at),
                            scheduledAtIso = room.scheduled_at,
                        )
                    }
                }
            _laterCreating.update { false }
        }
    }

    fun dismissLaterCreated() {
        _laterCreated.value = null
    }

    private fun parseIsoMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        val normalized = iso
            .replace(Regex("\\.\\d+"), "")
            .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { fmt.parse(normalized)?.time }.getOrNull()
            ?: System.currentTimeMillis()
    }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(
                application = app,
                tokenStore = app.tokenStore,
                roomRepository = app.roomRepository,
                historyStore = app.historyStore,
            ) as T
    }
}
