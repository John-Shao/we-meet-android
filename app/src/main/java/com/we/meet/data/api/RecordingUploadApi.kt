package com.we.meet.data.api

import com.squareup.moshi.Json
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

data class RecordingUploadCapabilities(
    val available: Boolean,
    @Json(name = "max_bytes") val maxBytes: Long = 0,
    val extensions: List<String> = emptyList(),
    /** True once the server offers presigned direct uploads. */
    @Json(name = "direct_upload_available") val directUploadAvailable: Boolean = false,
    /** The direct branch's ceiling; 0 when direct uploads are off. */
    @Json(name = "direct_max_bytes") val directMaxBytes: Long = 0,
)

data class RecordingUploadState(
    @Json(name = "record_id") val recordId: String,
    val status: String,
    val attempt: Int,
    val retryable: Boolean = false,
)

data class RecordingUploadRetry(val attempt: Int)

/** The declaration signed into the PUT, plus the options the job will carry. */
data class RecordingUploadPresign(
    val key: String,
    val name: String,
    val size: Long,
    @Json(name = "content_type") val contentType: String,
    val context: String,
    val hotwords: String,
)

/** One signed PUT, valid for one exact object. */
data class RecordingUploadTicket(
    @Json(name = "upload_url") val uploadUrl: String,
    @Json(name = "storage_name") val storageName: String,
    val headers: Map<String, String> = emptyMap(),
)

/** The same declaration plus the storage key the server handed out. */
data class RecordingUploadComplete(
    val key: String,
    val name: String,
    val size: Long,
    @Json(name = "content_type") val contentType: String,
    @Json(name = "storage_name") val storageName: String,
    val context: String,
    val hotwords: String,
)

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

    /** Step one of the direct flow: sign one PUT for an exact byte count. */
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/recording-uploads/upload-url/")
    suspend fun presign(@Body body: RecordingUploadPresign): RecordingUploadTicket

    /** Step two: adopt the object after storage has it. */
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/recording-uploads/upload-complete/")
    suspend fun complete(@Body body: RecordingUploadComplete): RecordingUploadState

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/recording-uploads/{record}/")
    suspend fun state(@Path("record") recordId: String): RecordingUploadState

    @POST("api/v1.0/recording-uploads/{record}/")
    suspend fun retry(@Path("record") recordId: String, @Body body: RecordingUploadRetry): RecordingUploadState
}
