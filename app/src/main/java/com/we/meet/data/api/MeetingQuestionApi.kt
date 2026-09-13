package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.RequestBody
import retrofit2.http.*

interface MeetingQuestionApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/questions/")
    suspend fun recent(@Path("record") record: String): RecordQuestionsDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/questions/{question}/")
    suspend fun question(@Path("record") record: String, @Path("question") question: String): RecordQuestionDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/questions/")
    suspend fun ask(@Path("record") record: String, @Body body: RequestBody): RecordQuestionDto
}
