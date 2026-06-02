package com.we.meet.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.history.HistoryEntry
import com.we.meet.data.history.HistoryStore
import com.we.meet.data.repository.RoomRepository
import com.we.meet.ui.preview.RoomTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    private val localHistory: StateFlow<List<HistoryEntry>> = historyStore.entries

    /** Server-side rooms the user is a member of. Refreshed on demand. */
    private val _remoteRooms = MutableStateFlow<List<RoomDto>>(emptyList())

    /**
     * Local + remote rooms merged by slug. Local wins for participant
     * lists and join/leave timestamps (per-device state the server can't
     * know); remote wins for name/host/created_at (most authoritative
     * source). Rooms that only exist on the server still surface here so
     * users see meetings created on the Web app.
     */
    val history: StateFlow<List<HistoryEntry>> = combine(
        localHistory,
        _remoteRooms,
    ) { local, remote -> mergeHistory(local, remote) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    init {
        refreshRemoteRooms()
    }

    /**
     * Pull the latest server-side list. Called once on init and again
     * whenever Home becomes visible (HomeScreen drives this via a
     * LaunchedEffect tied to the lifecycle). Failures are silent — the
     * local store remains the source of truth for offline-able state.
     */
    fun refreshRemoteRooms() {
        viewModelScope.launch {
            roomRepository.fetchMyRooms().onSuccess { rooms ->
                _remoteRooms.value = rooms
            }
        }
    }

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

    /**
     * Merge local history with the server-side list, keyed by slug. For
     * rooms present in both: take the local row's per-device fields
     * (firstJoinedAtMs, lastLeftAtMs, participants), overlay the
     * server's latest name/host/createdAt so a host rename on Web shows
     * up here. Server-only rooms are synthesized with
     * `firstJoinedAtMs = 0` and an empty participant list — they still
     * sort correctly by their `created_at`.
     */
    private fun mergeHistory(
        local: List<HistoryEntry>,
        remote: List<RoomDto>,
    ): List<HistoryEntry> {
        val localBySlug = local.associateBy { it.slug }
        val remoteSlugs = remote.mapNotNull { it.slug }.toHashSet()

        val merged = remote.mapNotNull { dto ->
            val slug = dto.slug ?: return@mapNotNull null
            val existing = localBySlug[slug]
            val createdAtMs = parseIsoMillis(dto.created_at)
            if (existing != null) {
                existing.copy(
                    name = dto.name?.takeIf { it.isNotBlank() } ?: existing.name,
                    host = dto.owner ?: existing.host,
                    createdAtMs = existing.createdAtMs.takeIf { it > 0 } ?: createdAtMs,
                )
            } else {
                HistoryEntry(
                    roomId = dto.id,
                    name = dto.name?.takeIf { it.isNotBlank() } ?: slug,
                    slug = slug,
                    host = dto.owner,
                    createdAtMs = createdAtMs,
                    firstJoinedAtMs = 0L,
                    lastLeftAtMs = null,
                    participants = emptyList(),
                )
            }
        }

        // Local-only entries: rooms we tracked from a join but the
        // server no longer lists (closed, deleted, or the user lost
        // membership). Keep them so the user can still see they were
        // there — clearing them is S2.3's job, not list-fetch.
        val localOnly = local.filter { it.slug !in remoteSlugs }

        return (merged + localOnly).sortedByDescending {
            maxOf(it.firstJoinedAtMs, it.lastLeftAtMs ?: 0L, it.createdAtMs)
        }
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
