package com.we.meet.data.api

import com.we.meet.data.api.dto.CreateRoomRequest
import com.we.meet.data.api.dto.EnterRequest
import com.we.meet.data.api.dto.ActionItemDto
import com.we.meet.data.api.dto.MuteParticipantRequest
import com.we.meet.data.api.dto.PagedRoomsDto
import com.we.meet.data.api.dto.RaiseHandRequest
import com.we.meet.data.api.dto.RegenerateSummaryResponse
import com.we.meet.data.api.dto.SummaryDto
import com.we.meet.data.api.dto.TranscriptDto
import com.we.meet.data.api.dto.RemoveParticipantRequest
import com.we.meet.data.api.dto.RenameParticipantRequest
import com.we.meet.data.api.dto.RequestEntryRequest
import com.we.meet.data.api.dto.RequestEntryResponse
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.api.dto.WaitingParticipantsResponse
import com.we.meet.data.api.dto.UpdateRoomRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Room control plane.  The backend's URL accepts either the UUID id or the
 * human-readable slug in the same path slot, so a single signature works.
 *
 * Auth: requires `Authorization: Bearer <access_token>` (added by
 * AuthInterceptor) for non-public rooms.
 */
interface RoomApi {

    @GET("api/v1.0/rooms/{idOrSlug}/")
    suspend fun getRoom(
        @Path("idOrSlug") idOrSlug: String,
        @Query("username") username: String,
    ): RoomDto

    /**
     * List rooms the authenticated user is a member of. Backend's
     * `RoomViewSet.list` already restricts to `filter(users=user)` so we
     * don't pass a `mine` flag. Paginated via DRF PageNumberPagination —
     * mobile only consumes page 1 (page_size=50) for now, which is well
     * within the room-membership cap any single user is likely to hit.
     */
    @GET("api/v1.0/rooms/")
    suspend fun listMyRooms(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): PagedRoomsDto

    @POST("api/v1.0/rooms/")
    suspend fun createRoom(
        @Query("username") username: String,
        @Body body: CreateRoomRequest,
    ): RoomDto

    @POST("api/v1.0/rooms/{idOrSlug}/end/")
    suspend fun endRoom(@Path("idOrSlug") idOrSlug: String)

    /**
     * Host-only: update room settings (currently access_level; room
     * configuration toggles are a follow-up). Backend permission is
     * scoped via the standard ModelViewSet `update` path which uses
     * room-level admin/owner gating.
     */
    @PATCH("api/v1.0/rooms/{idOrSlug}/")
    suspend fun updateRoom(
        @Path("idOrSlug") idOrSlug: String,
        @Body body: UpdateRoomRequest,
    ): RoomDto

    /**
     * Rename the *current* participant in a room (Web's equivalent of
     * `useRenameParticipant`). The backend resolves the participant from
     * the LiveKit token in `Authorization`, so we must NOT let
     * AuthInterceptor stamp the usual Keycloak Bearer here — pass the
     * `No-Auth: 1` marker AND the LiveKit Bearer explicitly.
     *
     * @param authHeader Pass `"Bearer ${livekitToken}"`.
     */
    @POST("api/v1.0/rooms/{idOrSlug}/rename/")
    suspend fun renameParticipant(
        @Path("idOrSlug") idOrSlug: String,
        @Header("No-Auth") noAuth: String = "1",
        @Header("Authorization") authHeader: String,
        @Body body: RenameParticipantRequest,
    )

    /**
     * Toggle the *current* participant's raised-hand state. Same auth shape
     * as `/rename/` — LiveKit-token identity, so skip AuthInterceptor.
     *
     * @param authHeader Pass `"Bearer ${livekitToken}"`.
     */
    @POST("api/v1.0/rooms/{idOrSlug}/toggle-hand/")
    suspend fun toggleHand(
        @Path("idOrSlug") idOrSlug: String,
        @Header("No-Auth") noAuth: String = "1",
        @Header("Authorization") authHeader: String,
        @Body body: RaiseHandRequest,
    )

    /**
     * Owner-only: kick a participant out of the room. AuthInterceptor's
     * default Keycloak Bearer is what the backend's `HasPrivilegesOnRoom`
     * permission expects — do NOT mark this No-Auth.
     */
    @POST("api/v1.0/rooms/{idOrSlug}/remove-participant/")
    suspend fun removeParticipant(
        @Path("idOrSlug") idOrSlug: String,
        @Body body: RemoveParticipantRequest,
    )

    /**
     * Owner-only: mute a specific publication on a participant. Unlike
     * remove-participant (which lives on the default auth chain), the
     * mute endpoint declares `authentication_classes=[LiveKitTokenAuth, ...]`
     * — and `LiveKitTokenAuthentication.authenticate` raises
     * AuthenticationFailed (NOT returns None) when it can't parse the
     * Bearer as a LiveKit JWT, so a Keycloak Bearer here never falls
     * through to OIDC. Same shape as /rename/ and /toggle-hand/: skip
     * the interceptor's auto Keycloak Bearer with No-Auth and pass the
     * LiveKit token explicitly. Owner's LiveKit token is room-scoped,
     * which satisfies CanMuteParticipant regardless of `everyone_can_mute`.
     */
    @POST("api/v1.0/rooms/{idOrSlug}/mute-participant/")
    suspend fun muteParticipant(
        @Path("idOrSlug") idOrSlug: String,
        @Header("No-Auth") noAuth: String = "1",
        @Header("Authorization") authHeader: String,
        @Body body: MuteParticipantRequest,
    )

    /**
     * Lobby: ask to be let into a room. Idempotent / poll-friendly —
     * call it once to enter the waiting queue, then keep calling at a
     * sane interval to refresh the queue position and observe status
     * transitions until you either receive an accepted+livekit reply or
     * a denied reply. Auth is intentionally absent on the backend
     * (`permission_classes=[]`) so anonymous web visitors can use it;
     * but the lobby tracks identity through an HTTP cookie, so the
     * client's cookie jar must persist across polls.
     */
    @POST("api/v1.0/rooms/{idOrSlug}/request-entry/")
    suspend fun requestEntry(
        @Path("idOrSlug") idOrSlug: String,
        @Body body: RequestEntryRequest,
    ): RequestEntryResponse

    /**
     * Owner-only: list participants currently waiting in the lobby.
     * Backend uses `HasPrivilegesOnRoom`, so AuthInterceptor's default
     * Keycloak Bearer is what we want (do NOT mark No-Auth).
     */
    @GET("api/v1.0/rooms/{idOrSlug}/waiting-participants/")
    suspend fun listWaitingParticipants(
        @Path("idOrSlug") idOrSlug: String,
    ): WaitingParticipantsResponse

    /**
     * Owner-only: admit or reject a waiting participant. `allow_entry`
     * decides which: true → ACCEPTED + extended timeout matching their
     * LiveKit token, false → DENIED + short timeout (so they can see
     * the rejection on their next poll before the entry vanishes).
     */
    @POST("api/v1.0/rooms/{idOrSlug}/enter/")
    suspend fun admitParticipant(
        @Path("idOrSlug") idOrSlug: String,
        @Body body: EnterRequest,
    )

    /**
     * Meeting summary (markdown text + status). 404 = no summary yet —
     * caller surfaces an empty state and offers regenerate.
     */
    @GET("api/v1.0/rooms/{idOrSlug}/summary/")
    suspend fun getSummary(@Path("idOrSlug") idOrSlug: String): SummaryDto

    /**
     * Action items extracted from the meeting. Empty list when none.
     * Backend orders by `sort_order`.
     */
    @GET("api/v1.0/rooms/{idOrSlug}/action-items/")
    suspend fun getActionItems(@Path("idOrSlug") idOrSlug: String): List<ActionItemDto>

    /**
     * Full transcript rows for the meeting, ordered by `started_at`.
     * Each row carries `translations` map keyed by language code.
     */
    @GET("api/v1.0/rooms/{idOrSlug}/transcripts/")
    suspend fun getTranscripts(@Path("idOrSlug") idOrSlug: String): List<TranscriptDto>

    /**
     * Re-run summary + action-item generation. Async on backend
     * (Celery) — caller refetches summary/action-items shortly after.
     */
    @POST("api/v1.0/rooms/{idOrSlug}/summary/regenerate/")
    suspend fun regenerateSummary(
        @Path("idOrSlug") idOrSlug: String,
    ): RegenerateSummaryResponse
}
