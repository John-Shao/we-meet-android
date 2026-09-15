package com.we.meet.data.api

import com.squareup.moshi.Json
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

data class RecordingUploadCapabilities(
    val available: Boolean,
    @Json(name = "max_bytes") val maxBytes: Long = 0,
    val extensions: List<String> = emptyList(),
)

data class RecordingUploadState(
    @Json(name = "record_id") val recordId: String,
    val status: String,
    val attempt: Int,
    val retryable: Boolean = false,
)

data class RecordingUploadRetry(val attempt: Int)

interface RecordingUploadApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/recording-uploads/")
    suspend fun capabilities(): RecordingUploadCapabilities

    @Multipart
    @POST("api/v1.0/recording-uploads/")
    suspend fun upload(
        @Part("key") key: RequestBody,
        @Part audio: MultipartBody.Part,
        @Part("context") context: RequestBody,
        @Part("hotwords") hotwords: RequestBody,
    ): RecordingUploadState

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/recording-uploads/{record}/")
    suspend fun state(@Path("record") recordId: String): RecordingUploadState

    @POST("api/v1.0/recording-uploads/{record}/")
    suspend fun retry(@Path("record") recordId: String, @Body body: RecordingUploadRetry): RecordingUploadState
}
