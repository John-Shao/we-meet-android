package com.we.meet.data.api

import com.we.meet.data.api.dto.CloudRecordingReceiptDto
import com.we.meet.data.api.dto.CloudRecordingStateDto
import okhttp3.RequestBody
import retrofit2.http.*

interface CloudRecordingApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/cloud-recording/control/")
    suspend fun state(@Query("room_id") roomId: String, @Query("livekit_room_sid") sid: String): CloudRecordingStateDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/cloud-recording/control/")
    suspend fun control(@Body body: RequestBody): CloudRecordingReceiptDto
}
