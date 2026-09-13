package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.RequestBody
import retrofit2.http.*

interface OnlineCaptureApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/online-captures/control/")
    suspend fun state(@Query("room_id") roomId: String, @Query("livekit_room_sid") sid: String): OnlineCaptureStateDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/online-captures/control/")
    suspend fun control(@Body body: RequestBody): OnlineCaptureReceiptDto
}

/** Join-token notice only. Its HTTP client has no account cookies, refresh authenticator or auth interceptor. */
interface OnlineCaptureNoticeApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-capture-status/")
    suspend fun notice(@Query("room_id") roomId: String, @Query("livekit_room_sid") sid: String,
        @Header("Authorization") authorization: String): OnlineCaptureNoticeDto
}
