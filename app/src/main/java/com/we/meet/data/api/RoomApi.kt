package com.we.meet.data.api

import com.we.meet.data.api.dto.CreateRoomRequest
import com.we.meet.data.api.dto.MuteParticipantRequest
import com.we.meet.data.api.dto.RaiseHandRequest
import com.we.meet.data.api.dto.RemoveParticipantRequest
import com.we.meet.data.api.dto.RenameParticipantRequest
import com.we.meet.data.api.dto.RoomDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
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

    @POST("api/v1.0/rooms/")
    suspend fun createRoom(
        @Query("username") username: String,
        @Body body: CreateRoomRequest,
    ): RoomDto

    @POST("api/v1.0/rooms/{idOrSlug}/end/")
    suspend fun endRoom(@Path("idOrSlug") idOrSlug: String)

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
     * Owner-only: mute a specific publication on a participant. Same auth
     * story as remove-participant — Keycloak Bearer via AuthInterceptor.
     * Caller passes the microphone publication's SID for the "silence a
     * noisy participant" flow.
     */
    @POST("api/v1.0/rooms/{idOrSlug}/mute-participant/")
    suspend fun muteParticipant(
        @Path("idOrSlug") idOrSlug: String,
        @Body body: MuteParticipantRequest,
    )
}
