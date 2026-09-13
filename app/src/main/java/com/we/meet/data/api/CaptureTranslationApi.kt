package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.RequestBody
import retrofit2.http.*

interface CaptureTranslationApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/translation/")
    suspend fun state(@Path("capture") capture: String): CaptureTranslationStateDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/translation/")
    suspend fun control(@Path("capture") capture: String, @Header("X-Capture-Lease") lease: String, @Body body: RequestBody): CaptureTranslationReceiptDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/translation/ticket/")
    suspend fun ticket(@Path("capture") capture: String, @Header("X-Capture-Lease") lease: String, @Body body: CaptureTranslationTicketRequestDto): CaptureTranslationTicketDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/translation/archives/")
    suspend fun archives(@Path("capture") capture: String, @Query("cursor") cursor: String?): CaptureTranslationArchivesDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/translation/archives/{archive}/")
    suspend fun segments(@Path("capture") capture: String, @Path("archive") archive: String, @Query("cursor") cursor: String?): CaptureTranslatedSegmentsDto
}
