package com.we.meet.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.history.HistoryEntry
import com.we.meet.data.history.HistoryStore
import com.we.meet.data.repository.RoomRepository
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
    private val historyStore: HistoryStore,
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
     *
     * Future scheduled rooms the user hasn't joined yet are excluded
     * here — they live in [scheduledMeetings] instead.
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

    /**
     * Future-dated rooms (scheduled_at in the future) the user is a
     * member of and hasn't joined yet. Sorted by scheduled time ascending
     * — the next meeting on top. Closed rooms are excluded.
     *
     * Rooms the user has already joined locally stay on the history list
     * even if their `scheduled_at` is still future (the meeting can run
     * past the scheduled start), so they aren't surfaced twice.
     */
    val scheduledMeetings: StateFlow<List<RoomDto>> = combine(
        localHistory,
        _remoteRooms,
    ) { local, remote -> selectScheduled(local, remote) }
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
                _remoteRooms.value = rooms.filterNot { it.isAiSession() }
            }
        }
    }

    /**
     * AI 打电话 sessions are created via the same `POST /rooms/` endpoint
     * as real meetings (feature-assistant convention: name starts with
     * `__JUSI_AI_SESSION__-`), so they come back in `/rooms/?mine=true`.
     * They are NOT meetings the user wants to see in 「预约会议」 /
     * 「历史记录」, hide them at the data-source seam.
     *
     * Single source of truth is the prefix written by AiCallViewModel —
     * keep the two in sync.
     */
    private fun RoomDto.isAiSession(): Boolean =
        name?.startsWith(AI_SESSION_NAME_PREFIX) == true

    /**
     * Remove a meeting from the user's view. When the user is the room
     * owner (backend `is_owner(user)`), the room is hard-deleted on the
     * server so it disappears from every device. Non-owners get HTTP
     * 403 — we still drop the local history entry so it vanishes from
     * THIS device, mirroring the Web "hide this room" UX. A remote
     * refresh follows so the scheduled/history zones reflect the
     * latest state.
     *
     * Pass [identifier] as whichever the row carries — UUID or slug.
     * Both forms hit the same DRF endpoint and HistoryStore.remove()
     * matches against either field.
     */
    fun deleteMeeting(identifier: String) {
        if (identifier.isBlank()) return
        viewModelScope.launch {
            roomRepository.deleteRoom(identifier)
            // Local removal happens regardless of the API result — for
            // non-owners that's the only meaningful effect; for owners
            // it keeps the UI in sync before the refresh lands.
            historyStore.remove(identifier)
            refreshRemoteRooms()
        }
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
        val todayStart = startOfTodayMs()
        val merged = remote.mapNotNull { dto ->
            val slug = dto.slug ?: return@mapNotNull null
            val existing = localBySlug[slug]
            // Today's and future scheduled rooms surface on
            // `scheduledMeetings`, not here — unless the user has
            // actually joined from this device, in which case keep
            // history's record of that.
            if (existing == null && isScheduledOnOrAfterToday(dto.scheduled_at, todayStart)) {
                return@mapNotNull null
            }
            val createdAtMs = parseIsoMillis(dto.created_at)
            // Track server's "closed_at" so Home can route stale rooms
            // to the archive view and live rooms back into the meeting.
            // 0L (parse failure / empty string) collapses to null.
            val closedAtMs = parseIsoMillisOrNull(dto.closed_at)
            if (existing != null) {
                existing.copy(
                    name = dto.name?.takeIf { it.isNotBlank() } ?: existing.name,
                    host = dto.owner ?: existing.host,
                    createdAtMs = existing.createdAtMs.takeIf { it > 0 } ?: createdAtMs,
                    closedAtMs = closedAtMs ?: existing.closedAtMs,
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
                    closedAtMs = closedAtMs,
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

    /**
     * Pick rooms whose `scheduled_at` is today or in the future. Today's
     * already-passed slots stay (e.g. 9am meeting viewed at 10am) — they
     * disappear at midnight rolling into the next day. Closed rooms are
     * excluded, and rooms the user has already joined from this device
     * stay in history instead.
     */
    private fun selectScheduled(
        local: List<HistoryEntry>,
        remote: List<RoomDto>,
    ): List<RoomDto> {
        val joinedSlugs = local.filter { it.firstJoinedAtMs > 0 }
            .mapNotNull { it.slug.takeIf { s -> s.isNotBlank() } }
            .toHashSet()
        val todayStart = startOfTodayMs()
        return remote
            .asSequence()
            .filter { it.closed_at.isNullOrBlank() }
            .filter { isScheduledOnOrAfterToday(it.scheduled_at, todayStart) }
            .filter { (it.slug ?: "") !in joinedSlugs }
            .sortedBy { parseIsoMillis(it.scheduled_at) }
            .toList()
    }

    private fun startOfTodayMs(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun isScheduledOnOrAfterToday(iso: String?, todayStartMs: Long): Boolean {
        if (iso.isNullOrBlank()) return false
        val ms = parseIsoMillisOrNull(iso) ?: return false
        return ms >= todayStartMs
    }

    private fun parseIsoMillisOrNull(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val normalized = iso
            .replace(Regex("\\.\\d+"), "")
            .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { fmt.parse(normalized)?.time }.getOrNull()
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

    private companion object {
        // Mirror of the prefix written by feature-assistant's AiCallViewModel
        // when it creates an AI session room. Keep the two in sync.
        const val AI_SESSION_NAME_PREFIX = "__JUSI_AI_SESSION__-"
    }
}
