package com.we.meet.data.api

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.POST

/**
 * Push-token registry on the we-meet backend (P0 offline push). The App
 * uploads the Getui cid after (a) Getui hands us a cid and (b) the user is
 * logged in — see [com.we.meet.push.PushTokenUploader]. Goes through the main
 * authed Retrofit stack (Bearer attached by AuthInterceptor), same as every
 * other we-meet endpoint.
 */
interface PushApi {

    @POST("api/v1.0/push/tokens/")
    suspend fun registerToken(@Body body: PushTokenRequest): Response<Unit>

    /** Unregister on logout so the backend stops pushing to this device. */
    @HTTP(method = "DELETE", path = "api/v1.0/push/tokens/", hasBody = true)
    suspend fun unregisterToken(@Body body: PushTokenDeleteRequest): Response<Unit>
}

@JsonClass(generateAdapter = true)
data class PushTokenRequest(
    val cid: String,
    val device_id: String? = null,
    val platform: String = "android",
    val app_version: String? = null,
)

@JsonClass(generateAdapter = true)
data class PushTokenDeleteRequest(
    val cid: String,
)
