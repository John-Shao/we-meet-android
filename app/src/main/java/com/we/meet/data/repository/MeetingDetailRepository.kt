package com.we.meet.data.repository

import com.we.meet.data.api.RoomApi
import com.we.meet.data.api.dto.ActionItemDto
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.api.dto.SummaryDto
import com.we.meet.data.api.dto.TranscriptDto
import retrofit2.HttpException

/**
 * Fetches the meeting-detail bundle (room metadata + summary + action items
 * + transcripts). One repository keeps each tab's loader independent so the
 * UI can show partial state — a room with no summary still surfaces the
 * info + transcript tabs.
 *
 * Summary endpoint returns 404 when no summary has been generated yet —
 * the repository maps that to `Result.success(null)` so callers can render
 * the empty state without inspecting exception types. All other failures
 * surface via `Result.failure`.
 */
class MeetingDetailRepository(
    private val roomApi: RoomApi,
) {

    suspend fun getRoom(idOrSlug: String, username: String): Result<RoomDto> = runCatching {
        roomApi.getRoom(idOrSlug, username)
    }

    /** 404 → success(null) so the UI can show "no summary yet" + Regenerate. */
    suspend fun getSummary(idOrSlug: String): Result<SummaryDto?> = runCatching {
        try {
            roomApi.getSummary(idOrSlug)
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }
    }

    suspend fun getActionItems(idOrSlug: String): Result<List<ActionItemDto>> = runCatching {
        roomApi.getActionItems(idOrSlug)
    }

    suspend fun getTranscripts(idOrSlug: String): Result<List<TranscriptDto>> = runCatching {
        roomApi.getTranscripts(idOrSlug)
    }

    /**
     * Kick off backend summary regeneration. Returns when the backend
     * acknowledges the request (Celery has been notified); the new
     * summary itself is fetched by the next [getSummary] call.
     */
    suspend fun regenerateSummary(idOrSlug: String): Result<Unit> = runCatching {
        roomApi.regenerateSummary(idOrSlug)
        Unit
    }
}
