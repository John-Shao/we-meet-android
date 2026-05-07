package com.we.meet.data.repository

import com.we.meet.BuildConfig
import com.we.meet.data.api.RoomApi
import com.we.meet.data.api.dto.CreateRoomRequest
import com.we.meet.data.api.dto.RoomDto

/**
 * Fetches a room's connection info from the backend.  The interesting payload
 * is the nested `livekit: { url, room, token }` block which is what
 * [com.we.meet.livekit.LiveKitController] needs to actually connect.
 */
class RoomRepository(
    private val roomApi: RoomApi,
) {

    /** Create a new room and return its connection info. */
    suspend fun createRoom(username: String, roomName: String): Result<RoomDto> = runCatching {
        val room = roomApi.createRoom(username, CreateRoomRequest(name = roomName))
        applyLivekitOverride(room)
    }

    /** Resolve a room by id (UUID) or slug.  Returns Result.failure on error. */
    suspend fun getRoom(idOrSlug: String, username: String): Result<RoomDto> = runCatching {
        val room = roomApi.getRoom(idOrSlug.trim(), username)
        applyLivekitOverride(room)
    }

    /** End (close) a room. Only the owner can do this. */
    suspend fun endRoom(idOrSlug: String): Result<Unit> = runCatching {
        roomApi.endRoom(idOrSlug)
    }

    /** Apply optional LiveKit URL override (used for local-dev port forwarding). */
    private fun applyLivekitOverride(room: RoomDto): RoomDto {
        val override = BuildConfig.WE_MEET_LIVEKIT_URL_OVERRIDE
        return if (override.isNotBlank() && room.livekit != null) {
            room.copy(livekit = room.livekit.copy(url = override))
        } else {
            room
        }
    }
}
