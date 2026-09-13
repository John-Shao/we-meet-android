package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.RequestBody
import retrofit2.http.*

interface MeetingSummaryApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/summary-job/")
    suspend fun progress(@Path("record") record: String): SummaryProgressDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/summary-requests/")
    suspend fun request(@Path("record") record: String, @Header("Idempotency-Key") key: String, @Body body: RequestBody): SummaryAcceptedDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/summary-automation/")
    suspend fun automation(@Path("record") record: String): SummaryAutomationDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/summary-automation/")
    suspend fun control(@Path("record") record: String, @Header("Idempotency-Key") key: String, @Body body: RequestBody): SummaryAutomationAcceptedDto
}
