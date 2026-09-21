package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.ResponseBody
import retrofit2.http.*

interface UploadTranslationApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/upload-translations/")
    suspend fun list(@Path("record") record: String): UploadTranslationListDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/upload-translations/")
    suspend fun generate(@Path("record") record: String, @Body body: UploadTranslationRequestDto): UploadTranslationDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/upload-translations/{translation}/")
    suspend fun detail(@Path("record") record: String, @Path("translation") translation: String, @Query("page") page: Int): UploadTranslationDto
    @Streaming
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/upload-translations/{translation}/export/")
    suspend fun export(@Path("record") record: String, @Path("translation") translation: String, @Query("as") format: String): ResponseBody
}
