package com.we.meet.data.api

import com.we.meet.data.api.dto.CreateRoomRequest
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
}
