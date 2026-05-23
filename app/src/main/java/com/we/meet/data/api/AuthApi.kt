package com.we.meet.data.api

import com.we.meet.data.api.dto.RefreshTokenRequest
import com.we.meet.data.api.dto.SendOtpRequest
import com.we.meet.data.api.dto.SendOtpResponse
import com.we.meet.data.api.dto.VerifyOtpRequest
import com.we.meet.data.api.dto.VerifyOtpResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Mobile authentication endpoints.  Documented in
 * ../jusi_meet_suite1.9/docs/mobile-integration-auth.md.
 *
 * These endpoints do NOT require an Authorization header.
 */
interface AuthApi {

    @POST("api/mobile/auth/send-otp/")
    suspend fun sendOtp(@Body body: SendOtpRequest): SendOtpResponse

    @POST("api/mobile/auth/verify-otp/")
    suspend fun verifyOtp(@Body body: VerifyOtpRequest): VerifyOtpResponse

    /**
     * Trade a refresh_token for a fresh access/refresh pair. The backend
     * holds the client_secret. Used by [TokenRefreshAuthenticator] on a 401
     * — Keycloak rotates the refresh token by default, so the response's
     * refresh_token MUST overwrite the stored one.
     */
    @POST("api/mobile/auth/refresh/")
    suspend fun refresh(@Body body: RefreshTokenRequest): VerifyOtpResponse
}
