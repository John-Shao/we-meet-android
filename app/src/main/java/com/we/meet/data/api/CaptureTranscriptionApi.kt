package com.we.meet.data.api

import com.we.meet.data.api.dto.CaptureAsrCreatedDto
import com.we.meet.data.api.dto.CaptureAsrJobDto
import com.we.meet.data.api.dto.CaptureAsrPreviewDto
import com.we.meet.data.api.dto.CaptureAsrStateDto
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CaptureTranscriptionApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/transcription/")
    suspend fun state(@Path("capture") capture: String): CaptureAsrStateDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/transcription/")
    suspend fun request(@Path("capture") capture: String, @Header("Idempotency-Key") key: String, @Body request: RequestBody): CaptureAsrCreatedDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/transcription/{job}/cancel/")
    suspend fun cancel(@Path("capture") capture: String, @Path("job") job: String, @Body empty: RequestBody): CaptureAsrJobDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/transcription/{job}/preview/")
    suspend fun preview(@Path("capture") capture: String, @Path("job") job: String, @Query("after_sequence") after: Int): CaptureAsrPreviewDto
}
