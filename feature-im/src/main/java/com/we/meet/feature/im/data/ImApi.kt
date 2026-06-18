package com.we.meet.feature.im.data

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * we-meet backend IM bridge endpoint surface.
 *
 * The body of `POST /api/v1.0/im/token/` is intentionally empty — the backend
 * looks at the authenticated user (via OIDC bearer auto-attached by the host
 * OkHttp interceptor) and signs a jusi-light-im JWT bound to that user.
 */
internal interface ImApi {

    @POST("api/v1.0/im/token/")
    suspend fun fetchToken(@Body body: Map<String, String> = emptyMap()): ImTokenResponse
}

@JsonClass(generateAdapter = true)
internal data class ImTokenResponse(
    val uid: String,
    val token: String,
    val ws_url: String,
    val expires_at: Long,
)
