package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.RequestBody
import retrofit2.http.*

interface MeetingInterpretationApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-interpretation/channels/")
    suspend fun state(@Query("room_id") roomId: String, @Query("livekit_room_sid") sid: String): InterpretationStateDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-interpretation/channels/")
    suspend fun control(@Body body: RequestBody): InterpretationChannelReceiptDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-interpretation/subscription/")
    suspend fun subscribe(@Body body: RequestBody): InterpretationListenReceiptDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-interpretation/renew/")
    suspend fun renew(@Body body: InterpretationRenewRequestDto): InterpretationSubscriptionDto
}
