package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.RequestBody
import retrofit2.http.*

interface MeetingTranslationApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-translations/control/")
    suspend fun state(@Query("room_id") roomId: String, @Query("livekit_room_sid") sid: String): PrivateTranslationStateDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-translations/control/")
    suspend fun control(@Body body: RequestBody): PrivateTranslationReceiptDto
}
