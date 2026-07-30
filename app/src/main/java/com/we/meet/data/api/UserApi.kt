package com.we.meet.data.api

import com.we.meet.data.api.dto.ConfirmProfileImageRequest
import com.we.meet.data.api.dto.UpdateIntroRequest
import com.we.meet.data.api.dto.UpdateNicknameRequest
import com.we.meet.data.api.dto.UpdateTimezoneRequest
import com.we.meet.data.api.dto.UploadUrlRequest
import com.we.meet.data.api.dto.UploadUrlResponse
import com.we.meet.data.api.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * meet-backend user / profile endpoints. Documented in
 * ../jusi_meet_suite1.9/docs/mobile-integration-profile.md.
 *
 * All calls require a Bearer access token; [AuthInterceptor] attaches it
 * automatically.
 */
interface UserApi {

    /** Fetch the currently logged-in user's full profile. */
    @GET("api/v1.0/users/me/")
    suspend fun getMe(): UserDto

    /** Update the user's bio (server enforces 100-char cap). */
    @PATCH("api/v1.0/users/{id}/")
    suspend fun updateIntro(
        @Path("id") userId: String,
        @Body body: UpdateIntroRequest,
    ): UserDto

    /**
     * Report this device's IANA timezone (e.g. `Asia/Shanghai`). The server
     * interprets 免打扰时段 in this zone, so an unreported zone means quiet hours
     * fire at the wrong wall-clock time. Mirrors what the web client does with
     * the browser zone.
     */
    @PATCH("api/v1.0/users/{id}/")
    suspend fun updateTimezone(
        @Path("id") userId: String,
        @Body body: UpdateTimezoneRequest,
    ): UserDto

    /** Request a presigned PUT URL for an avatar / cover image upload. */
    @POST("api/v1.0/users/me/upload-url/")
    suspend fun requestProfileUploadUrl(
        @Body body: UploadUrlRequest,
    ): UploadUrlResponse

    /** Confirm a freshly-uploaded image and persist its URL on the user. */
    @PATCH("api/v1.0/users/me/profile-image/")
    suspend fun confirmProfileImage(
        @Body body: ConfirmProfileImageRequest,
    ): UserDto

    /**
     * Update the display nickname. Backend uses Keycloak Admin API
     * (service-account token) to set firstName, bypassing the realm's user
     * profile validator that requires lastName/email — those are blank for
     * SMS-registered users so the public Account REST API would reject the
     * change.
     */
    @PATCH("api/v1.0/users/me/nickname/")
    suspend fun updateNickname(
        @Body body: UpdateNicknameRequest,
    ): UserDto

    /**
     * Deregister (soft-delete + anonymize) the current user account.
     * Server returns 204 No Content on success — Retrofit's suspend Unit
     * adapter accepts that as a non-failure.
     */
    @POST("api/v1.0/users/me/deregister/")
    suspend fun deregister()
}
