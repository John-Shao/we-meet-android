package com.we.meet.data.repository

import com.we.meet.BuildConfig
import com.we.meet.data.api.RoomApi
import com.we.meet.data.api.dto.CreateRoomRequest
import com.we.meet.data.api.dto.EnterRequest
import com.we.meet.data.api.dto.MuteParticipantRequest
import com.we.meet.data.api.dto.RaiseHandRequest
import com.we.meet.data.api.dto.RemoveParticipantRequest
import com.we.meet.data.api.dto.RenameParticipantRequest
import com.we.meet.data.api.dto.RequestEntryRequest
import com.we.meet.data.api.dto.RequestEntryResponse
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.api.dto.UpdateRoomRequest
import com.we.meet.data.api.dto.WaitingParticipantDto

/**
 * Fetches a room's connection info from the backend.  The interesting payload
 * is the nested `livekit: { url, room, token }` block which is what
 * [com.we.meet.livekit.LiveKitController] needs to actually connect.
 */
class RoomRepository(
    private val roomApi: RoomApi,
) {

    /**
     * Create a new room and return its connection info. [scheduledAtIso]
     * is optional — when supplied the backend persists it on
     * Room.scheduled_at; downstream UIs (invite sheet, history) surface
     * it as the intended start time. Null = persistent, joinable-anytime
     * room.
     */
    suspend fun createRoom(
        username: String,
        roomName: String,
        scheduledAtIso: String? = null,
    ): Result<RoomDto> = runCatching {
        val room = roomApi.createRoom(
            username,
            CreateRoomRequest(name = roomName, scheduled_at = scheduledAtIso),
        )
        applyLivekitOverride(room)
    }

    /** Resolve a room by id (UUID) or slug.  Returns Result.failure on error. */
    suspend fun getRoom(idOrSlug: String, username: String): Result<RoomDto> = runCatching {
        val room = roomApi.getRoom(idOrSlug.trim(), username)
        applyLivekitOverride(room)
    }

    /**
     * List rooms the authenticated user is a member of. Backend filters
     * by `users=request.user` so any logged-in account hits its own list.
     * Anonymous callers receive an empty list (backend short-circuits).
     *
     * Used by Home to overlay server-side history on top of the local
     * HistoryStore — entries the user joined from another device land
     * here, the local store contributes the per-device "last visited"
     * timestamps and observed participants.
     */
    suspend fun fetchMyRooms(): Result<List<RoomDto>> = runCatching {
        roomApi.listMyRooms().results.map { applyLivekitOverride(it) }
    }

    /** End (close) a room. Only the owner can do this. */
    suspend fun endRoom(idOrSlug: String): Result<Unit> = runCatching {
        roomApi.endRoom(idOrSlug)
    }

    /**
     * Host-only: start/stop a transcript recording on the backend. The
     * UI surface comes through LiveKit's `RecordingStatusChanged` event,
     * not the HTTP response — these calls just kick off / shut down
     * the Egress worker.
     */
    suspend fun startRecording(idOrSlug: String): Result<Unit> = runCatching {
        roomApi.startRecording(
            idOrSlug,
            com.we.meet.data.api.dto.StartRecordingRequest(),
        )
    }

    suspend fun stopRecording(idOrSlug: String): Result<Unit> = runCatching {
        roomApi.stopRecording(idOrSlug)
    }

    /**
     * Start realtime subtitle (Doubao ASR via meet-agent-subtitles) on
     * the backend. Auth chain requires LiveKit token, not Keycloak — caller
     * passes the room's livekit token verbatim. Once started, the agent
     * publishes transcription events the client picks up via
     * RoomEvent.TranscriptionReceived; there is no stop endpoint.
     */
    suspend fun startSubtitle(idOrSlug: String, livekitToken: String): Result<Unit> = runCatching {
        roomApi.startSubtitle(idOrSlug, authHeader = "Bearer $livekitToken")
    }

    /**
     * Owner-only: delete the room on the backend. Returns [Result.failure]
     * with the underlying HttpException when the caller isn't the owner
     * (HTTP 403) — callers should fall back to local-only removal in
     * that case so participants can still hide ended meetings from
     * their own history.
     */
    suspend fun deleteRoom(idOrSlug: String): Result<Unit> = runCatching {
        roomApi.deleteRoom(idOrSlug)
    }

    /**
     * Host-only: change the room's access level
     * ("public" | "trusted" | "restricted"). "restricted" gates the
     * lobby flow; "trusted" lets logged-in users bypass; "public"
     * disables the lobby entirely.
     */
    suspend fun updateAccessLevel(
        idOrSlug: String,
        accessLevel: String,
    ): Result<RoomDto> = runCatching {
        applyLivekitOverride(
            roomApi.updateRoom(idOrSlug, UpdateRoomRequest(access_level = accessLevel))
        )
    }

    /**
     * Rename the *current* participant (i.e. the local user) in a room.
     * Backend identifies the participant from the supplied LiveKit token,
     * so callers must pass the same token the SDK is using on the wire.
     */
    /** P4-M2: owner-only room rename (standard ModelViewSet PATCH gating). */
    suspend fun renameRoom(idOrSlug: String, name: String): Result<Unit> = runCatching {
        roomApi.updateRoom(idOrSlug, UpdateRoomRequest(name = name))
        Unit
    }

    suspend fun renameSelf(
        idOrSlug: String,
        livekitToken: String,
        name: String,
    ): Result<Unit> = runCatching {
        roomApi.renameParticipant(
            idOrSlug = idOrSlug,
            authHeader = "Bearer $livekitToken",
            body = RenameParticipantRequest(name = name),
        )
    }

    /** Raise or lower the local participant's hand. */
    suspend fun toggleHand(
        idOrSlug: String,
        livekitToken: String,
        raised: Boolean,
    ): Result<Unit> = runCatching {
        roomApi.toggleHand(
            idOrSlug = idOrSlug,
            authHeader = "Bearer $livekitToken",
            body = RaiseHandRequest(raised = raised),
        )
    }

    /** Owner-only: kick the named participant out of the room. */
    suspend fun removeParticipant(
        idOrSlug: String,
        identity: String,
    ): Result<Unit> = runCatching {
        roomApi.removeParticipant(
            idOrSlug = idOrSlug,
            body = RemoveParticipantRequest(participant_identity = identity),
        )
    }

    /**
     * Owner-only: silence a participant's microphone. Backend mutes the
     * specific publication SID; LiveKit then pushes the mute to the
     * target client, which auto-flips its local mic off.
     *
     * Auth uses the caller's LiveKit token — backend's mute-participant
     * endpoint chains LiveKitTokenAuthentication first and a Keycloak
     * Bearer would 401 there before falling through to OIDC.
     */
    suspend fun muteParticipantMicrophone(
        idOrSlug: String,
        livekitToken: String,
        identity: String,
        micTrackSid: String,
    ): Result<Unit> = runCatching {
        roomApi.muteParticipant(
            idOrSlug = idOrSlug,
            authHeader = "Bearer $livekitToken",
            body = MuteParticipantRequest(
                participant_identity = identity,
                track_sid = micTrackSid,
            ),
        )
    }

    /**
     * Lobby: visitor's request-entry call. Status returned drives the
     * UI state machine: "waiting" → keep polling, "accepted" → connect
     * to LiveKit with the supplied token, "denied" → tell the user the
     * host rejected them, retry blocked for a short window.
     */
    suspend fun requestEntry(
        idOrSlug: String,
        username: String,
    ): Result<RequestEntryResponse> = runCatching {
        applyLivekitOverrideEntry(
            roomApi.requestEntry(idOrSlug, RequestEntryRequest(username = username))
        )
    }

    /** Owner-only: snapshot of the lobby. Caller is expected to poll. */
    suspend fun listWaitingParticipants(
        idOrSlug: String,
    ): Result<List<WaitingParticipantDto>> = runCatching {
        roomApi.listWaitingParticipants(idOrSlug).participants
    }

    /** Owner-only: admit ([allow]=true) or reject ([allow]=false) one waiter. */
    suspend fun admitParticipant(
        idOrSlug: String,
        participantId: String,
        allow: Boolean,
    ): Result<Unit> = runCatching {
        roomApi.admitParticipant(
            idOrSlug = idOrSlug,
            body = EnterRequest(
                participant_id = participantId,
                allow_entry = allow,
            ),
        )
    }

    private fun applyLivekitOverrideEntry(resp: RequestEntryResponse): RequestEntryResponse {
        val override = BuildConfig.WE_MEET_LIVEKIT_URL_OVERRIDE
        return if (override.isNotBlank() && resp.livekit != null) {
            resp.copy(livekit = resp.livekit.copy(url = override))
        } else {
            resp
        }
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
