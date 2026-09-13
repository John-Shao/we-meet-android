package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.RequestBody
import retrofit2.http.*

interface MeetingReviewApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/human-summary/")
    suspend fun current(@Path("record") record: String): HumanReviewStateDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/human-summary/")
    suspend fun save(@Path("record") record: String, @Body body: RequestBody): HumanReviewAcceptedDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/human-summary/history/")
    suspend fun history(@Path("record") record: String, @Query("before") before: Int?): HumanReviewHistoryDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/human-summary/history/{review}/")
    suspend fun version(@Path("record") record: String, @Path("review") review: String): HumanReviewDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/summary-tasks/")
    suspend fun tasks(@Path("record") record: String, @Query("q") query: String?): SummaryTasksStateDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/summary-tasks/")
    suspend fun convert(@Path("record") record: String, @Body body: RequestBody): SummaryTaskAcceptedDto
}
