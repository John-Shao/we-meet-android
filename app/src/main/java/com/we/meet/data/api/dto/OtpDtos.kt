package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/** Body for `POST /api/mobile/auth/send-otp/`. */
@JsonClass(generateAdapter = true)
data class SendOtpRequest(
    val phone: String,
)

/** Response for `POST /api/mobile/auth/send-otp/`. */
@JsonClass(generateAdapter = true)
data class SendOtpResponse(
    val success: Boolean,
    val expires_in: Int,
)

/** Body for `POST /api/mobile/auth/verify-otp/`. */
@JsonClass(generateAdapter = true)
data class VerifyOtpRequest(
    val phone: String,
    val otp: String,
)

/** Response for `POST /api/mobile/auth/verify-otp/`. */
@JsonClass(generateAdapter = true)
data class VerifyOtpResponse(
    val access_token: String,
    val refresh_token: String,
    val token_type: String,
    val expires_in: Int,
)

/** Generic error envelope returned by the mobile auth endpoints on 4xx. */
@JsonClass(generateAdapter = true)
data class ErrorResponse(
    val error: String?,
)
